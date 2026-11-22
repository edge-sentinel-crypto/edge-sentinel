package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AirPlaySender
import com.airbridge.airplay.airplay.AudioFrame
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.StreamSettings
import com.airbridge.airplay.airplay.StreamTimeline
import com.airbridge.airplay.airplay.ap2.Ap2Config
import com.airbridge.airplay.airplay.ap2.Ap2Sender
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import com.airbridge.airplay.airplay.raop.RaopConfig
import com.airbridge.airplay.airplay.raop.RaopSender
import com.airbridge.airplay.integration.LoopbackSupport.RTP_BASE
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.ConnectOptions
import com.airbridge.airplay.session.DefaultSenderFactory
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.testutil.MockAp2Receiver
import com.airbridge.airplay.testutil.MockAudioSink
import com.airbridge.airplay.testutil.MockRaopReceiver
import com.airbridge.airplay.testutil.TestAudio
import com.airbridge.airplay.util.ByteUtil
import com.airbridge.airplay.util.NtpClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Per-speaker sync offset over real sockets: the RTP sync packets that AirPlay 1 (RAOP) and
 * AirPlay 2 senders put on the control channel carry the shifted latency, both in the first sync
 * packet after connecting and right after a live `setSyncOffsetMs` while audio keeps flowing on
 * the same session (no new SETUP, RECORD, pairing or TEARDOWN).
 */
class SyncOffsetLoopbackTest {
    private val identity = SenderIdentity.random()
    private val loopback = InetAddress.getLoopbackAddress()

    /** Latency a sync packet announces: rtpNow (bytes 16..19) minus rtpLessLatency (bytes 4..7). */
    private fun latencyOf(p: ByteArray): Long = (ByteUtil.getU32BE(p, 16) - ByteUtil.getU32BE(p, 4)) and 0xFFFFFFFFL

    /**
     * Checks one sync packet against the shared timeline: rtpNow is the timeline's RTP time at the
     * packet's NTP time, and rtpLessLatency (the frame due now) is rtpNow shifted by [latency].
     */
    private fun assertSyncPacket(p: ByteArray, timeline: StreamTimeline, latency: Int, first: Boolean) {
        assertEquals("sync packet size", 20, p.size)
        assertEquals(if (first) 0x90 else 0x80, p[0].toInt() and 0xFF)
        assertEquals(0xD4, p[1].toInt() and 0xFF)
        assertEquals(latency.toLong(), latencyOf(p))
        val rtpNow = ByteUtil.getU32BE(p, 16)
        val expectedNow = timeline.rtpAt(NtpClock.ntp64ToNanos(ByteUtil.getU64BE(p, 8)))
        val drift = ((rtpNow - expectedNow + 0x80000000L) and 0xFFFFFFFFL) - 0x80000000L
        assertTrue("rtpNow matches the timeline at the packet's NTP time (off by $drift frames)", drift in -1L..1L)
        assertEquals("shifted RTP timestamp of the frame due now", (rtpNow - latency) and 0xFFFFFFFFL, ByteUtil.getU32BE(p, 4))
    }

    /** Streams [count] frames from [start] and returns their PCM. */
    private fun stream(sender: AirPlaySender, timeline: StreamTimeline, start: Long, count: Int): List<ShortArray> =
        LoopbackSupport.streamSine(sender, timeline, count, startIndex = start)

    /** All [sent] frames arrived bit-exact, in order, with consecutive timestamps and one marker. */
    private fun assertContinuousAudio(sink: MockAudioSink, sent: List<ShortArray>) {
        assertTrue("timed out waiting for ${sent.size} frames (got ${sink.frames.size})", sink.waitForFrames(sent.size))
        assertTrue("sink errors: ${sink.errors}", sink.errors.isEmpty())
        assertArrayEquals(TestAudio.concat(sent), sink.pcmInOrder())
        sink.frames.values.toList().forEachIndexed { i, f ->
            assertEquals("timestamp of frame $i", (RTP_BASE + i * AudioFrame.FRAMES.toLong()) and 0xFFFFFFFFL, f.timestamp)
            assertEquals("marker bit only on the first packet (no new run)", i == 0, f.marker)
        }
    }

