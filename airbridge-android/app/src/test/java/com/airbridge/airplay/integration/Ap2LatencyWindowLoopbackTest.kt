package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.LatencyBounds
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.StreamSettings
import com.airbridge.airplay.airplay.SyncOffset
import com.airbridge.airplay.airplay.ap2.Ap2Config
import com.airbridge.airplay.airplay.ap2.Ap2Sender
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.ConnectOptions
import com.airbridge.airplay.session.DefaultSenderFactory
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.testutil.MockAp2Receiver
import com.airbridge.airplay.testutil.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * AirPlay 2 latency negotiation over real sockets against a strict mock receiver: the stream SETUP
 * declares latencyMin/latencyMax, and every RTP sync packet, the first one and the periodic
 * mid-stream ones, must announce a latency inside that window, both as announced and as the total
 * with the receiver's 11,025 frames, at -250, 0 and +250 ms and through a live sweep.
 */
class Ap2LatencyWindowLoopbackTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private fun speaker(id: String, mock: MockAp2Receiver) = AirPlayReceiver(
        id = id, name = "Speaker $id", host = "127.0.0.1", airplayPort = mock.port,
        airplayTxt = mapOf("features" to "0x445F8A00,0x1C340", "flags" to "0x4", "model" to "AudioAccessory5,1"),
    )

    @Test
    fun firstAndMidStreamSyncPacketsStayInsideTheSetupWindowAtMinus250Zero250() {
        val offsets = listOf(-250, 0, 250)
        val mocks = offsets.map { MockAp2Receiver(strictLatencyWindow = true).start() }
        try {
            val manager = AirPlaySessionManager(DefaultSenderFactory(SenderIdentity.random(), InMemoryPairingStore()))
            manager.start()
            offsets.forEachIndexed { i, ms -> manager.connect(speaker("s$i", mocks[i]), ConnectOptions(syncOffsetMs = ms)) }
            assertTrue(waitUntil(15_000) { manager.statuses.value.values.count { it.state == SessionState.STREAMING } == 3 })

            // About 2.5 s of real-time audio: each speaker's worker sends its periodic sync packet every second.
            val chunks = TestAudio.sineChunks(310)
            val t0 = System.nanoTime()
            chunks.forEachIndexed { k, pcm ->
                manager.push(pcm)
                val due = t0 + (k + 1) * 352L * 1_000_000_000L / 44_100
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            for (i in offsets.indices) {
                val mock = mocks[i]
                assertTrue("speaker $i: at least 3 sync packets", waitUntil(3_000) { mock.syncChecks.size >= 3 })
            }
            manager.stop()

            offsets.forEachIndexed { i, ms ->
                val mock = mocks[i]
                assertEquals("SETUP window", 11_025L..99_225L, mock.latencyWindow)
                val checks = mock.syncChecks.toList()
                val announced = SyncOffset.latencyFrames(SyncOffset.BASE_LATENCY_FRAMES, ms).toLong()
                assertTrue("$ms ms: first sync packet checked", checks.first().first)
                assertTrue("$ms ms: mid-stream sync packets checked", checks.count { !it.first } >= 2)
                for (c in checks) {
                    assertTrue("$ms ms: $c", c.inside)
                    assertEquals("$ms ms: announced latency", announced, c.announced)
                    assertEquals("$ms ms: total latency", announced + 11_025, c.total)
                }
                assertTrue("$ms ms mock errors: ${mock.errors}", mock.errors.isEmpty())
                assertTrue(mock.sink!!.frames.size >= 300)
            }
            assertEquals(listOf(66_150L, 77_175L, 88_200L), mocks.map { it.syncChecks.first().announced })
            assertEquals("+250 ms reaches the top of the window exactly", 99_225L, mocks[2].syncChecks.first().total)
        } finally {
            mocks.forEach { it.close() }
        }
    }

    @Test
    fun liveOffsetSweepNeverLeavesTheSetupWindow() {
        MockAp2Receiver(strictLatencyWindow = true).start().use { mock ->
            val sender = Ap2Sender(
                Ap2Config(
                    name = "Sweep", host = loopback, port = mock.port, receiverKey = "sweep",
                    feedbackIntervalMs = 200, settings = StreamSettings(syncOffsetMs = 250),
                ),
                SenderIdentity.random(),
                InMemoryPairingStore(),
            )
            val timeline = LoopbackSupport.timeline()
            sender.connect(timeline, 0)
            assertEquals(LatencyBounds(11_025, 99_225), sender.latencyBounds)
            val sink = mock.sink!!
            assertTrue(waitUntil(3_000) { mock.syncChecks.size == 1 })

            // Every 5 ms step from +250 down to -250 and back, plus out-of-range requests (clamped).
            val steps = (250 downTo -250 step 5) + (-250..250 step 5) + listOf(999, -999, 0)
            var frame = 0L
            for (ms in steps) {
                val before = sender.stats.syncPacketsSent
                sender.setSyncOffsetMs(ms)
                LoopbackSupport.streamSine(sender, timeline, 1, startIndex = frame) // send() + tick(): the forced sync packet
                frame++
                assertEquals("$ms ms: one forced sync packet", before + 1, sender.stats.syncPacketsSent)
                Thread.sleep(2) // keep the burst within the mock's UDP receive buffers
            }
            val expected = 1 + steps.size
            assertTrue(waitUntil(3_000) { mock.syncChecks.size >= expected })
            val checks = mock.syncChecks.toList()
            assertTrue("every packet inside the window: ${checks.filterNot { it.inside }}", checks.all { it.inside })
            assertEquals("the extremes are used", 66_150L, checks.minOf { it.announced })
            assertEquals(99_225L, checks.maxOf { it.total })
            assertEquals("no clamping by the sender was ever needed", 0L, sender.stats.latencyClamps)
            assertTrue(sink.waitForFrames(steps.size))
            sender.close()
            assertTrue(waitUntil { mock.teardown })
            assertTrue("mock errors: ${mock.errors}", mock.errors.isEmpty())
        }
    }
}
