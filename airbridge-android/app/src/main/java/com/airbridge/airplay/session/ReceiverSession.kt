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
import com.airbridge.airplay.airplay.SyncOffset
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
    /** Sync offset in ms applied to this speaker (negative = earlier, positive = later). */
    val syncOffsetMs: Int = 0,
    /**
     * Set when Auto mode tried this protocol first and it failed, so the speaker now plays over
     * [protocol] instead: AIRPLAY_2 when an AirPlay 2 speaker fell back to AirPlay 1.
     */
    val fallbackFrom: AirPlayProtocol? = null,
    /** Why the first protocol failed (for diagnostics), when [fallbackFrom] is set. */
    val fallbackReason: String? = null,
) {
    /** True while the speaker plays over AirPlay 1 because AirPlay 2 failed (Auto mode). */
    val isFallback: Boolean get() = fallbackFrom != null && protocol != null && protocol != fallbackFrom

    /** Protocol label for the UI: "AirPlay 2", "AirPlay 1", or "AirPlay 1 (fallback)". */
    val protocolLabel: String? get() = protocol?.let { if (isFallback) "${it.label} (fallback)" else it.label }
}

/**
 * One speaker inside a stream: its own worker thread and bounded frame queue, so a slow or dead
 * speaker can never stall the capture pipeline or the other speakers. Handles connect, protocol
 * fallback (AirPlay 2 -> AirPlay 1 when both are advertised, shown as "AirPlay 1 (fallback)"),
 * reconnect with backoff, a new address announced by the speaker, PIN and password prompts,
 * volume, sync offset and teardown.
 */
