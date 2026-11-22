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
 *  AudioRecord (AudioPlaybackCapture: USAGE_MEDIA/GAME/UNKNOWN, 44.1 kHz, stereo, PCM16)
 *    -> capture thread -> lock-free ring buffer (about 3 s; 1.5 s requested, rounded up to a power of two)
 *    -> pump thread -> 352-frame chunks -> drift correction -> level meter + blocked-capture
 *       detector -> AirPlaySessionManager.push() (ALAC encoded once, fanned out to speakers)
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

    /** Creates the AudioRecord and starts both threads. Throws if capture cannot start. */
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is required for audio playback capture")
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuffer * 4, SAMPLE_RATE * CHANNELS * 2 / 4) // >= 250 ms
        val r = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("AudioRecord could not be initialized for playback capture")
        }
        record = r
        running = true
        r.startRecording()
        drift.start(clock.nowNanos())
        captureThread = Thread({ captureLoop(r) }, "airbridge-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        pumpThread = Thread({ pumpLoop() }, "airbridge-pump").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        AirLog.i(TAG, "capture started (buffer $bufferBytes bytes, min $minBuffer)")
    }

    private fun captureLoop(r: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(FRAMES_PER_CHUNK * CHANNELS * 4)
        while (running) {
            val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n > 0) {
                ring.write(buf, 0, n)
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

    fun stop() {
        running = false
        pumpThread?.let { LockSupport.unpark(it) }
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        captureThread?.join(1_000)
        pumpThread?.join(1_000)
        record?.release()
        record = null
        AirLog.i(TAG, "capture stopped (dropped ${ring.droppedSamples} samples)")
    }

    companion object {
        private const val TAG = "Capture"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val FRAMES_PER_CHUNK = 352
    }
}
