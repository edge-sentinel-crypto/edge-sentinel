package com.airbridge.airplay.airplay

import com.airbridge.airplay.airplay.ap2.Ap2Config
import com.airbridge.airplay.airplay.ap2.Ap2Sender
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/**
 * The AirPlay 2 stream SETUP latency window (`latencyMin`, `latencyMax`) against every latency the
 * sync packets can announce. A receiver may compare the announced latency (rtpNow - rtpLessLatency)
 * or the total it applies (announced + 11,025 frames) with the window, so both must be inside it.
 */
class Ap2LatencyBoundsTest {
    private val base = SyncOffset.BASE_LATENCY_FRAMES

    @Test
    fun defaultWindowIs11025To99225AndMatchesTheBaselineMath() {
        assertEquals(77_175, base)
        assertEquals(77_175, StreamSettings().syncLatencyFrames)
        assertEquals("1.75 s announced + 0.25 s added by the receiver = 2.0 s", 88_200, SyncOffset.totalLatencyFrames(base, 0))
        assertEquals("-250 ms announced", 66_150, SyncOffset.latencyFrames(base, -250))
        assertEquals("+250 ms announced", 88_200, SyncOffset.latencyFrames(base, 250))
        assertEquals("-250 ms total = 1.75 s", 77_175, SyncOffset.totalLatencyFrames(base, -250))
        assertEquals("+250 ms total = 2.25 s", 99_225, SyncOffset.totalLatencyFrames(base, 250))
        val bounds = SyncOffset.ap2LatencyBounds()
        assertEquals(LatencyBounds(min = 11_025, max = 99_225), bounds)
        assertEquals(bounds, SyncOffset.ap2LatencyBounds(StreamSettings().syncLatencyFrames))
    }

    @Test
    fun everyOffsetFromMinus250To250IsInsideTheWindowBothWays() {
        val bounds = SyncOffset.ap2LatencyBounds(base)
        for (ms in -400..400) { // every millisecond, including values the clamp brings back to ±250
            val announced = SyncOffset.latencyFrames(base, ms)
            val total = SyncOffset.totalLatencyFrames(base, ms)
            assertTrue("$ms ms: announced $announced inside ${bounds.min}..${bounds.max}", announced in bounds.min..bounds.max)
            assertTrue("$ms ms: total $total inside ${bounds.min}..${bounds.max}", total in bounds.min..bounds.max)
            assertTrue("$ms ms admitted", bounds.admits(announced))
            assertEquals("no clamping needed at $ms ms", announced, bounds.clamp(announced))
        }
        // The extremes touch the window exactly: nothing is wasted and nothing is outside.
        assertEquals(bounds.max, SyncOffset.totalLatencyFrames(base, SyncOffset.MAX_MS))
        assertTrue(SyncOffset.latencyFrames(base, SyncOffset.MIN_MS) >= bounds.min)
    }

    @Test
    fun theV020WindowDidNotCoverPositiveOffsets() {
        // v0.2.0 declared latencyMin 11,025 / latencyMax 88,200 while +250 ms asked for 99,225 in total.
        val old = LatencyBounds(min = 11_025, max = 88_200)
        assertTrue("0 ms fits (88,200 total)", old.admits(SyncOffset.latencyFrames(base, 0)))
        assertTrue("-250 ms fits", old.admits(SyncOffset.latencyFrames(base, -250)))
        assertFalse("+5 ms already exceeds the old maximum", old.admits(SyncOffset.latencyFrames(base, 5)))
        assertFalse("+250 ms (99,225 total) exceeds the old maximum", old.admits(SyncOffset.latencyFrames(base, 250)))
        assertEquals("the old window would have clamped +250 ms back to 0 ms", 77_175, old.clamp(SyncOffset.latencyFrames(base, 250)))
    }

    @Test
    fun windowFollowsAnyBaseLatency() {
        for (b in listOf(22_050, 30_000, 44_100, 66_150, 77_175, 88_200, 99_225, 132_300)) {
            val bounds = SyncOffset.ap2LatencyBounds(b)
            assertTrue("never narrower than the owntone/pyatv window", bounds.min <= 11_025 && bounds.max >= 88_200)
            for (ms in SyncOffset.MIN_MS..SyncOffset.MAX_MS step SyncOffset.STEP_MS) {
                assertTrue("base $b, $ms ms", bounds.admits(SyncOffset.latencyFrames(b, ms)))
            }
        }
        // A 2.0 s announced base (88,200): +250 ms needs a total of 110,250 and -250 ms announces 77,175.
        val b88 = SyncOffset.ap2LatencyBounds(88_200)
        assertEquals(110_250, b88.max)
        assertTrue(b88.min <= 77_175)
    }

    @Test
    fun windowsThatCannotHoldTheReceiverShareAreRejected() {
        try {
            LatencyBounds(min = 80_000, max = 88_200)
            fail("a window narrower than 11,025 frames cannot admit any sync packet")
        } catch (_: IllegalArgumentException) {
        }
        val w = LatencyBounds(min = 11_025, max = 99_225)
        assertEquals(11_025, w.clamp(5_000))
        assertEquals(88_200, w.clamp(95_000))
    }

    @Test
    fun ap2SenderDeclaresTheDerivedWindow() {
        val sender = Ap2Sender(
            Ap2Config(name = "Window", host = InetAddress.getLoopbackAddress(), port = 7000, receiverKey = "w"),
            SenderIdentity.random(),
            InMemoryPairingStore(),
        )
        assertEquals(LatencyBounds(11_025, 99_225), sender.latencyBounds)
        val custom = Ap2Sender(
            Ap2Config(
                name = "Custom", host = InetAddress.getLoopbackAddress(), port = 7000, receiverKey = "c",
                settings = StreamSettings(syncLatencyFrames = 88_200),
            ),
            SenderIdentity.random(),
            InMemoryPairingStore(),
        )
        assertEquals(110_250, custom.latencyBounds.max)
    }
}
