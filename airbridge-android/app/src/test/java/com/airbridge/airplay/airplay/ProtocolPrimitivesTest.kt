package com.airbridge.airplay.airplay

import com.airbridge.airplay.airplay.raop.RaopEncryption
import com.airbridge.airplay.airplay.raop.RaopSender
import com.airbridge.airplay.util.ByteUtil
import com.airbridge.airplay.util.Hex
import com.airbridge.airplay.util.NtpClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class ProtocolPrimitivesTest {
    @Test
    fun rtspRequestSerializationAndParsing() {
        val h = RtspHeaders()
        h["CSeq"] = "3"
        h["Content-Type"] = "text/parameters"
        val req = RtspRequest("SET_PARAMETER", "rtsp://10.0.0.2/1234", h, "volume: -15.000000\r\n".toByteArray())
        val wire = String(req.serialize())
        assertEquals(
            "SET_PARAMETER rtsp://10.0.0.2/1234 RTSP/1.0\r\nCSeq: 3\r\nContent-Type: text/parameters\r\nContent-Length: 20\r\n\r\nvolume: -15.000000\r\n",
            wire,
        )
        val parsed = RtspCodec.readRequest(ByteArrayInputStream(req.serialize()))!!
        assertEquals("SET_PARAMETER", parsed.method)
        assertEquals(3, parsed.cseq)
        assertEquals("volume: -15.000000\r\n", String(parsed.body))
        assertEquals("text/parameters", parsed.headers["content-type"])
    }

    @Test
    fun rtspResponseParsingAcceptsHttpStatusLines() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nCSeq: 9\r\n\r\nabcRTSP/1.0 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"raop\", nonce=\"abc\"\r\n\r\n"
        val input = ByteArrayInputStream(raw.toByteArray())
        val r1 = RtspCodec.readResponse(input)!!
        assertEquals(200, r1.statusCode)
        assertEquals("abc", String(r1.body))
        val r2 = RtspCodec.readResponse(input)!!
        assertEquals(401, r2.statusCode)
        assertEquals(DigestAuth.Challenge("raop", "abc"), DigestAuth.parseChallenge(r2.headers["www-authenticate"]))
        assertNull(RtspCodec.readResponse(input))
    }

    @Test
    fun digestAuthMatchesIndependentComputation() {
        // Expected value computed with Python hashlib.
        assertEquals("38cd348eaaa37fe16b3df6815bbc87c8", DigestAuth.response("iTunes", "raop", "secret", "ANNOUNCE", "rtsp://192.168.1.2/12345", "0123456789abcdef"))
        val upper = DigestAuth.response("iTunes", "raop", "secret", "ANNOUNCE", "rtsp://192.168.1.2/12345", "0123456789abcdef", upperHex = true)
        assertEquals(upper.uppercase(), upper)
        val header = DigestAuth.authorizationHeader("iTunes", DigestAuth.Challenge("raop", "n1"), "pw", "OPTIONS", "*")
        assertTrue(header.startsWith("Digest username=\"iTunes\", realm=\"raop\", nonce=\"n1\", uri=\"*\", response=\""))
        assertNull(DigestAuth.parseChallenge("Basic realm=\"x\""))
    }

    @Test
    fun transportHeaderParsing() {
        val ports = RaopSender.parseTransport("RTP/AVP/UDP;unicast;mode=record;server_port=6000;control_port=6001;timing_port=6002")
        assertEquals(6000, ports["server_port"])
        assertEquals(6001, ports["control_port"])
        assertEquals(6002, ports["timing_port"])
        assertEquals(53561, RaopSender.parseTransport("RTP/AVP/UDP;server_port=53561-53562")["server_port"])
    }

    @Test
    fun rtpAudioHeaderLayout() {
        val h = RtpPackets.audioHeader(true, 0xFFFF, 0x1_2345_6789L, 0xCAFEBABEL)
        assertEquals("80e0ffff23456789cafebabe", Hex.encode(h))
        assertEquals("8060", Hex.encode(RtpPackets.audioHeader(false, 1, 0, 0)).substring(0, 4))
    }

    @Test
    fun syncPacketLayout() {
        val p = RtpPackets.sync(true, 1000, 0x0102030405060708L, 89200)
        assertEquals(20, p.size)
        assertEquals("90d40007", Hex.encode(p.copyOfRange(0, 4)))
        assertEquals(1000L, ByteUtil.getU32BE(p, 4))
        assertEquals(0x0102030405060708L, ByteUtil.getU64BE(p, 8))
        assertEquals(89200L, ByteUtil.getU32BE(p, 16))
        assertEquals(0x80.toByte(), RtpPackets.sync(false, -5, 0, 3)[0])
        assertEquals("negative RTP values wrap to 32 bits", 0xFFFFFFFBL, ByteUtil.getU32BE(RtpPackets.sync(false, -5, 0, 3), 4))
    }

    @Test
    fun timingReplyEchoesOrigin() {
        val req = RtpPackets.timingRequest(0x1111222233334444L)
        assertTrue(RtpPackets.isTimingRequest(req, req.size))
        val reply = RtpPackets.timingReply(req, 0x5555L, 0x6666L)
        assertEquals("80d30007", Hex.encode(reply.copyOfRange(0, 4)))
        assertEquals(0x1111222233334444L, ByteUtil.getU64BE(reply, 8))
        assertEquals(0x5555L, ByteUtil.getU64BE(reply, 16))
        assertEquals(0x6666L, ByteUtil.getU64BE(reply, 24))
    }

    @Test
    fun resendRequestAndRetransmitPackets() {
        val req = RtpPackets.resendRequest(7, 65534, 3)
        assertEquals(RtpPackets.ResendRequest(65534, 3), RtpPackets.parseResendRequest(req, req.size))
        assertNull(RtpPackets.parseResendRequest(byteArrayOf(0x80.toByte(), 0xD4.toByte(), 0, 0, 0, 0, 0, 1), 8))
        val original = RtpPackets.audioHeader(false, 65535, 1, 2) + byteArrayOf(9, 9)
        val rt = RtpPackets.retransmit(original, 65535)
        assertEquals("80d6ffff", Hex.encode(rt.copyOfRange(0, 4)))
        assertArrayEquals(original, rt.copyOfRange(4, rt.size))
        val buf = RetransmitBuffer(1024)
        buf.put(65535, original)
        buf.put(0, byteArrayOf(1))
        assertArrayEquals(original, buf.get(65535))
        assertNotNull(buf.get(0))
        buf.put(65535 + 1024, byteArrayOf(2)) // same slot, newer seq (wraps to 1023)
        assertNull("evicted by a newer packet in the same slot", buf.get(65535))
    }

    @Test
    fun timelineMathAndWrap() {
        val clock = NtpClock({ 0L }, 0L)
        val t = StreamTimeline(clock, rtpBase = 0xFFFFFF00L, anchorNanos = 0L)
        assertEquals(0xFFFFFF00L, t.timestampForFrame(0))
        assertEquals("wraps at 2^32", (0xFFFFFF00L + 352) and 0xFFFFFFFFL, t.timestampForFrame(1))
        assertEquals((0xFFFFFF00L + 44_100) and 0xFFFFFFFFL, t.rtpAt(1_000_000_000L))
        assertEquals("one frame is 22.675 us", (0xFFFFFF00L + 1) and 0xFFFFFFFFL, t.rtpAt(22_676L))
        // A year of streaming does not overflow.
        val year = 365L * 24 * 3600 * 1_000_000_000L
        assertEquals((44_100L * 365 * 24 * 3600) and 0xFFFFFFFFL, StreamTimeline(clock, 0, 0).rtpAt(year))
        assertEquals(1_000_000_000L * 352 / 44_100, StreamTimeline(clock, 0, 0).nanosForFrame(1))
    }

    @Test
    fun ntpClockConversions() {
        var mono = 5_000_000_000L
        val clock = NtpClock({ mono }, wallMillisAtStart = 0L)
        val ntp = clock.now()
        assertEquals("seconds since 1900 at the Unix epoch", NtpClock.NTP_EPOCH_OFFSET_SECONDS, ntp ushr 32)
        mono += 1_500_000_000L
        val later = clock.now()
        assertEquals(NtpClock.NTP_EPOCH_OFFSET_SECONDS + 1, later ushr 32)
        assertEquals(0x80000000L, later and 0xFFFFFFFFL)
        assertEquals(clock.nowNanos(), NtpClock.ntp64ToNanos(NtpClock.toNtp64(clock.nowNanos())))
    }

    @Test
    fun volumeMapping() {
        assertEquals(-144.0, AirPlayVolume.percentToDb(0), 0.0)
        assertEquals(-30.0 + 0.3, AirPlayVolume.percentToDb(1), 1e-9)
        assertEquals(-15.0, AirPlayVolume.percentToDb(50), 1e-9)
        assertEquals(0.0, AirPlayVolume.percentToDb(100), 0.0)
        assertEquals(0.0, AirPlayVolume.percentToDb(150), 0.0)
        assertEquals(50, AirPlayVolume.dbToPercent(-15.0))
        assertEquals(0, AirPlayVolume.dbToPercent(-144.0))
        assertEquals("-15.000000", AirPlayVolume.format(-15.0))
    }

    @Test
    fun airportPublicKeyIsTheKnownModulus() {
        val modulus = ByteUtil.toUnsignedMinimal(RaopEncryption.AIRPORT_PUBLIC_KEY.modulus)
        assertEquals(256, modulus.size)
        assertEquals(65537L, RaopEncryption.AIRPORT_PUBLIC_KEY.exponent.toLong())
        // SHA-256 of the modulus bytes as published in owntone's raop.c.
        assertEquals("bcab8f192eb454017f0cad661cb3da4b58ead76d09737eb8ad9dcd9a565791d0", Hex.encode(MessageDigest.getInstance("SHA-256").digest(modulus)))
    }

    @Test
    fun raopPayloadEncryptionLeavesTailInClear() {
        val enc = RaopEncryption()
        val payload = ByteArray(1412) { it.toByte() }
        val sealed = enc.encryptPayload(payload)
        assertEquals(payload.size, sealed.size)
        assertArrayEquals("last 1412 % 16 = 4 bytes are not encrypted", payload.copyOfRange(1408, 1412), sealed.copyOfRange(1408, 1412))
        assertTrue(!payload.copyOfRange(0, 16).contentEquals(sealed.copyOfRange(0, 16)))
        assertArrayEquals(payload, RaopEncryption.decryptPayload(enc.aesKey, enc.aesIv, sealed))
        assertTrue(enc.sdpAttributes().startsWith("a=rsaaeskey:"))
        assertTrue("base64 without padding, as iTunes sends it", !enc.sdpAttributes().contains("=\r\n"))
    }
}
