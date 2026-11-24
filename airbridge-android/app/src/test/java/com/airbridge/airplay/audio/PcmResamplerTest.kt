package com.airbridge.airplay.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The 48 kHz -> 44.1 kHz stereo converter used when a phone captures playback only at 48 kHz.
 * Output frame n must equal the input signal at time n / 44,100 s: every check compares the
 * converter's output with the ideal 44.1 kHz signal computed from the same formula.
 */
class PcmResamplerTest {
    private val inRate = 48_000
    private val outRate = 44_100
    private val gain = PcmResampler.GAIN

    /** Stereo input: frame i = (left(i / 48000 s), right(i / 48000 s)), rounded to 16 bits. */
    private fun stereo(frames: Int, left: (Double) -> Double, right: (Double) -> Double): ShortArray {
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / inRate
            pcm[2 * i] = left(t).roundToInt().coerceIn(-32768, 32767).toShort()
            pcm[2 * i + 1] = right(t).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    private fun sine(amplitude: Double, hz: Double, phase: Double = 0.0): (Double) -> Double = { t -> amplitude * sin(2 * PI * hz * t + phase) }

    /** Signal-to-error ratio (dB) of channel [ch] of [out] against [ideal] at 44.1 kHz, output frames from..until. */
    private fun snrDb(out: ShortArray, ch: Int, ideal: (Double) -> Double, from: Int, until: Int): Double {
        var signal = 0.0
        var error = 0.0
        for (n in from until until) {
            val want = gain * ideal(n.toDouble() / outRate)
            val e = out[2 * n + ch] - want
            signal += want * want
            error += e * e
        }
        return 10 * log10(signal / maxOf(error, 1e-12))
    }

    private fun rms(out: ShortArray, ch: Int, from: Int, until: Int): Double {
        var s = 0.0
        for (n in from until until) s += out[2 * n + ch].toDouble() * out[2 * n + ch]
        return sqrt(s / (until - from))
    }

    @Test
    fun ratioIsExactly147To160AndTheLookAheadIs80Frames() {
        val r = PcmResampler(inRate, outRate)
        assertEquals(147, r.up)
        assertEquals(160, r.down)
        assertEquals(80, PcmResampler.LOOKAHEAD_FRAMES)
        assertEquals(0, r.outputFramesFor(80))
        assertEquals(1, r.outputFramesFor(81))
        assertEquals("one second in, one second out (less the 80-frame look-ahead)", 44_027, r.outputFramesFor(48_000))
        assertEquals(2_646_000L - 73, r.outputFramesFor(48_000L * 60))
    }

    @Test
    fun sineWavesComeOutAtTheSameFrequencyAndLevel() {
        for (hz in listOf(50.0, 440.0, 1_000.0, 5_000.0, 10_000.0, 15_000.0, 19_000.0)) {
            val amp = 29_200.0 // -1 dBFS
            val f = sine(amp, hz, phase = 0.3)
            val out = PcmResampler(inRate, outRate).process(stereo(inRate, f, f))
            val frames = out.size / 2
            assertEquals(44_027, frames)
            val snr = snrDb(out, 0, f, from = 300, until = frames)
            assertTrue("$hz Hz: SNR $snr dB", snr >= 90.0)
            var idealSquares = 0.0
            for (n in 300 until frames) idealSquares += f(n.toDouble() / outRate).let { it * it }
            val ratio = rms(out, 0, 300, frames) / sqrt(idealSquares / (frames - 300))
            val levelDb = 20 * log10(ratio)
            println("sine %.0f Hz at -1 dBFS: SNR %.1f dB, level %.5f dB".format(hz, snr, levelDb))
            assertTrue("$hz Hz: level ${"%.5f".format(levelDb)} dB", abs(levelDb) < 0.01)
        }
    }

    @Test
    fun stereoChannelsStaySeparate() {
        // Left 1 kHz, right digital silence: the right output is exact silence.
        val left = sine(16_000.0, 1_000.0)
        val silent: (Double) -> Double = { 0.0 }
        val a = PcmResampler(inRate, outRate).process(stereo(24_000, left, silent))
        for (n in 0 until a.size / 2) assertEquals("right sample $n", 0, a[2 * n + 1].toInt())
        // -6 dBFS tones: 16-bit rounding of input and output alone limits the SNR to about 89 dB.
        val snrA = snrDb(a, 0, left, 300, a.size / 2)
        println("stereo: left 1 kHz -6 dBFS SNR %.1f dB, right exact silence".format(snrA))
        assertTrue("left $snrA dB", snrA >= 85.0)

        // Different tones per channel: each channel matches only its own tone.
        val right = sine(16_000.0, 7_000.0, phase = 1.1)
        val b = PcmResampler(inRate, outRate).process(stereo(24_000, left, right))
        val snrL = snrDb(b, 0, left, 300, b.size / 2)
        val snrR = snrDb(b, 1, right, 300, b.size / 2)
        println("stereo: left 1 kHz SNR %.1f dB, right 7 kHz SNR %.1f dB".format(snrL, snrR))
        assertTrue("left $snrL dB", snrL >= 85.0)
        assertTrue("right $snrR dB", snrR >= 85.0)

        // Swapping the input channels swaps the output channels exactly.
        val c = PcmResampler(inRate, outRate).process(stereo(24_000, right, left))
        for (n in 0 until b.size / 2) {
            assertEquals(b[2 * n], c[2 * n + 1])
            assertEquals(b[2 * n + 1], c[2 * n])
        }
    }

    @Test
    fun chunkBoundariesDoNotChangeTheOutput() {
        val input = stereo(96_000, sine(20_000.0, 997.0), sine(12_000.0, 6_123.0, 0.7))
        val whole = PcmResampler(inRate, outRate).process(input)
        val random = Random(20261124)
        for (pattern in 0 until 4) {
            val r = PcmResampler(inRate, outRate)
            val out = ShortArray(whole.size + 64)
            var written = 0
            var frame = 0
            while (frame < 96_000) {
                val n = when (pattern) {
                    0 -> 1 // single frames
                    1 -> 352 // AirPlay packet size
                    2 -> 1 + random.nextInt(4_000)
                    else -> listOf(1, 2, 3, 147, 160, 1_408, 4_096)[random.nextInt(7)]
                }.coerceAtMost(96_000 - frame)
                val chunkOut = ShortArray(r.maxOutputSamples(n * 2))
                val m = r.process(input, frame * 2, n * 2, chunkOut, 0)
                System.arraycopy(chunkOut, 0, out, written, m)
                written += m
                frame += n
                assertEquals("no drift at any point", r.outputFramesFor(r.inputFrames), r.outputFrames)
            }
            assertEquals("pattern $pattern: same length", whole.size, written)
            assertArrayEquals("pattern $pattern: identical samples", whole, out.copyOf(written))
        }
    }

    @Test
    fun fullScaleSinesDoNotClipAndOverloadsSaturateWithoutWrapping() {
        for (hz in listOf(20.0, 100.0, 1_000.0, 5_000.0, 10_000.0, 15_000.0, 19_000.0, 20_000.0)) {
            // Full scale (32767) after a 10 ms raised-cosine fade-in, as music starts.
            val f: (Double) -> Double = { t ->
                val fade = min(1.0, t / 0.010)
                (0.5 - 0.5 * cos(PI * fade)) * 32_767.0 * sin(2 * PI * hz * t + 0.3)
            }
            val r = PcmResampler(inRate, outRate)
            val out = r.process(stereo(12_000, f, f))
            assertEquals("$hz Hz: no clipped samples", 0L, r.clippedSamples)
            val peak = out.maxOf { abs(it.toInt()) }
            println("full-scale %.0f Hz: peak %d, clipped 0".format(hz, peak))
            assertTrue("$hz Hz: really full scale (peak $peak)", peak >= 32_700)
        }

        // A full-scale 1 kHz square wave overshoots (Gibbs): those samples saturate, never wrap.
        val square: (Double) -> Double = { t -> if (sin(2 * PI * 1_000.0 * t) >= 0) 32_767.0 else -32_767.0 }
        val r = PcmResampler(inRate, outRate)
        val out = r.process(stereo(24_000, square, square))
        assertTrue("overshoot was saturated", r.clippedSamples > 0)
        assertEquals(32_767, out.maxOf { it.toInt() })
        assertEquals(-32_768, out.minOf { it.toInt() })
        for (n in 100 until out.size / 2) {
            // Away from the edges (more than 0.1 ms) the output has the square wave's sign.
            val phase = (n.toDouble() / outRate * 1_000.0) % 1.0
            if (abs(phase - 0.5) < 0.1 || phase < 0.1 || phase > 0.9) continue
            val expectPositive = phase < 0.5
            assertEquals("frame $n sign", expectPositive, out[2 * n] > 0)
        }
    }

    @Test
    fun sixtySecondsInRandomChunksStayExactAndPhaseLocked() {
        // 60 s of a 1 kHz tone in random chunk sizes: the frame count is exact and the last second
        // is still phase-locked to the ideal 44.1 kHz tone (1 ppm of drift would cost 60 µs, a
        // 0.38 rad phase error at 1 kHz, and push the error far above -90 dB).
        val seconds = 60
        val tone = sine(20_000.0, 1_000.0, 0.2)
        val r = PcmResampler(inRate, outRate)
        val random = Random(7)
        val chunk = ShortArray(8_192 * 2)
        val tail = ShortArray(outRate * 2 * 2)
        var produced = 0L
        var frame = 0L
        val total = inRate.toLong() * seconds
        val out = ShortArray(r.maxOutputSamples(chunk.size))
        while (frame < total) {
            val n = (1 + random.nextInt(8_192)).toLong().coerceAtMost(total - frame).toInt()
            for (i in 0 until n) {
                val v = tone((frame + i).toDouble() / inRate).roundToInt().toShort()
                chunk[2 * i] = v
                chunk[2 * i + 1] = v
            }
            val m = r.process(chunk, 0, n * 2, out, 0)
            // Keep the most recent output samples (the last ~2 s) for the phase check.
            for (k in 0 until m / 2) {
                val idx = ((produced + k) % (tail.size / 2)).toInt()
                tail[2 * idx] = out[2 * k]
                tail[2 * idx + 1] = out[2 * k + 1]
            }
            produced += m / 2
            frame += n
        }
        assertEquals(total, r.inputFrames)
        assertEquals("exactly ceil((T - 80) x 147 / 160) frames", r.outputFramesFor(total), produced)
        assertEquals(2_646_000L - 73, produced)
        var signal = 0.0
        var error = 0.0
        for (n in produced - outRate until produced) {
            val want = gain * tone(n.toDouble() / outRate)
            val got = tail[2 * ((n % (tail.size / 2)).toInt())]
            signal += want * want
            error += (got - want) * (got - want)
        }
        val snr = 10 * log10(signal / error)
        println("60 s in random chunks: $produced frames out, last second SNR %.1f dB".format(snr))
        assertTrue("last second still phase-locked: SNR $snr dB", snr >= 90.0)
        assertEquals(0L, r.clippedSamples)
    }

    @Test
    fun silenceStaysDigitalSilenceAndDcPassesUnchanged() {
        // A tone burst, then 0.5 s of zeros: once the 80-frame look-ahead has passed, the output is
        // exact zeros again (the blocked-capture detector depends on all-zero chunks).
        val r = PcmResampler(inRate, outRate)
        r.process(stereo(4_800, sine(30_000.0, 3_000.0), sine(30_000.0, 500.0)))
        val after = r.process(ShortArray(24_000 * 2))
        val nonZeroTail = (2 * 200 until after.size).count { after[it].toInt() != 0 } // from output frame 200
        assertEquals("exact silence after the tone has left the filter", 0, nonZeroTail)
        val zeros = PcmResampler(inRate, outRate).process(ShortArray(48_000 * 2))
        assertTrue(zeros.all { it.toInt() == 0 })

        // DC: every phase has unity gain (times 0.9999), so a constant comes out constant.
        val dc = PcmResampler(inRate, outRate).process(ShortArray(48_000 * 2) { if (it % 2 == 0) 10_000 else -20_000 })
        for (n in 100 until dc.size / 2) {
            assertEquals(9_999, dc[2 * n].toInt())
            assertEquals(-19_998, dc[2 * n + 1].toInt())
        }
    }
}
