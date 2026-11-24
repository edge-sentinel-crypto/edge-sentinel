package com.airbridge.airplay.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Live level meter: RMS and peak of each chunk in dBFS with a smooth falling display value. */
class LevelMeter(private val decayPerChunkDb: Float = 0.6f) {
    data class Level(val rmsDb: Float, val peakDb: Float, val displayPeakDb: Float) {
        /** 0..1 for UI bars (-60 dBFS .. 0 dBFS). */
        val rmsFraction: Float get() = ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)
        val peakFraction: Float get() = ((displayPeakDb + 60f) / 60f).coerceIn(0f, 1f)
    }

    private var display = SILENCE_DB

    fun process(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Level {
        var sum = 0.0
        var peak = 0
        for (i in offset until offset + length) {
            val v = pcm[i].toInt()
            sum += v.toDouble() * v
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        val rms = if (length > 0) sqrt(sum / length) else 0.0
        val rmsDb = toDb(rms)
        val peakDb = toDb(peak.toDouble())
        display = max(peakDb, display - decayPerChunkDb)
        return Level(rmsDb, peakDb, display)
    }

    companion object {
        const val SILENCE_DB = -96f

        fun toDb(amplitude: Double): Float =
            if (amplitude <= 0.0) SILENCE_DB else max(SILENCE_DB, (20.0 * log10(amplitude / 32768.0)).toFloat())

        fun isAllZero(pcm: ShortArray): Boolean {
            for (s in pcm) if (s.toInt() != 0) return false
            return true
        }
    }
}

/**
 * Detects capture that is silently blocked. Android's AudioPlaybackCapture returns digital zeros
 * for an app that opts out of capture (an app, or one version of it, can set
 * `android:allowAudioPlaybackCapture="false"` or a capture policy), while
 * AudioManager.isMusicActive() still reports playback. If that persists for [blockedAfterMs]
 * the user is told, instead of streaming silence without explanation. Apps that allow capture,
 * which is Android's default for current apps, are captured normally.
 */
class CaptureHealthMonitor(
    private val blockedAfterMs: Long = 3_000,
    private val idleAfterMs: Long = 3_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Status {
        /** Capture just started, not enough data yet. */
        STARTING,
        /** Non-zero audio is arriving. */
        OK,
        /** Silence and no app is playing music. */
        NOTHING_PLAYING,
        /** Music is playing but the captured audio has been exact zeros for too long. */
        BLOCKED,
    }

    private var zeroSince = -1L
    private var started = -1L
    var status: Status = Status.STARTING
        private set

    fun onChunk(allZero: Boolean, musicActive: Boolean): Status {
        val t = now()
        if (started < 0) started = t
        if (!allZero) {
            zeroSince = -1
            status = Status.OK
            return status
        }
        if (zeroSince < 0) zeroSince = t
        val silentFor = t - zeroSince
        status = when {
            musicActive && silentFor >= blockedAfterMs -> Status.BLOCKED
            !musicActive && silentFor >= idleAfterMs -> Status.NOTHING_PLAYING
            status == Status.OK || status == Status.STARTING -> if (t - started < idleAfterMs) Status.STARTING else status
            else -> status
        }
        return status
    }

    fun reset() {
        zeroSince = -1
        started = -1
        status = Status.STARTING
    }
}

/**
 * Keeps the number of frames sent aligned with real time. The phone's audio clock and its system
 * clock drift by tens of ppm, and capture can stall (e.g. when the playing app pauses its output).
 * Receivers schedule playback from our sync packets (system clock), so a frame deficit is filled
 * with silence and a surplus is trimmed before it can overrun the receivers' 2 s buffer.
 */
class DriftCorrector(
    private val sampleRate: Int = 44_100,
    private val framesPerChunk: Int = 352,
    private val toleranceMs: Long = 200,
) {
    sealed class Action {
        object None : Action()
        /** Send this many chunks of silence first (capture fell behind). */
        data class InsertSilence(val chunks: Int) : Action()
        /** Drop the chunk at hand (capture ran ahead). */
        object Drop : Action()
    }

    private var startNanos = Long.MIN_VALUE

    fun start(nowNanos: Long) {
        startNanos = nowNanos
    }

    val started: Boolean get() = startNanos != Long.MIN_VALUE

    /** Chunks that should have been produced by [nowNanos]. */
    fun expectedChunks(nowNanos: Long): Long {
        val elapsed = nowNanos - startNanos
        if (elapsed <= 0) return 0
        val frames = (elapsed / 1_000_000L) * sampleRate / 1_000L
        return frames / framesPerChunk
    }

    /** Decision before emitting chunk number [produced] (0-based count of chunks already sent). */
    fun decide(produced: Long, nowNanos: Long): Action {
        if (!started) return Action.None
        val tolerance = toleranceMs * sampleRate / 1000 / framesPerChunk
        val expected = expectedChunks(nowNanos)
        return when {
            produced > expected + tolerance -> Action.Drop
            produced < expected - tolerance -> Action.InsertSilence((expected - produced).toInt().coerceAtMost(200))
            else -> Action.None
        }
    }
}
