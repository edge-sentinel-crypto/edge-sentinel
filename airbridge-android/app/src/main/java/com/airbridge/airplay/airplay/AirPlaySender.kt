package com.airbridge.airplay.airplay

import com.airbridge.airplay.codec.AlacEncoder
import com.airbridge.airplay.codec.PcmL16
import com.airbridge.airplay.util.NtpClock
import java.io.Closeable

/** AirPlay generation used for one receiver. */
enum class AirPlayProtocol(val label: String) {
    AIRPLAY_1("AirPlay 1"),
    AIRPLAY_2("AirPlay 2"),
}

/** Audio payload format on the wire. */
enum class AudioCodec { ALAC, L16 }

/**
 * One 352-frame block of 44.1 kHz 16-bit stereo audio, encoded once and shared by every receiver.
 * [rtpTimestamp] comes from the shared [StreamTimeline] so all receivers play in step.
 */
class AudioFrame(
    val index: Long,
    val rtpTimestamp: Long,
    val pcm: ShortArray,
) {
    val alac: ByteArray by lazy { AlacEncoder.encode(pcm, 0, pcm.size / 2) }
    val l16: ByteArray by lazy { PcmL16.encode(pcm) }

    fun payload(codec: AudioCodec): ByteArray = if (codec == AudioCodec.ALAC) alac else l16

    /** Forces the shared ALAC encoding on the calling (capture) thread. */
    fun prepare(): ByteArray = alac

    companion object {
        const val FRAMES = AlacEncoder.FRAMES_PER_PACKET
        const val SAMPLE_RATE = AlacEncoder.SAMPLE_RATE
    }
}

/**
 * Shared RTP/NTP timeline. Frame k carries RTP time rtpBase + 352k and was captured at
 * anchor + 352k/44100 s; receivers are told (through sync packets) to play it [latencyFrames]
 * later. Every receiver in a group uses the same instance and the same [clock].
 */
class StreamTimeline(
    val clock: NtpClock,
    val rtpBase: Long,
    val anchorNanos: Long = clock.nowNanos(),
    val sampleRate: Int = AudioFrame.SAMPLE_RATE,
) {
    fun timestampForFrame(index: Long): Long = (rtpBase + index * AudioFrame.FRAMES) and 0xFFFFFFFFL

    /** RTP time corresponding to clock time [nanos] (wrapping at 32 bits). Overflow-safe for years. */
    fun rtpAt(nanos: Long): Long {
        val elapsed = nanos - anchorNanos
        val seconds = Math.floorDiv(elapsed, 1_000_000_000L)
        val rem = Math.floorMod(elapsed, 1_000_000_000L)
        val frames = seconds * sampleRate + rem * sampleRate / 1_000_000_000L
        return (rtpBase + frames) and 0xFFFFFFFFL
    }

    /** Clock time at which frame [index] was (nominally) captured. */
    fun nanosForFrame(index: Long): Long {
        val samples = index * AudioFrame.FRAMES
        return anchorNanos + (samples / sampleRate) * 1_000_000_000L + (samples % sampleRate) * 1_000_000_000L / sampleRate
    }
}

/** Settings for one sender. */
data class StreamSettings(
    val codec: AudioCodec = AudioCodec.ALAC,
    /** Latency announced in sync packets (frames); with the 0x07 flag receivers add 11025, total 2.0 s. */
    val syncLatencyFrames: Int = 77_175,
    /**
     * Initial per-speaker sync offset in ms (-250..+250; negative = earlier, positive = later).
     * It shifts the announced latency by round(ms x 44.1) frames; see [SyncOffset].
     */
    val syncOffsetMs: Int = SyncOffset.DEFAULT_MS,
    /** AirPlay 2 SETUP latency bounds (frames). */
    val ap2LatencyMin: Int = 11_025,
    val ap2LatencyMax: Int = 88_200,
    val initialVolumeDb: Double = -15.0,
)

class SenderStats {
    @Volatile var packetsSent = 0L
    @Volatile var bytesSent = 0L
    @Volatile var retransmitted = 0L
    @Volatile var timingRepliesSent = 0L
    @Volatile var syncPacketsSent = 0L
    @Volatile var feedbackSent = 0L
    @Volatile var controlFailures = 0
}

/** Common contract of the AirPlay 1 (RAOP) and AirPlay 2 (HAP) senders. */
interface AirPlaySender : Closeable {
    val protocol: AirPlayProtocol
    val displayName: String
    val stats: SenderStats

    /**
     * Performs the full handshake (pairing/auth, SETUP, RECORD, volume). Blocking; throws
     * [AirPlayException] subclasses on failure. [firstFrameIndex] is the next frame that will be sent.
     */
    fun connect(timeline: StreamTimeline, firstFrameIndex: Long)

    /** Sends one audio frame (called from this receiver's own worker thread). */
    fun send(frame: AudioFrame)

    /** Periodic housekeeping: sync packets, keep-alives. Called often from the worker thread. */
    fun tick()

    fun setVolume(db: Double)

    /** Current per-speaker sync offset in ms (negative = plays earlier, positive = plays later). */
    val syncOffsetMs: Int

    /**
     * Changes the sync offset without reconnecting. On a connected sender the worker thread's next
     * [tick] sends a sync packet at once with the shifted latency, so the receiver re-aligns while
     * the RTSP/HAP session and the audio stream keep running. Before [connect] it only sets the
     * value used for the first sync packet. Thread-safe; never blocks.
     */
    fun setSyncOffsetMs(offsetMs: Int)

    /** Flushes the receiver buffer; the next frame sent starts a new run (marker bit set). */
    fun flush(nextFrameIndex: Long)

    /** False once the control connection has failed repeatedly (the session then reconnects). */
    val isHealthy: Boolean get() = stats.controlFailures < 3

    /** Sends TEARDOWN (best effort) and releases all sockets. Idempotent. */
    override fun close()
}

/** Errors with enough detail for the UI to tell the user what to do. */
open class AirPlayException(message: String, cause: Throwable? = null) : Exception(message, cause)

class PasswordRequiredException(message: String = "This speaker requires a password") : AirPlayException(message)

/** An AirPlay 2 receiver is showing (or requires) a 4-digit PIN. */
class PinRequiredException(message: String = "Enter the 4-digit code shown by the receiver") : AirPlayException(message)

class AuthenticationFailedAirPlayException(message: String) : AirPlayException(message)

class ReceiverBusyException(message: String = "The receiver is busy with another sender") : AirPlayException(message)

class ProtocolFailureException(message: String, cause: Throwable? = null) : AirPlayException(message, cause)

/** Volume mapping between the UI percentage and AirPlay's dB scale (-30..0, -144 = mute). */
object AirPlayVolume {
    const val MUTE_DB = -144.0
    const val MIN_DB = -30.0
    const val MAX_DB = 0.0

    fun percentToDb(percent: Int): Double {
        val p = percent.coerceIn(0, 100)
        if (p == 0) return MUTE_DB
        return MIN_DB + (MAX_DB - MIN_DB) * p / 100.0
    }

    fun dbToPercent(db: Double): Int {
        if (db <= MIN_DB) return 0
        if (db >= MAX_DB) return 100
        return Math.round((db - MIN_DB) / (MAX_DB - MIN_DB) * 100.0).toInt()
    }

    fun format(db: Double): String = String.format(java.util.Locale.US, "%.6f", db)
}
