package com.airbridge.airplay.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.util.AirLog
import com.airbridge.airplay.util.NtpClock
import java.util.concurrent.locks.LockSupport

/**
 * System-audio capture pipeline:
 *
 *  AudioRecord (AudioPlaybackCapture: USAGE_MEDIA/GAME/UNKNOWN, stereo, PCM16; 44.1 kHz, or
 *  48 kHz when the phone refuses 44.1 kHz)
 *    -> capture thread (48 kHz only: PcmResampler to 44.1 kHz)
 *    -> lock-free ring buffer (about 3 s; 1.5 s requested, rounded up to a power of two)
 *    -> pump thread -> 352-frame chunks -> drift correction -> level meter + blocked-capture
 *       detector -> AirPlaySessionManager.push() (ALAC encoded once, fanned out to speakers)
 *
 * AirBridge only records other apps' playback. It never plays audio itself and never requests
 * audio focus, so the app that is playing (a music player, for example) keeps playing.
 */
class CaptureEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val sessions: AirPlaySessionManager,
    private val clock: NtpClock,
    private val listener: Listener,
) {
    interface Listener {
        fun onLevel(level: LevelMeter.Level)
        fun onHealth(status: CaptureHealthMonitor.Status)
        fun onCaptureError(message: String)

        /** The capture sample rate in use: 44,100, or 48,000 (converted to 44,100). */
        fun onCaptureFormat(sampleRate: Int) {}
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ring = PcmRingBuffer(SAMPLE_RATE * CHANNELS * 3 / 2)
    private val chunker = FrameChunker(ring)
    private val meter = LevelMeter()
    private val health = CaptureHealthMonitor()
    private val drift = DriftCorrector()
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var pumpThread: Thread? = null
    @Volatile private var running = false

    /** Sample rate of the running capture (0 before [start]). */
    @Volatile var captureRate: Int = 0
        private set

    /**
     * Opens playback capture (44.1 kHz first, 48 kHz as the fallback, see [CaptureFallback]) and
     * starts both threads. Throws if capture cannot start at either rate.
     */
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val opened = CaptureFallback.openFirst { rate -> openRecord(config, rate) }
        opened.refused.forEach { AirLog.w(TAG, "capture format refused: $it") }
        val r = opened.handle
        val resampler = if (opened.needsResampling) PcmResampler(opened.sampleRate, SAMPLE_RATE, CHANNELS) else null
        record = r
        captureRate = opened.sampleRate
        running = true
        drift.start(clock.nowNanos())
        captureThread = Thread({ captureLoop(r, resampler) }, "airbridge-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        pumpThread = Thread({ pumpLoop() }, "airbridge-pump").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        listener.onCaptureFormat(opened.sampleRate)
        AirLog.i(
            TAG,
            "capture started at ${opened.sampleRate} Hz" + (if (resampler != null) ", converted to $SAMPLE_RATE Hz" else ""),
        )
    }

    /**
     * Builds and starts a playback-capture AudioRecord at [sampleRate] (stereo, 16-bit). On any
     * failure the AudioRecord is released before the exception leaves, so a refused rate leaks
     * nothing and the next rate can be tried.
     */
    private fun openRecord(config: AudioPlaybackCaptureConfiguration, sampleRate: Int): AudioRecord {
        // A missing permission is not a format problem: CaptureFallback rethrows it at once.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is required for audio playback capture")
        }
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IllegalStateException("no buffer size for this format (error $minBuffer)")
        val bufferBytes = maxOf(minBuffer * 4, sampleRate * CHANNELS * 2 / 4) // >= 250 ms
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val r = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        var started = false
        try {
            if (r.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord did not initialize")
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("AudioRecord did not start recording")
            started = true
            AirLog.i(TAG, "AudioRecord open at $sampleRate Hz (buffer $bufferBytes bytes, min $minBuffer)")
            return r
        } finally {
            if (!started) {
                try {
                    r.stop()
                } catch (_: IllegalStateException) {
                }
                r.release()
            }
        }
    }

    private fun captureLoop(r: AudioRecord, resampler: PcmResampler?) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(FRAMES_PER_CHUNK * CHANNELS * 4)
        val converted = resampler?.let { ShortArray(it.maxOutputSamples(buf.size)) }
        while (running) {
            val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n > 0) {
                if (resampler == null) {
                    ring.write(buf, 0, n)
                } else {
                    val m = resampler.process(buf, 0, n - n % CHANNELS, converted!!, 0)
                    if (m > 0) ring.write(converted, 0, m)
                }
                pumpThread?.let { LockSupport.unpark(it) }
            } else if (n < 0 && running) {
                listener.onCaptureError("Audio capture stopped (error $n)")
                break
            }
        }
    }

    private fun pumpLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        var produced = 0L
        var lastLevelNanos = 0L
        var lastMusicCheck = 0L
        var musicActive = false
        var lastHealth: CaptureHealthMonitor.Status? = null
        val silence = ShortArray(FRAMES_PER_CHUNK * CHANNELS)
        while (running) {
            if (!chunker.hasChunk()) LockSupport.parkNanos(10_000_000L)
            val now = clock.nowNanos()
            // Capture stalled (or audio clock slower): keep receivers fed with silence.
            val stall = drift.decide(produced, now)
            if (stall is DriftCorrector.Action.InsertSilence && !chunker.hasChunk()) {
                repeat(stall.chunks) { sessions.push(silence.copyOf()); produced++ }
            }
            while (running) {
                val chunk = chunker.next() ?: break
                if (drift.decide(produced, clock.nowNanos()) is DriftCorrector.Action.Drop) continue
                sessions.push(chunk)
                produced++
                val t = clock.nowNanos()
                if (t - lastMusicCheck > 250_000_000L) {
                    musicActive = audioManager.isMusicActive
                    lastMusicCheck = t
                }
                val status = health.onChunk(LevelMeter.isAllZero(chunk), musicActive)
                if (status != lastHealth) {
                    lastHealth = status
                    listener.onHealth(status)
                }
                val level = meter.process(chunk)
                if (t - lastLevelNanos > 66_000_000L) { // ~15 updates per second
                    lastLevelNanos = t
                    listener.onLevel(level)
                }
            }
        }
    }

    /** Stops both threads and always releases the AudioRecord (idempotent). */
    fun stop() {
        running = false
        pumpThread?.let { LockSupport.unpark(it) }
        val r = record
        try {
            try {
                r?.stop()
            } catch (_: IllegalStateException) {
            }
            captureThread?.join(1_000)
            pumpThread?.join(1_000)
        } finally {
            r?.release()
            record = null
            AirLog.i(TAG, "capture stopped (dropped ${ring.droppedSamples} samples)")
        }
    }

    companion object {
        private const val TAG = "Capture"
        const val SAMPLE_RATE = CaptureFallback.AIRPLAY_RATE
        const val CHANNELS = 2
        const val FRAMES_PER_CHUNK = 352
    }
}
