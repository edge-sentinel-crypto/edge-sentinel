package com.airbridge.airplay.codec

import com.airbridge.airplay.testutil.TestAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AlacCodecTest {
    @Test
    fun fullFrameIsBitExactAndHasExpectedSize() {
        val pcm = TestAudio.sineStereo(352)
        val frame = AlacEncoder.encode(pcm)
        // 23 header bits + 352*2*16 sample bits + 3-bit end tag = 11290 bits -> 1412 bytes.
        assertEquals(1412, frame.size)
        assertEquals(AlacEncoder.encodedSize(352), frame.size)
        assertArrayEquals(pcm, AlacDecoder.decode(frame))
    }

    @Test
    fun headerBitsMatchAppleLayout() {
        val frame = AlacEncoder.encode(ShortArray(704))
        val r = BitReader(frame)
        assertEquals("ID_CPE", 1, r.read(3))
        assertEquals("instance tag", 0, r.read(4))
        assertEquals("unused", 0, r.read(12))
        assertEquals("partial frame", 0, r.read(1))
        assertEquals("bytes shifted", 0, r.read(2))
        assertEquals("escape (uncompressed)", 1, r.read(1))
        repeat(704) { assertEquals(0, r.read(16)) }
        assertEquals("ID_END", 7, r.read(3))
    }

    @Test
    fun samplesAreBigEndianLeftThenRight() {
        val pcm = shortArrayOf(0x1234, 0x5678.toShort(), -2, Short.MIN_VALUE)
        val frame = AlacEncoder.encode(pcm, frames = 2)
        val r = BitReader(frame)
        r.read(3 + 4 + 12); r.read(1); r.read(2); r.read(1)
        assertEquals("partial frame sample count", 2, r.read(32))
        assertEquals(0x1234, r.read(16))
        assertEquals(0x5678, r.read(16))
        assertEquals(0xFFFE, r.read(16))
        assertEquals(0x8000, r.read(16))
        assertEquals(7, r.read(3))
    }

    @Test
    fun extremesAndRandomDataRoundTrip() {
        assertArrayEquals(TestAudio.edgeCasePcm(352), AlacDecoder.decode(AlacEncoder.encode(TestAudio.edgeCasePcm(352))))
        for (seed in 1L..25L) {
            val pcm = TestAudio.randomPcm(704, seed)
            assertArrayEquals("seed $seed", pcm, AlacDecoder.decode(AlacEncoder.encode(pcm)))
        }
    }

    @Test
    fun partialFrameCarriesItsOwnLength() {
        val pcm = TestAudio.sineStereo(100)
        val frame = AlacEncoder.encode(pcm, frames = 100)
        assertEquals(AlacEncoder.encodedSize(100), frame.size)
        assertArrayEquals(pcm, AlacDecoder.decode(frame))
    }

    @Test
    fun monoFrameUsesSingleChannelElement() {
        val mono = ShortArray(352) { (it * 91).toShort() }
        val frame = AlacEncoder.encode(mono, channels = 1)
        assertEquals(0, BitReader(frame).read(3))
        assertArrayEquals(mono, AlacDecoder.decode(frame, channels = 1))
    }

    @Test
    fun longSineStreamDecodesBitExact() {
        val chunks = TestAudio.sineChunks(250) // ~2 seconds
        val decoded = chunks.map { AlacDecoder.decode(AlacEncoder.encode(it)) }
        assertArrayEquals(TestAudio.concat(chunks), TestAudio.concat(decoded))
    }

    @Test
    fun decoderRejectsTruncatedAndCompressedFrames() {
        val frame = AlacEncoder.encode(TestAudio.sineStereo(352))
        try {
            AlacDecoder.decode(frame.copyOf(100))
            fail("truncated frame accepted")
        } catch (e: AlacDecoder.AlacFormatException) {
            assertTrue(e.message!!.contains("truncated"))
        }
        val compressed = frame.copyOf()
        // Clear the escape bit (bit 22 of the frame) to make it look like a compressed frame.
        compressed[2] = (compressed[2].toInt() and 0xFD).toByte()
        try {
            AlacDecoder.decode(compressed)
            fail("compressed frame accepted")
        } catch (e: AlacDecoder.AlacFormatException) {
            assertTrue(e.message!!.contains("compressed"))
        }
    }

    @Test
    fun magicCookieMatchesSdpParameters() {
        val c = AlacEncoder.magicCookie()
        assertEquals(24, c.size)
        assertEquals(352, ((c[0].toInt() and 0xFF) shl 24) or ((c[1].toInt() and 0xFF) shl 16) or ((c[2].toInt() and 0xFF) shl 8) or (c[3].toInt() and 0xFF))
        assertEquals(16, c[5].toInt())
        assertEquals(40, c[6].toInt())
        assertEquals(10, c[7].toInt())
        assertEquals(14, c[8].toInt())
        assertEquals(2, c[9].toInt())
        assertEquals(255, ((c[10].toInt() and 0xFF) shl 8) or (c[11].toInt() and 0xFF))
        assertEquals(44100, ((c[20].toInt() and 0xFF) shl 24) or ((c[21].toInt() and 0xFF) shl 16) or ((c[22].toInt() and 0xFF) shl 8) or (c[23].toInt() and 0xFF))
        assertEquals("352 0 16 40 10 14 2 255 0 0 44100", AlacEncoder.SDP_FMTP)
    }

    @Test
    fun l16IsBigEndianAndRoundTrips() {
        val pcm = shortArrayOf(0x0102, -2, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = PcmL16.encode(pcm)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0xFE.toByte(), 0x7F, 0xFF.toByte(), 0x80.toByte(), 0x00), bytes)
        assertArrayEquals(pcm, PcmL16.decode(bytes))
        val le = byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte())
        val out = ShortArray(2)
        PcmL16.fromLittleEndian(le, 0, 4, out)
        assertArrayEquals(shortArrayOf(0x0102, -2), out)
    }
}
