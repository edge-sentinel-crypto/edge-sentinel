package com.airbridge.airplay.audio

import com.airbridge.airplay.testutil.TestAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineTest {
    @Test
    fun ringBufferWrapsAndCountsOverflow() {
        val ring = PcmRingBuffer(1000)
        assertEquals("rounded up to a power of two", 1024, ring.capacitySamples)
        val data = ShortArray(700) { it.toShort() }
        assertEquals(700, ring.write(data, 0, 700))
        val out = ShortArray(500)
        assertEquals(500, ring.read(out, 0, 500))
        assertArrayEquals(data.copyOfRange(0, 500), out)
        // Writes now wrap around the end of the backing array.
        val more = ShortArray(800) { (1000 + it).toShort() }
        assertEquals("only free space is written", 824, ring.write(more, 0, 800).let { it + ring.write(more, 0, 24) })
        assertEquals(1024, ring.available())
        assertEquals("overflowing samples are dropped and counted", 0, ring.write(ShortArray(10), 0, 10))
        assertEquals(10L, ring.droppedSamples)
        val all = ShortArray(1024)
        assertEquals(1024, ring.read(all, 0, 1024))
        assertArrayEquals(data.copyOfRange(500, 700), all.copyOfRange(0, 200))
        assertArrayEquals(more, all.copyOfRange(200, 1000))
        ring.write(data, 0, 10)
        ring.clear()
        assertEquals(0, ring.available())
    }

    @Test
    fun ringBufferIsSafeForOneProducerAndOneConsumer() {
        val ring = PcmRingBuffer(4096)
        val total = 2_000_000
        var producerSum = 0L
        var consumerSum = 0L
        var consumed = 0
        val producer = Thread {
            var next = 0
            val buf = ShortArray(333)
            while (next < total) {
                val n = minOf(buf.size, total - next)
                for (i in 0 until n) buf[i] = ((next + i) % 30011).toShort()
                var off = 0
                while (off < n) {
                    val w = ring.write(buf, off, n - off).also { if (it == 0) Thread.yield() }
                    for (i in off until off + w) producerSum += buf[i]
                    off += w
                }
                next += n
            }
        }
        // The producer retries when the buffer is full, so nothing may be lost or reordered.
        producer.start()
        val out = ShortArray(512)
        var expected = 0
        var ordered = true
        while (consumed < total) {
            val n = ring.read(out, 0, out.size)
            if (n == 0) Thread.yield()
            for (i in 0 until n) {
                if (out[i] != (expected % 30011).toShort()) ordered = false
                consumerSum += out[i]
                expected++
            }
            consumed += n
        }
        producer.join()
        assertTrue("samples arrived in order", ordered)
        assertEquals(producerSum, consumerSum)
    }

    @Test
    fun chunkerEmitsExact352FrameBlocks() {
        val ring = PcmRingBuffer(8192)
        val chunker = FrameChunker(ring)
        val pcm = TestAudio.sineStereo(1000)
        ring.write(pcm, 0, 500) // less than one chunk (704 samples)
        assertNull(chunker.next())
        ring.write(pcm, 500, pcm.size - 500)
        val c1 = chunker.next()!!
        val c2 = chunker.next()!!
        assertEquals(704, c1.size)
        assertArrayEquals(pcm.copyOfRange(0, 704), c1)
        assertArrayEquals(pcm.copyOfRange(704, 1408), c2)
        assertNull("592 samples left, not a full chunk", chunker.next())
    }

    @Test
    fun levelMeterReportsDbfs() {
        val meter = LevelMeter()
        val full = TestAudio.sineStereo(352, amplitude = 1.0)
        val lvl = meter.process(full)
        assertEquals("full-scale sine RMS is about -3 dBFS", -3.0f, lvl.rmsDb, 0.3f)
        assertEquals(0.0f, lvl.peakDb, 0.1f)
        val quiet = meter.process(TestAudio.sineStereo(352, amplitude = 0.01))
        assertEquals(-43.0f, quiet.rmsDb, 0.4f)
        assertTrue("display peak decays slowly", quiet.displayPeakDb > -1.0f)
        assertEquals(LevelMeter.SILENCE_DB, meter.process(ShortArray(704)).rmsDb)
        assertTrue(LevelMeter.isAllZero(ShortArray(704)))
        assertTrue(!LevelMeter.isAllZero(shortArrayOf(0, 0, 1)))
    }

    @Test
    fun blockedCaptureIsDetectedAfterThreeSeconds() {
        var now = 0L
        val m = CaptureHealthMonitor(blockedAfterMs = 3000, now = { now })
        assertEquals(CaptureHealthMonitor.Status.STARTING, m.onChunk(allZero = true, musicActive = true))
        now = 2900
        assertEquals(CaptureHealthMonitor.Status.STARTING, m.onChunk(true, true))
        now = 3001
        assertEquals("music active + all-zero PCM for >3 s", CaptureHealthMonitor.Status.BLOCKED, m.onChunk(true, true))
        now = 3100
        assertEquals("real audio clears the warning", CaptureHealthMonitor.Status.OK, m.onChunk(false, true))
        now = 4000
        assertEquals(CaptureHealthMonitor.Status.OK, m.onChunk(true, false))
        now = 7100
        assertEquals("silence with nothing playing is not an error", CaptureHealthMonitor.Status.NOTHING_PLAYING, m.onChunk(true, false))
        now = 7200
        assertEquals(CaptureHealthMonitor.Status.BLOCKED, m.onChunk(true, true))
    }

    @Test
    fun driftCorrectorFillsStallsAndTrimsBursts() {
        val d = DriftCorrector(toleranceMs = 200)
        d.start(0)
        val oneSecond = 1_000_000_000L
        // 125.3 chunks per second nominal.
        assertEquals(125L, d.expectedChunks(oneSecond))
        assertEquals(DriftCorrector.Action.None, d.decide(produced = 120, nowNanos = oneSecond))
        val stall = d.decide(produced = 50, nowNanos = oneSecond)
        assertTrue(stall is DriftCorrector.Action.InsertSilence)
        assertEquals(75, (stall as DriftCorrector.Action.InsertSilence).chunks)
        assertEquals(DriftCorrector.Action.Drop, d.decide(produced = 200, nowNanos = oneSecond))
        assertEquals(DriftCorrector.Action.None, DriftCorrector().decide(10, oneSecond))
    }
}
