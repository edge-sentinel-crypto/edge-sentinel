package com.airbridge.airplay.session

import com.airbridge.airplay.airplay.AirPlayException
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AirPlaySender
import com.airbridge.airplay.airplay.AirPlayVolume
import com.airbridge.airplay.airplay.AudioFrame
import com.airbridge.airplay.airplay.AuthenticationFailedAirPlayException
import com.airbridge.airplay.airplay.PasswordRequiredException
import com.airbridge.airplay.airplay.PinRequiredException
import com.airbridge.airplay.airplay.StreamTimeline
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.util.AirLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** State of one speaker in the current stream. */
enum class SessionState { CONNECTING, STREAMING, NEEDS_PIN, NEEDS_PASSWORD, RETRYING, FAILED, STOPPED }

/** Snapshot published to the UI. */
data class ReceiverStatus(
    val receiverId: String,
    val name: String,
    val state: SessionState,
    val protocol: AirPlayProtocol?,
    val volumePercent: Int,
    val message: String? = null,
    val packetsSent: Long = 0,
    val retransmits: Long = 0,
    val droppedFrames: Long = 0,
)

/**
 * One speaker inside a stream: its own worker thread and bounded frame queue, so a slow or dead
 * speaker can never stall the capture pipeline or the other speakers. Handles connect, protocol
 * fallback (AirPlay 2 -> AirPlay 1 when both are advertised), reconnect with backoff, PIN and
 * password prompts, volume and teardown.
 */