    /**
     * The live part shared by both protocols: change the offset, run one tick and expect exactly one
     * forced sync packet with the new latency, although less than a second passed since the last one.
     */
    private fun liveChange(sender: AirPlaySender, sink: MockAudioSink, timeline: StreamTimeline, offsetMs: Int, expectedLatency: Int) {
        // Let every sync packet sent so far arrive first, so the next one recorded is the forced one.
        assertTrue(waitUntil(2_000) { sink.syncPackets.size.toLong() == sender.stats.syncPacketsSent })
        val before = sink.syncPackets.size
        val sentBefore = sender.stats.syncPacketsSent
        sender.setSyncOffsetMs(offsetMs)
        assertEquals(offsetMs.coerceIn(-250, 250), sender.syncOffsetMs)
        sender.tick()
        assertEquals("the next tick sends one sync packet at once", sentBefore + 1, sender.stats.syncPacketsSent)
        assertTrue("sync packet arrived", waitUntil(2_000) { sink.syncPackets.size > before })
        assertSyncPacket(sink.syncPackets[before], timeline, expectedLatency, first = false)
        sender.tick()
        assertEquals("no extra sync packet on the following tick", sentBefore + 1, sender.stats.syncPacketsSent)
    }

    @Test
    fun airPlay1SyncPacketsCarryTheOffsetOnConnectAndAfterALiveChange() {
        MockRaopReceiver().start().use { mock ->
            val sender = RaopSender(
                RaopConfig(name = "Mock AirPort", host = loopback, port = mock.port, keepAliveSeconds = 1, settings = StreamSettings(syncOffsetMs = 120)),
                identity,
            )
            val timeline = LoopbackSupport.timeline()
            sender.connect(timeline, 0)
            val sink = mock.sink!!
            assertTrue(waitUntil(3_000) { sink.syncPackets.isNotEmpty() })
            assertSyncPacket(sink.syncPackets.first(), timeline, 77_175 + 5_292, first = true)

            val sent = stream(sender, timeline, 0, 60).toMutableList()
            liveChange(sender, sink, timeline, -250, 66_150) // play earlier: 1.75 s total
            sent += stream(sender, timeline, 60, 60)
            liveChange(sender, sink, timeline, 999, 88_200) // clamped to +250 ms: 2.25 s total
            sent += stream(sender, timeline, 120, 40)
            liveChange(sender, sink, timeline, 0, 77_175)
            sent += stream(sender, timeline, 160, 40)

            assertContinuousAudio(sink, sent)
            assertSame("same UDP session: no new SETUP", sink, mock.sink)
            for (m in listOf("ANNOUNCE", "SETUP", "RECORD")) assertEquals("$m count", 1, mock.requests.count { it.startsWith("$m ") })
            assertTrue("no FLUSH or TEARDOWN", mock.requests.none { it.startsWith("FLUSH") || it.startsWith("TEARDOWN") })
            sender.close()
            assertTrue(waitUntil { mock.teardown })
            assertTrue(mock.errors.isEmpty())
        }
    }

    @Test
    fun airPlay2SyncPacketsCarryTheOffsetOnConnectAndAfterALiveChange() {
        MockAp2Receiver().start().use { mock ->
            val sender = Ap2Sender(
                Ap2Config(
                    name = "Mock HomePod", host = loopback, port = mock.port, receiverKey = "mock-hp",
                    feedbackIntervalMs = 150, settings = StreamSettings(syncOffsetMs = -40),
                ),
                identity,
                InMemoryPairingStore(),
            )
            val timeline = LoopbackSupport.timeline()
            sender.connect(timeline, 0)
            val sink = mock.sink!!
            assertTrue(waitUntil(3_000) { sink.syncPackets.isNotEmpty() })
            assertSyncPacket(sink.syncPackets.first(), timeline, 77_175 - 1_764, first = true)

            val sent = stream(sender, timeline, 0, 60).toMutableList()
            liveChange(sender, sink, timeline, 250, 88_200) // delay: 2.25 s total
            sent += stream(sender, timeline, 60, 60)
            liveChange(sender, sink, timeline, -25, 77_175 - 1_103)
            sent += stream(sender, timeline, 120, 40)

            assertContinuousAudio(sink, sent)
            assertSame("same stream: no new SETUP", sink, mock.sink)
            assertEquals("one transient pairing only", 1, mock.transientSetups)
            assertEquals(2, mock.requests.count { it == "POST /pair-setup" })
            assertEquals("session + stream SETUP only", 2, mock.encryptedRequests.count { it.startsWith("SETUP ") })
            assertEquals(1, mock.encryptedRequests.count { it.startsWith("RECORD ") })
            assertTrue("keep-alive still running", waitUntil(3_000) { mock.feedbacks >= 2 })
            assertTrue(mock.encryptedRequests.none { it.startsWith("TEARDOWN") || it.startsWith("FLUSH") })
            sender.close()
            assertTrue(waitUntil { mock.teardown })
            assertTrue("mock errors: ${mock.errors}", mock.errors.isEmpty())
        }
    }

