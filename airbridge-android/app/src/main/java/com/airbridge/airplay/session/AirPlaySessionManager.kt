package com.airbridge.airplay.session

import com.airbridge.airplay.airplay.AudioFrame
import com.airbridge.airplay.airplay.StreamTimeline
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.util.AirLog
import com.airbridge.airplay.util.NtpClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Fans captured audio out to 1..N speakers. All speakers share one [StreamTimeline] (same RTP
 * clock and NTP anchor) so they play in step; each speaker has its own [ReceiverSession] (thread,
 * queue, protocol, volume). Speakers can be added and removed while audio is flowing.
 */
class AirPlaySessionManager(
    private val factory: SenderFactory,
    private val clock: NtpClock = NtpClock(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val sessions = ConcurrentHashMap<String, ReceiverSession>()
    private val frameCounter = AtomicLong(0)
    @Volatile private var timeline: StreamTimeline? = null

    private val _statuses = MutableStateFlow<Map<String, ReceiverStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ReceiverStatus>> = _statuses.asStateFlow()

    val isStreaming: Boolean get() = timeline != null

    val activeCount: Int get() = sessions.values.count { it.state == SessionState.STREAMING }

    /** Starts a new timeline; speakers added afterwards connect immediately. */
    @Synchronized
    fun start(): StreamTimeline {
        timeline?.let { return it }
        val t = StreamTimeline(clock, random.nextInt().toLong() and 0xFFFFFFFFL)
        frameCounter.set(0)
        timeline = t
        AirLog.i(TAG, "stream timeline started (rtp base ${t.rtpBase})")
        return t
    }

    /** Stops every session (TEARDOWN) and ends the timeline. */
    fun stop() {
        val all: List<ReceiverSession>
        synchronized(this) {
            all = sessions.values.toList()
            sessions.clear()
            timeline = null
        }
        all.forEach { it.stop() }
        all.forEach { it.join(3_000) }
        _statuses.value = emptyMap()
        AirLog.i(TAG, "stream stopped")
    }

    /** Adds (or re-adds) a speaker. Requires [start] first. */
    @Synchronized
    fun connect(receiver: AirPlayReceiver, options: ConnectOptions) {
        val t = timeline ?: throw IllegalStateException("stream not started")
        sessions.remove(receiver.id)?.stop()
        val session = ReceiverSession(receiver, options, factory, t, { frameCounter.get() }, ::onStatus)
        sessions[receiver.id] = session
        onStatus(session.status())
        session.start()
    }

    fun disconnect(receiverId: String) {
        val s = sessions.remove(receiverId) ?: return
        s.stop()
        _statuses.value = _statuses.value - receiverId
    }

    fun setVolume(receiverId: String, percent: Int) {
        sessions[receiverId]?.setVolume(percent)
    }

    fun providePin(receiverId: String, pin: String) {
        sessions[receiverId]?.retryWith(pin = pin)
    }

    fun providePassword(receiverId: String, password: String) {
        sessions[receiverId]?.retryWith(password = password)
    }

    fun retry(receiverId: String) {
        sessions[receiverId]?.retryWith()
    }

    fun isConnected(receiverId: String): Boolean = sessions.containsKey(receiverId)

    /** Index the next pushed frame will get. */
    fun nextFrameIndex(): Long = frameCounter.get()

    /**
     * Fan-out entry point: wraps 352 stereo frames into an [AudioFrame] stamped on the shared
     * timeline and hands it to every streaming speaker. Returns the frame (for metering/tests).
     */
    fun push(pcm: ShortArray): AudioFrame? {
        val t = timeline ?: return null
        val index = frameCounter.getAndIncrement()
        val frame = AudioFrame(index, t.timestampForFrame(index), pcm)
        val targets = sessions.values
        if (targets.isNotEmpty()) {
            frame.prepare() // encode once here so every speaker shares the same bytes
            for (s in targets) s.offer(frame)
        }
        return frame
    }

    private fun onStatus(status: ReceiverStatus) {
        synchronized(_statuses) {
            // Ignore late updates from sessions that were already removed.
            if (sessions.containsKey(status.receiverId)) {
                _statuses.value = _statuses.value + (status.receiverId to status)
            }
        }
    }

    companion object {
        private const val TAG = "Sessions"
    }
}