class ReceiverSession(
    val receiver: AirPlayReceiver,
    @Volatile var options: ConnectOptions,
    private val factory: SenderFactory,
    private val timeline: StreamTimeline,
    private val nextFrameIndex: () -> Long,
    private val onStatus: (ReceiverStatus) -> Unit,
    queueCapacity: Int = 96,
) {
    private val queue = ArrayBlockingQueue<AudioFrame>(queueCapacity)
    private val dropped = AtomicLong()
    @Volatile private var running = true
    @Volatile private var sender: AirPlaySender? = null
    @Volatile var state: SessionState = SessionState.CONNECTING
        private set
    @Volatile private var message: String? = null
    @Volatile private var activeProtocol: AirPlayProtocol? = null
    private val thread = Thread({ run() }, "airplay-session-${receiver.name}").apply { isDaemon = true }
    private val retryLock = ReentrantLock()
    private val retrySignal = retryLock.newCondition()
    @Volatile private var retryRequested = false

    fun start() = thread.start()

    /** Called by the fan-out for every captured frame. Never blocks. */
    fun offer(frame: AudioFrame) {
        if (state != SessionState.STREAMING) return
        while (!queue.offer(frame)) {
            queue.poll()
            dropped.incrementAndGet()
        }
    }

    fun setVolume(percent: Int) {
        options = options.copy(volumePercent = percent)
        sender?.let { s -> if (state == SessionState.STREAMING) s.setVolume(AirPlayVolume.percentToDb(percent)) }
        publish()
    }

    /** Supplies a PIN or password and reconnects immediately. */
    fun retryWith(pin: String? = options.pin, password: String? = options.password) {
        options = options.copy(pin = pin, password = password)
        retryLock.withLock {
            retryRequested = true
            retrySignal.signalAll()
        }
    }

    fun stop() {
        running = false
        retryLock.withLock { retrySignal.signalAll() }
        thread.interrupt()
    }

    fun join(timeoutMs: Long) = thread.join(timeoutMs)

    private fun run() {
        var attempt = 0
        while (running) {
            if (connectOnce()) {
                attempt = 0
                streamLoop()
                if (!running) break
                setState(SessionState.RETRYING, "Connection lost, reconnecting")
                waitForRetry(1_000)
                continue
            }
            if (!running) break
            if (state == SessionState.NEEDS_PIN || state == SessionState.NEEDS_PASSWORD) {
                waitForRetry(Long.MAX_VALUE) // until the user enters the code
                continue
            }
            attempt++
            if (attempt >= MAX_ATTEMPTS) {
                waitForRetry(Long.MAX_VALUE) // stays FAILED until the user taps retry
                attempt = 0
            } else {
                val backoff = BACKOFF_MS[minOf(attempt - 1, BACKOFF_MS.size - 1)]
                setState(SessionState.RETRYING, "${message ?: "Connection failed"} (retrying in ${backoff / 1000}s)")
                waitForRetry(backoff)
            }
        }
        closeSender()
        setState(SessionState.STOPPED, null)
    }

    private fun waitForRetry(ms: Long) {
        retryLock.withLock {
            if (!retryRequested && running) {
                try {
                    if (ms == Long.MAX_VALUE) retrySignal.await() else retrySignal.await(ms, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                }
            }
            retryRequested = false
        }
    }

    private fun connectOnce(): Boolean {
        closeSender()
        queue.clear()
        val primary = receiver.protocolFor(options.preference)
        val canFallBack = options.preference == ProtocolPreference.AUTO &&
            primary == AirPlayProtocol.AIRPLAY_2 && receiver.supportsAirPlay1
        val order = if (canFallBack) listOf(primary, AirPlayProtocol.AIRPLAY_1) else listOf(primary)
        for ((i, protocol) in order.withIndex()) {
            setState(SessionState.CONNECTING, "Connecting (${protocol.label})", protocol)
            val s = try {
                factory.create(receiver, protocol, options)
            } catch (e: Exception) {
                setState(SessionState.FAILED, "Cannot reach ${receiver.host}: ${e.message}")
                return false
            }
            try {
                s.connect(timeline, nextFrameIndex())
                sender = s
                activeProtocol = protocol
                setState(SessionState.STREAMING, null, protocol)
                return true
            } catch (e: PinRequiredException) {
                s.close()
                setState(SessionState.NEEDS_PIN, e.message, protocol)
                return false
            } catch (e: PasswordRequiredException) {
                s.close()
                setState(SessionState.NEEDS_PASSWORD, e.message, protocol)
                return false
            } catch (e: AuthenticationFailedAirPlayException) {
                s.close()
                if (options.pin != null) {
                    // Wrong code: ask the receiver for a fresh one, then wait for the user again.
                    options = options.copy(pin = null)
                    val again = connectOnce()
                    if (state == SessionState.NEEDS_PIN) setState(SessionState.NEEDS_PIN, "Wrong code. Enter the new code shown by ${receiver.name}", protocol)
                    return again
                }
                if (options.password != null) {
                    options = options.copy(password = null)
                    setState(SessionState.NEEDS_PASSWORD, "Wrong password for ${receiver.name}", protocol)
                    return false
                }
                setState(SessionState.FAILED, e.message, protocol)
                return false
            } catch (e: AirPlayException) {
                s.close()
                AirLog.w(TAG, "${receiver.name}: ${protocol.label} failed: ${e.message}")
                if (i < order.size - 1) {
                    AirLog.i(TAG, "${receiver.name}: falling back to AirPlay 1")
                    continue
                }
                setState(SessionState.FAILED, e.message, protocol)
                return false
            } catch (e: Exception) {
                s.close()
                setState(SessionState.FAILED, e.message ?: e.javaClass.simpleName, protocol)
                return false
            }
        }
        return false
    }

    private fun streamLoop() {
        val s = sender ?: return
        var lastPublish = 0L
        while (running && !retryRequested) {
            val frame = try {
                queue.poll(10, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            try {
                if (frame != null) s.send(frame)
                s.tick()
            } catch (e: Exception) {
                AirLog.w(TAG, "${receiver.name}: send failed", e)
                return
            }
            if (!s.isHealthy) {
                AirLog.w(TAG, "${receiver.name}: control connection lost")
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastPublish > 1000) {
                lastPublish = now
                publish()
            }
        }
        if (retryRequested) retryRequested = false
    }

    private fun closeSender() {
        val s = sender ?: return
        sender = null
        try {
            s.close()
        } catch (e: Exception) {
            AirLog.d(TAG, "${receiver.name}: close failed: ${e.message}")
        }
    }

    private fun setState(newState: SessionState, msg: String?, protocol: AirPlayProtocol? = activeProtocol) {
        state = newState
        message = msg
        activeProtocol = protocol
        publish()
    }

    fun status(): ReceiverStatus {
        val stats = sender?.stats
        return ReceiverStatus(
            receiverId = receiver.id,
            name = receiver.name,
            state = state,
            protocol = activeProtocol,
            volumePercent = options.volumePercent,
            message = message,
            packetsSent = stats?.packetsSent ?: 0,
            retransmits = stats?.retransmitted ?: 0,
            droppedFrames = dropped.get(),
        )
    }

    private fun publish() = onStatus(status())

    companion object {
        private const val TAG = "Session"
        private const val MAX_ATTEMPTS = 6
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