    /**
     * The app path: ConnectOptions -> ReceiverSession -> sender -> RtpStreamer, then a live change
     * through AirPlaySessionManager.setSyncOffsetMs while the sessions' own worker threads tick.
     */
    @Test
    fun sessionManagerAppliesOffsetsPerSpeakerWithoutReconnecting() {
        MockRaopReceiver().start().use { ap1Mock ->
            MockAp2Receiver().start().use { ap2Mock ->
                val ap1 = AirPlayReceiver(id = "den", name = "Den AVR", host = "127.0.0.1", raopPort = ap1Mock.port, raopTxt = mapOf("cn" to "0,1", "et" to "0,4"))
                val ap2 = AirPlayReceiver(
                    id = "kitchen", name = "Kitchen HomePod", host = "127.0.0.1", airplayPort = ap2Mock.port,
                    airplayTxt = mapOf("features" to "0x445F8A00,0x1C340", "flags" to "0x4", "model" to "AudioAccessory5,1"),
                )
                val manager = AirPlaySessionManager(DefaultSenderFactory(SenderIdentity.random(), InMemoryPairingStore()))
                manager.start()
                manager.connect(ap1, ConnectOptions(syncOffsetMs = 100))
                manager.connect(ap2, ConnectOptions(syncOffsetMs = -60))
                assertTrue(waitUntil(15_000) { manager.statuses.value.values.count { it.state == SessionState.STREAMING } == 2 })
                assertEquals(100, manager.statuses.value["den"]!!.syncOffsetMs)
                val sinkA = ap1Mock.sink!!
                val sinkB = ap2Mock.sink!!
                assertTrue(waitUntil(3_000) { sinkA.syncPackets.isNotEmpty() && sinkB.syncPackets.isNotEmpty() })
                assertEquals(77_175L + 4_410, latencyOf(sinkA.syncPackets.first()))
                assertEquals(77_175L - 2_646, latencyOf(sinkB.syncPackets.first()))

                TestAudio.sineChunks(40).forEach { manager.push(it); Thread.sleep(2) }
                val bBefore = sinkB.syncPackets.size
                manager.setSyncOffsetMs("den", -200)
                val t0 = System.nanoTime()
                assertTrue("den re-synced", waitUntil(2_000) { sinkA.syncPackets.any { latencyOf(it) == 77_175L - 8_820 } })
                assertTrue("forced sync well within the 1 s period", (System.nanoTime() - t0) / 1_000_000 < 900)
                assertEquals(-200, manager.statuses.value["den"]!!.syncOffsetMs)
                Thread.sleep(300)
                assertTrue("kitchen untouched", sinkB.syncPackets.drop(bBefore).all { latencyOf(it) == 77_175L - 2_646 })

                val chunks = TestAudio.sineChunks(80)
                chunks.forEach { manager.push(it); Thread.sleep(2) }
                assertTrue(sinkA.waitForFrames(80) && sinkB.waitForFrames(80))
                assertEquals("no reconnect", 1, ap1Mock.requests.count { it.startsWith("SETUP ") })
                assertEquals(1, ap2Mock.transientSetups)
                assertSame(sinkA, ap1Mock.sink)
                assertSame(sinkB, ap2Mock.sink)
                manager.stop()
                assertTrue(waitUntil { ap1Mock.teardown && ap2Mock.teardown })
                assertTrue(sinkA.errors.isEmpty() && sinkB.errors.isEmpty())
            }
        }
    }
}
