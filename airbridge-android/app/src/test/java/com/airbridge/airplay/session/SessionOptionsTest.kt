package com.airbridge.airplay.session

import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AirPlaySender
import com.airbridge.airplay.airplay.AirPlayVolume
import com.airbridge.airplay.airplay.AudioFrame
import com.airbridge.airplay.airplay.SenderStats
import com.airbridge.airplay.airplay.StreamTimeline
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.util.NtpClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ReceiverSession hands the speaker's options to its sender (ConnectOptions -> sender), and a
 * volume or sync-offset change made while the speaker is still connecting reaches it once the
 * connection is up.
 */
class SessionOptionsTest {
    /** Records what the session asks of it; connect() blocks until [release] is counted down. */
    private class RecordingSender : AirPlaySender {
        override val protocol = AirPlayProtocol.AIRPLAY_1
        override val displayName = "Recorder"
        override val stats = SenderStats()
        val events: MutableList<String> = CopyOnWriteArrayList()
        val connecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile override var syncOffsetMs = 0
            private set

        override fun connect(timeline: StreamTimeline, firstFrameIndex: Long) {
            events += "connect(offset=$syncOffsetMs)"
            connecting.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        override fun send(frame: AudioFrame) = Unit
        override fun tick() = Unit
        override fun setVolume(db: Double) {
            events += "volume($db)"
        }
        override fun setSyncOffsetMs(offsetMs: Int) {
            syncOffsetMs = offsetMs
            events += "offset($offsetMs)"
        }
        override fun flush(nextFrameIndex: Long) = Unit
        override fun close() {
            events += "close"
        }
    }

    @Test
    fun changesMadeWhileConnectingAreAppliedOnceConnected() {
        val sender = RecordingSender()
        val created = CopyOnWriteArrayList<ConnectOptions>()
        val receiver = AirPlayReceiver("r1", "Recorder", "192.0.2.20", raopPort = 7000)
        val session = ReceiverSession(
            receiver,
            ConnectOptions(volumePercent = 50, syncOffsetMs = 35),
            { _, _, options -> created += options; sender },
            StreamTimeline(NtpClock(), 0),
            { 0L },
            { },
        )
        session.start()
        assertTrue(sender.connecting.await(5, TimeUnit.SECONDS))
        assertEquals("factory got the options", 35, created.single().syncOffsetMs)
        assertEquals("offset handed to the sender before connect", listOf("offset(35)", "connect(offset=35)"), sender.events.toList())

        session.setVolume(80) // still CONNECTING: no sender yet, so only stored
        session.setSyncOffsetMs(-400) // clamped to -250
        assertEquals(-250, session.options.syncOffsetMs)
        sender.release.countDown()
        assertTrue(waitUntil { session.state == SessionState.STREAMING })
        assertTrue(waitUntil { sender.events.size >= 4 })
        assertEquals(
            listOf("offset(35)", "connect(offset=35)", "volume(${AirPlayVolume.percentToDb(80)})", "offset(-250)"),
            sender.events.toList(),
        )
        assertEquals(-250, session.status().syncOffsetMs)
        assertEquals(80, session.status().volumePercent)

        // Live changes on a streaming speaker go straight to the sender (no reconnect).
        session.setSyncOffsetMs(15)
        session.setVolume(20)
        assertEquals(listOf("offset(15)", "volume(${AirPlayVolume.percentToDb(20)})"), sender.events.drop(4))
        assertEquals("connected once", 1, created.size)
        session.stop()
        session.join(3_000)
        assertTrue(sender.events.last() == "close")
    }
}
