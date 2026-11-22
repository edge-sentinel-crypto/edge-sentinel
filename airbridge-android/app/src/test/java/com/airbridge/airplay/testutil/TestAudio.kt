package com.airbridge.airplay.testutil

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Deterministic test signals. */
object TestAudio {
    /**
     * Interleaved stereo 16-bit sine: left [freqLeft] Hz, right [freqRight] Hz, starting at frame
     * [startFrame] so consecutive chunks join seamlessly.
     */
    fun sineStereo(frames: Int, startFrame: Long = 0, freqLeft: Double = 440.0, freqRight: Double = 660.0, amplitude: Double = 0.6): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = (startFrame + i) / 44_100.0
            out[i * 2] = (sin(2 * PI * freqLeft * t) * amplitude * 32767).roundToInt().toShort()
            out[i * 2 + 1] = (sin(2 * PI * freqRight * t) * amplitude * 32767).roundToInt().toShort()
        }
        return out
    }

    /** [count] consecutive 352-frame chunks of the stereo sine. */
    fun sineChunks(count: Int, framesPerChunk: Int = 352): List<ShortArray> =
        (0 until count).map { sineStereo(framesPerChunk, it.toLong() * framesPerChunk) }

    fun randomPcm(samples: Int, seed: Long): ShortArray {
        val r = java.util.Random(seed)
        return ShortArray(samples) { (r.nextInt(65536) - 32768).toShort() }
    }

    /** Includes the extreme values that catch sign/byte-order bugs. */
    fun edgeCasePcm(frames: Int): ShortArray {
        val pattern = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0, -1, 1, 0x7F00, 0x00FF, -256, 0x0100, 0x8001.toShort())
        return ShortArray(frames * 2) { pattern[it % pattern.size] }
    }

    fun concat(chunks: List<ShortArray>): ShortArray {
        val out = ShortArray(chunks.sumOf { it.size })
        var p = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, p, c.size)
            p += c.size
        }
        return out
    }
}
