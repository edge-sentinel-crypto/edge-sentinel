package com.airbridge.airplay.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming sample-rate converter for interleaved 16-bit PCM. Pure Kotlin (no Android imports),
 * so it is unit tested on the JVM. AirBridge uses it when a phone captures playback only at
 * 48 kHz: [CaptureEngine] then converts 48,000 Hz to the 44,100 Hz that AirPlay carries.
 *
 * Method: exact rational resampling. 44,100 / 48,000 = 147 / 160, so output frame n is the input
 * signal at time n x 160 / 147 input frames, interpolated with a 160-tap Kaiser-windowed sinc
 * (beta 10, cutoff 21 kHz) taken from a table of 147 phases. The bookkeeping is in integers, so
 * the ratio is exact: after T input frames exactly [outputFramesFor] (T) frames have come out,
 * whatever the chunk sizes, and the output never drifts against the input clock. The kernel is
 * centred, so the output is not delayed; frame n can be computed once the input reaches 80 frames
 * past its position (1.7 ms of look-ahead at 48 kHz).
 *
 * Each phase is normalized to a DC gain of exactly 1 and then scaled by [GAIN] (0.9999, that is
 * -0.0009 dB), so full-scale sines never clip. Values beyond 16 bits (for example the overshoot of
 * a full-scale square wave) saturate at -32768 / 32767 and are counted in [clippedSamples]; they
 * never wrap. Each channel is filtered on its own. Silence in gives exact digital silence out, so
 * the blocked-capture detector still sees all-zero chunks. Not thread-safe: one capture thread
 * calls [process].
 */
