package com.airbridge.airplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/** Sync-offset math: ms -> 44.1 kHz frames, clamping, 5 ms snapping, latency floor, labels. */
class SyncOffsetTest {
    /** Reference: offsetMs x 44.1 in exact decimal arithmetic, halves rounded away from zero. */
    private fun reference(ms: Int): Int =
        BigDecimal(ms).multiply(BigDecimal("44.1")).setScale(0, RoundingMode.HALF_UP).intValueExact()

    @Test
    fun framesAt44100HzForEveryStepFromMinus250ToPlus250() {
        var previous = Int.MIN_VALUE
        for (ms in SyncOffset.MIN_MS..SyncOffset.MAX_MS step SyncOffset.STEP_MS) {
            val f = SyncOffset.frames(ms)
            assertEquals("frames for $ms ms", reference(ms), f)
            assertEquals("symmetric around 0 for $ms ms", -f, SyncOffset.frames(-ms))
            assertTrue("monotonic at $ms ms", f > previous)
            previous = f
        }
        assertEquals(101, (SyncOffset.MIN_MS..SyncOffset.MAX_MS step SyncOffset.STEP_MS).count())
    }

    @Test
    fun knownFrameValues() {
        assertEquals(0, SyncOffset.frames(0))
        assertEquals(11_025, SyncOffset.frames(250))
        assertEquals(-11_025, SyncOffset.frames(-250))
        assertEquals(221, SyncOffset.frames(5)) // 220.5 rounds away from zero
        assertEquals(-221, SyncOffset.frames(-5))
        assertEquals(441, SyncOffset.frames(10))
        assertEquals(1_764, SyncOffset.frames(40))
        assertEquals(-1_103, SyncOffset.frames(-25)) // -1102.5
        assertEquals(4_410, SyncOffset.frames(100))
        assertEquals(5_292, SyncOffset.frames(120))
        assertEquals(12_000, SyncOffset.frames(250, sampleRate = 48_000))
    }

    @Test
    fun clampingAndSnapping() {
        assertEquals(250, SyncOffset.clamp(300))
        assertEquals(-250, SyncOffset.clamp(-999))
        assertEquals(17, SyncOffset.clamp(17))
        assertEquals("out-of-range offsets are clamped before conversion", 11_025, SyncOffset.frames(400))
        assertEquals(-11_025, SyncOffset.frames(Int.MIN_VALUE))
        assertEquals(11_025, SyncOffset.frames(Int.MAX_VALUE))
        assertEquals(5, SyncOffset.snap(7))
        assertEquals(10, SyncOffset.snap(8))
        assertEquals(-5, SyncOffset.snap(-7))
        assertEquals(-10, SyncOffset.snap(-8))
        assertEquals(0, SyncOffset.snap(2))
        assertEquals(0, SyncOffset.snap(-2))
        assertEquals(-5, SyncOffset.snap(-3))
        assertEquals(250, SyncOffset.snap(252))
        assertEquals(250, SyncOffset.snap(1_000))
        assertEquals(-250, SyncOffset.snap(-1_000))
        for (ms in -260..260) {
            val s = SyncOffset.snap(ms)
            assertEquals("snap($ms) is on the 5 ms grid", 0, s % 5)
            assertTrue(s in -250..250)
            assertEquals("snap is idempotent", s, SyncOffset.snap(s))
        }
    }

    @Test
    fun announcedLatencyAndSafetyFloor() {
        val base = StreamSettings().syncLatencyFrames
        assertEquals(77_175, base)
        assertEquals(77_175, SyncOffset.latencyFrames(base, 0))
        assertEquals("+250 ms = 2.25 s total with the receiver's 11,025", 88_200, SyncOffset.latencyFrames(base, 250))
        assertEquals("-250 ms = 1.75 s total", 66_150, SyncOffset.latencyFrames(base, -250))
        assertEquals(78_939, SyncOffset.latencyFrames(base, 40))
        assertEquals(76_072, SyncOffset.latencyFrames(base, -25))
        assertEquals("never below the floor", SyncOffset.MIN_LATENCY_FRAMES, SyncOffset.latencyFrames(30_000, -250))
        assertEquals(SyncOffset.MIN_LATENCY_FRAMES, SyncOffset.latencyFrames(0, 0))
        assertEquals(22_050, SyncOffset.MIN_LATENCY_FRAMES)
        for (ms in -250..250) {
            val l = SyncOffset.latencyFrames(base, ms)
            assertTrue("default latency stays in 66,150..88,200 ($ms ms -> $l)", l in 66_150..88_200)
        }
    }

    @Test
    fun labelsFollowTheSignConvention() {
        assertEquals("+40 ms (delay)", SyncOffset.label(40))
        assertEquals("-25 ms (earlier)", SyncOffset.label(-25))
        assertEquals("0 ms", SyncOffset.label(0))
        assertEquals("+250 ms (delay)", SyncOffset.label(250))
        assertEquals("+40 ms", SyncOffset.badge(40))
        assertEquals("-25 ms", SyncOffset.badge(-25))
        // Positive = delay = more announced latency; negative = earlier = less latency.
        assertTrue(SyncOffset.latencyFrames(77_175, 40) > 77_175)
        assertTrue(SyncOffset.latencyFrames(77_175, -25) < 77_175)
    }
}
