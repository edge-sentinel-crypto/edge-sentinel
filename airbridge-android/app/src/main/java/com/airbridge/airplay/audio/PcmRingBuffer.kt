package com.airbridge.airplay.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded single-producer / single-consumer ring buffer of 16-bit samples.
 *
 * Lock-free: the capture thread only advances [writeIndex], the pump thread only advances
 * [readIndex], and each publishes its index with a release store (lazySet) after copying, so the
 * other side never sees half-written samples. When the consumer falls behind, new samples are
 * dropped (and counted) instead of growing memory or latency.
 */
class PcmRingBuffer(capacitySamples: Int) {
    private val capacity: Int = Integer.highestOneBit((capacitySamples - 1).coerceAtLeast(1)) shl 1
    private val mask = capacity - 1
    private val buffer = ShortArray(capacity)
    private val writeIndex = AtomicLong(0)
    private val readIndex = AtomicLong(0)
    private val dropped = AtomicLong(0)

    val capacitySamples: Int get() = capacity

    /** Samples that could not be stored because the buffer was full. */
    val droppedSamples: Long get() = dropped.get()

    fun available(): Int = (writeIndex.get() - readIndex.get()).toInt()

    fun free(): Int = capacity - available()

    /** Producer side. Returns the number of samples stored. */
    fun write(src: ShortArray, offset: Int, length: Int): Int {
        val w = writeIndex.get()
        val free = capacity - (w - readIndex.get()).toInt()
        val n = minOf(length, free)
        if (n < length) dropped.addAndGet((length - n).toLong())
        if (n <= 0) return 0
        val start = (w and mask.toLong()).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(src, offset, buffer, start, first)
        if (n > first) System.arraycopy(src, offset + first, buffer, 0, n - first)
        writeIndex.lazySet(w + n)
        return n
    }

    /** Consumer side. Returns the number of samples copied into [dst]. */
    fun read(dst: ShortArray, offset: Int, length: Int): Int {
        val r = readIndex.get()
        val n = minOf(length, (writeIndex.get() - r).toInt())
        if (n <= 0) return 0
        val start = (r and mask.toLong()).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(buffer, start, dst, offset, first)
        if (n > first) System.arraycopy(buffer, 0, dst, offset + first, n - first)
        readIndex.lazySet(r + n)
        return n
    }

    /** Consumer side: discards everything currently buffered. */
    fun clear() {
        readIndex.lazySet(writeIndex.get())
    }
}

/** Cuts the PCM stream into exact 352-frame AirPlay packets (704 interleaved stereo samples). */
class FrameChunker(private val ring: PcmRingBuffer, framesPerChunk: Int = 352, private val channels: Int = 2) {
    val samplesPerChunk = framesPerChunk * channels

    fun hasChunk(): Boolean = ring.available() >= samplesPerChunk

    /** Returns the next full chunk, or null if fewer than 352 frames are buffered. */
    fun next(): ShortArray? {
        if (!hasChunk()) return null
        val chunk = ShortArray(samplesPerChunk)
        val n = ring.read(chunk, 0, samplesPerChunk)
        check(n == samplesPerChunk) { "single consumer violated" }
        return chunk
    }
}