class PcmResampler(
    val inputRate: Int,
    val outputRate: Int,
    val channels: Int = 2,
) {
    /** Output frames per cycle (147 for 48 kHz -> 44.1 kHz). */
    val up: Int

    /** Input frames per cycle (160 for 48 kHz -> 44.1 kHz). */
    val down: Int

    /** Phase-major kernel table: weight of tap m for phase p is `table[p * TAPS + m]`. */
    private val table: DoubleArray

    /** Interleaved input history; frame 0 of [buf] is absolute input frame [bufStart]. */
    private var buf: ShortArray
    private var bufFrames: Int
    private var bufStart: Long

    /** Input frames received so far. */
    var inputFrames = 0L
        private set

    /** Output frames produced so far. */
    var outputFrames = 0L
        private set

    /** Output samples that had to be saturated to the 16-bit range (0 for any in-range signal). */
    var clippedSamples = 0L
        private set

    init {
        require(inputRate > 0 && outputRate > 0 && inputRate != outputRate) { "unsupported rates $inputRate -> $outputRate" }
        require(channels >= 1) { "channels must be at least 1" }
        val g = gcd(inputRate, outputRate)
        up = outputRate / g
        down = inputRate / g
        table = buildTable(up, cutoffHz = CUTOFF_FRACTION * minOf(inputRate, outputRate), inputRate = inputRate)
        // Silence before the first input frame: HALF zero frames of history.
        buf = ShortArray((HALF + 4096) * channels)
        bufFrames = HALF
        bufStart = -HALF.toLong()
    }

    /** Output frames that exactly [inputFrames] input frames produce (0 until the look-ahead is filled). */
    fun outputFramesFor(inputFrames: Long): Long {
        val usable = inputFrames - HALF
        return if (usable <= 0) 0 else (usable * up + down - 1) / down
    }

    /** Largest number of output samples one [process] call can write for [inputSamples] input samples. */
    fun maxOutputSamples(inputSamples: Int): Int {
        val frames = inputSamples / channels
        return ((frames.toLong() * up / down + 2) * channels).toInt()
    }

    /**
     * Feeds [samples] interleaved samples from [input] (a whole number of frames) and writes every
     * output frame that is now complete to [out] from [outOffset]. Returns the number of samples
     * written; [out] needs room for [maxOutputSamples] ([samples]).
     */
    fun process(input: ShortArray, offset: Int, samples: Int, out: ShortArray, outOffset: Int = 0): Int {
        require(samples >= 0 && samples % channels == 0) { "samples must be whole frames" }
        val frames = samples / channels
        append(input, offset, frames)
        inputFrames += frames
        val lastInput = inputFrames - 1
        var o = outOffset
        while (true) {
            val position = outputFrames * down
            val q = position / up
            if (q + HALF > lastInput) break
            val phase = (position - q * up).toInt()
            val first = (q + 1 - HALF - bufStart).toInt()
            val base = phase * TAPS
            if (channels == 2) {
                var left = 0.0
                var right = 0.0
                var j = first * 2
                for (m in 0 until TAPS) {
                    val w = table[base + m]
                    left += w * buf[j]
                    right += w * buf[j + 1]
                    j += 2
                }
                out[o++] = saturate(left)
                out[o++] = saturate(right)
            } else {
                for (c in 0 until channels) {
                    var acc = 0.0
                    var j = first * channels + c
                    for (m in 0 until TAPS) {
                        acc += table[base + m] * buf[j]
                        j += channels
                    }
                    out[o++] = saturate(acc)
                }
            }
            outputFrames++
        }
        discardUnneededHistory()
        return o - outOffset
    }

    /** Convenience form: returns the output samples for the whole of [input]. */
    fun process(input: ShortArray): ShortArray {
        val out = ShortArray(maxOutputSamples(input.size))
        val n = process(input, 0, input.size, out, 0)
        return out.copyOf(n)
    }

    /** Forgets all history and counters (as if newly created). */
    fun reset() {
        buf.fill(0)
        bufFrames = HALF
        bufStart = -HALF.toLong()
        inputFrames = 0
        outputFrames = 0
        clippedSamples = 0
    }

    private fun append(input: ShortArray, offset: Int, frames: Int) {
        val needed = (bufFrames + frames) * channels
        if (needed > buf.size) buf = buf.copyOf(maxOf(needed, buf.size * 2))
        System.arraycopy(input, offset, buf, bufFrames * channels, frames * channels)
        bufFrames += frames
    }

    /** Keeps only the input frames that the next output frame (and later ones) can still use. */
    private fun discardUnneededHistory() {
        val nextQ = (outputFrames * down) / up
        val drop = (nextQ + 1 - HALF - bufStart).toInt().coerceAtMost(bufFrames)
        if (drop <= 0) return
        System.arraycopy(buf, drop * channels, buf, 0, (bufFrames - drop) * channels)
        bufFrames -= drop
        bufStart += drop
    }

    private fun saturate(v: Double): Short {
        val r = Math.round(v)
        return when {
            r > Short.MAX_VALUE -> {
                clippedSamples++
                Short.MAX_VALUE
            }
            r < Short.MIN_VALUE -> {
                clippedSamples++
                Short.MIN_VALUE
            }
            else -> r.toShort()
        }
    }

    companion object {
        /** Kernel length in input frames (taps per phase). */
        const val TAPS = 160
        private const val HALF = TAPS / 2

        /** Kaiser window shape: about 100 dB stopband with 160 taps. */
        const val KAISER_BETA = 10.0

        /** Cutoff as a fraction of the lower rate: 21,000 Hz for 44.1 kHz output. */
        const val CUTOFF_FRACTION = 21_000.0 / 44_100.0

        /** Overall gain: 1 - 1e-4 (-0.0009 dB) of headroom so full-scale sines do not clip. */
        const val GAIN = 0.9999

        /** Look-ahead in input frames before an output frame can be computed. */
        const val LOOKAHEAD_FRAMES = HALF

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /**
         * Windowed-sinc kernel sampled at every phase: for phase p the output instant lies p/up of an
         * input frame after input frame q, and tap m weights input frame q + 1 - HALF + m, whose
         * distance from that instant is x = p/up + HALF - 1 - m input frames.
         */
        private fun buildTable(up: Int, cutoffHz: Double, inputRate: Int): DoubleArray {
            val t = DoubleArray(up * TAPS)
            val fc = cutoffHz / inputRate // cycles per input frame
            val i0Beta = besselI0(KAISER_BETA)
            for (p in 0 until up) {
                var sum = 0.0
                for (m in 0 until TAPS) {
                    val x = p.toDouble() / up + HALF - 1 - m
                    val r = x / HALF
                    val window = if (r * r >= 1.0) besselI0(0.0) / i0Beta else besselI0(KAISER_BETA * sqrt(1.0 - r * r)) / i0Beta
                    val arg = 2.0 * fc * x
                    val sinc = if (arg == 0.0) 1.0 else sin(PI * arg) / (PI * arg)
                    val v = 2.0 * fc * sinc * window
                    t[p * TAPS + m] = v
                    sum += v
                }
                val scale = GAIN / sum
                for (m in 0 until TAPS) t[p * TAPS + m] *= scale
            }
            return t
        }

        /** Modified Bessel function of the first kind, order 0 (power series). */
        internal fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (true) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < sum * 1e-17) break
                k++
            }
            return sum
        }
    }
}