class ReceiverSession(
    receiver: AirPlayReceiver,
    options: ConnectOptions,
    private val factory: SenderFactory,
    private val timeline: StreamTimeline,
    private val nextFrameIndex: () -> Long,
    private val onStatus: (ReceiverStatus) -> Unit,
    queueCapacity: Int = 96,
) {
    private val optionsLock = Any()

    /**
     * The speaker as last announced (same id; its address, ports or TXT may change). Every
     * connection attempt reads it afresh, so a reconnect goes to the speaker's current address.
     */
    @Volatile var receiver: AirPlayReceiver = receiver
        private set

    /** Current options; changed only through [updateOptions] so concurrent changes are not lost. */
    @Volatile var options: ConnectOptions = options
        private set

    private val queue = ArrayBlockingQueue<AudioFrame>(queueCapacity)
    private val dropped = AtomicLong()
    @Volatile private var running = true
    @Volatile private var sender: AirPlaySender? = null
    @Volatile var state: SessionState = SessionState.CONNECTING
        private set
    @Volatile private var message: String? = null
    @Volatile private var activeProtocol: AirPlayProtocol? = null
    @Volatile private var fallbackFrom: AirPlayProtocol? = null
    @Volatile private var fallbackReason: String? = null
    private val thread = Thread({ run() }, "airplay-session-${receiver.name}").apply { isDaemon = true }
    private val retryLock = ReentrantLock()
    private val retrySignal = retryLock.newCondition()

    /** Reconnect now: set by [retryWith]; also ends a running stream (new PIN or password). */
    @Volatile private var retryRequested = false

    /** Retry the connection now, but never interrupt a stream that is playing (new address). */
    @Volatile private var wakeRequested = false

    fun start() = thread.start()

    /** Called by the fan-out for every captured frame. Never blocks. */
    fun offer(frame: AudioFrame) {
        if (state != SessionState.STREAMING) return
        while (!queue.offer(frame)) {
            queue.poll()
            dropped.incrementAndGet()
        }
    }

    private fun updateOptions(change: (ConnectOptions) -> ConnectOptions): ConnectOptions =
        synchronized(optionsLock) { change(options).also { options = it } }

    fun setVolume(percent: Int) {
        updateOptions { it.copy(volumePercent = percent) }
        sender?.let { s -> if (state == SessionState.STREAMING) s.setVolume(AirPlayVolume.percentToDb(percent)) }
        publish()
    }

    /**
     * Live sync-offset change: the connected sender keeps its RTSP/HAP session and sends a sync
     * packet with the shifted latency on its next tick. While connecting or reconnecting, the new
     * value is applied as soon as the connection is up.
     */
    fun setSyncOffsetMs(offsetMs: Int) {
        val ms = SyncOffset.clamp(offsetMs)
        updateOptions { it.copy(syncOffsetMs = ms) }
        sender?.setSyncOffsetMs(ms)
        publish()
    }

    /** Supplies a PIN or password and reconnects immediately. */
    fun retryWith(pin: String? = options.pin, password: String? = options.password) {
        updateOptions { it.copy(pin = pin, password = password) }
        retryLock.withLock {
            retryRequested = true
            retrySignal.signalAll()
        }
    }

    /**
     * The speaker announced itself again, possibly from a new IP address or port (same id). The
     * session keeps its options (volume, sync offset, PIN/password) and the speaker keeps its
     * pairing, which is stored under the id. A stream that is playing keeps its connection; the
     * next connection attempt uses the new address. A session that is waiting to retry (after a
     * failed or lost connection) retries at once at the new address. Returns true when the
     * address or a port changed.
     */
    fun updateReceiver(updated: AirPlayReceiver): Boolean {
        require(updated.id == receiver.id) { "receiver id changed from ${receiver.id} to ${updated.id}" }
        val old = receiver
        receiver = updated
        val moved = old.host != updated.host || old.raopPort != updated.raopPort || old.airplayPort != updated.airplayPort
        if (moved) {
            AirLog.i(TAG, "${updated.name}: now at ${updated.host} (was ${old.host})")
            val s = state
            if (s == SessionState.RETRYING || s == SessionState.FAILED) {
                retryLock.withLock {
                    wakeRequested = true
                    retrySignal.signalAll()
                }
            }
        }
        if (moved || old.name != updated.name) publish()
        return moved
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
            if (!retryRequested && !wakeRequested && running) {
                try {
                    if (ms == Long.MAX_VALUE) retrySignal.await() else retrySignal.await(ms, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                }
            }
            retryRequested = false
            wakeRequested = false
        }
    }

    private fun connectOnce(): Boolean {
        closeSender()
        queue.clear()
        val target = receiver // one consistent address for this attempt
        fallbackFrom = null
        fallbackReason = null
        val primary = target.protocolFor(options.preference)
        val canFallBack = options.preference == ProtocolPreference.AUTO &&
            primary == AirPlayProtocol.AIRPLAY_2 && target.supportsAirPlay1
        val order = if (canFallBack) listOf(primary, AirPlayProtocol.AIRPLAY_1) else listOf(primary)
        var firstFailure: String? = null
        for ((i, protocol) in order.withIndex()) {
            setState(SessionState.CONNECTING, "Connecting (${protocol.label})", protocol)
            val used = options
            val s = try {
                factory.create(target, protocol, used)
            } catch (e: Exception) {
                setState(SessionState.FAILED, "Cannot reach ${target.host}: ${e.message}")
                return false
            }
            try {
                s.setSyncOffsetMs(used.syncOffsetMs)
                s.connect(timeline, nextFrameIndex())
                sender = s
                activeProtocol = protocol
                // Auto mode reached the speaker only through its second protocol: say so on its card.
                fallbackFrom = if (i > 0) order[0] else null
                fallbackReason = if (i > 0) firstFailure else null
                setState(SessionState.STREAMING, null, protocol)
                // Volume or sync-offset changes made while connecting were not part of this
                // connection's setup: apply the latest values now.
                val now = options
                if (now.volumePercent != used.volumePercent) s.setVolume(AirPlayVolume.percentToDb(now.volumePercent))
                if (now.syncOffsetMs != s.syncOffsetMs) s.setSyncOffsetMs(now.syncOffsetMs)
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
                    updateOptions { it.copy(pin = null) }
                    val again = connectOnce()
                    if (state == SessionState.NEEDS_PIN) setState(SessionState.NEEDS_PIN, "Wrong code. Enter the new code shown by ${receiver.name}", protocol)
                    return again
                }
                if (options.password != null) {
                    updateOptions { it.copy(password = null) }
                    setState(SessionState.NEEDS_PASSWORD, "Wrong password for ${receiver.name}", protocol)
                    return false
                }
                setState(SessionState.FAILED, e.message, protocol)
                return false
            } catch (e: AirPlayException) {
                s.close()
                AirLog.w(TAG, "${receiver.name}: ${protocol.label} failed: ${e.message}")
                if (i < order.size - 1) {
                    AirLog.i(TAG, "${receiver.name}: falling back to ${order[i + 1].label}")
                    if (firstFailure == null) firstFailure = "${protocol.label} failed: ${e.message ?: e.javaClass.simpleName}"
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
            syncOffsetMs = options.syncOffsetMs,
            fallbackFrom = fallbackFrom,
            fallbackReason = fallbackReason,
        )
    }

    private fun publish() = onStatus(status())

    companion object {
        private const val TAG = "Session"
        private const val MAX_ATTEMPTS = 6
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
