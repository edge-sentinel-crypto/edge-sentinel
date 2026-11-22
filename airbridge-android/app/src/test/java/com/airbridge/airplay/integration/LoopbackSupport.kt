package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AirPlaySender
import com.airbridge.airplay.airplay.AudioFrame
import com.airbridge.airplay.airplay.RtpPackets
import com.airbridge.airplay.airplay.StreamTimeline
import com.airbridge.airplay.testutil.MockAudioSink
import com.airbridge.airplay.testutil.TestAudio
import com.airbridge.airplay.util.ByteUtil
import com.airbridge.airplay.util.NtpClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Shared helpers for the loopback protocol tests. */
object LoopbackSupport {
    const val RTP_BASE = 0x7FFFFF00L

    fun timeline(): StreamTimeline = StreamTimeline(NtpClock(), RTP_BASE)

    /** Streams [count] sine chunks through [sender] at roughly 4x real time. */
    fun streamSine(sender: AirPlaySender, timeline: StreamTimeline, count: Int, startIndex: Long = 0): List<ShortArray> {
        val chunks = (0 until count).map { TestAudio.sineStereo(352, (startIndex + it) * 352) }
        chunks.forEachIndexed { i, pcm ->
            val index = startIndex + i
            sender.send(AudioFrame(index, timeline.timestampForFrame(index), pcm))
            sender.tick()
            if (i % 2 == 1) Thread.sleep(1)
        }
        return chunks
    }

    fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /**
     * Verifies a sink received exactly the sent PCM (bit-exact, in order, nothing missing), with
     * consecutive RTP timestamps from [firstTimestamp], the marker bit only on the first packet,
     * one deliberately dropped packet recovered through resend/retransmit, sync packets carrying
     * the 77175-frame latency and answered timing requests.
     */
    fun assertReceivedExactly(sink: MockAudioSink, sent: List<ShortArray>, firstTimestamp: Long) {
        assertTrue("timed out waiting for ${sent.size} frames (got ${sink.frames.size})", sink.waitForFrames(sent.size))
        assertTrue("sink errors: ${sink.errors}", sink.errors.isEmpty())
        assertEquals(sent.size, sink.frames.size)
        assertArrayEquals("decoded PCM differs from the sine that was sent", TestAudio.concat(sent), sink.pcmInOrder())
        val frames = sink.frames.values.toList()
        frames.forEachIndexed { i, f ->
            assertEquals("timestamp of frame $i", (firstTimestamp + i * 352L) and 0xFFFFFFFFL, f.timestamp)
            assertEquals("marker bit only on the first packet", i == 0, f.marker)
        }
        assertTrue("dropped packet was requested", sink.droppedSeq >= 0)
        assertTrue("dropped packet came back as a retransmission", sink.retransmitsReceived >= 1)
        assertTrue(frames.any { it.retransmitted && it.seq == sink.droppedSeq })
        assertTrue("sync packets received", waitUntil(3_000) { sink.syncPackets.isNotEmpty() })
        val firstSync = sink.syncPackets.first()
        assertEquals(0x90, firstSync[0].toInt() and 0xFF)
        assertEquals(77_175L, (ByteUtil.getU32BE(firstSync, 16) - ByteUtil.getU32BE(firstSync, 4)) and 0xFFFFFFFFL)
        assertTrue("timing replies received", waitUntil(3_000) { sink.timingReplies.isNotEmpty() })
        assertEquals(RtpPackets.BYTE1_TIMING_REPLY, sink.timingReplies.first()[1].toInt() and 0xFF)
    }
}
