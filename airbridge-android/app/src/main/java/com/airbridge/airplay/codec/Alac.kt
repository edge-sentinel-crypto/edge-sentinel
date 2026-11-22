package com.airbridge.airplay.codec

/**
 * Apple Lossless (ALAC) framing for AirPlay.
 *
 * AirPlay receivers (AirPort Express, Apple TV, HomePod, Sonos, Shairport-Sync, ...) all accept
 * "uncompressed" ALAC frames: an ALAC bitstream whose channel-pair element has the escape flag set,
 * so samples are stored verbatim. That is bit-exact lossless and cheap enough to run on a phone for
 * many receivers at once. Layout of one frame (MSB-first bits):
 *
 *   element tag      3  = 1 (ID_CPE, a stereo channel pair) or 0 (ID_SCE, mono)
 *   instance tag     4  = 0
 *   unused          12  = 0
 *   partial frame    1  = 1 when the frame holds fewer samples than the stream's frameLength
 *   bytes shifted    2  = 0
 *   escape flag      1  = 1 (samples are not compressed)
 *   [sample count   32] only when partial frame = 1
 *   samples        N x channels x 16, interleaved L,R as two's complement
 *   end tag          3  = 7 (ID_END) then zero padding to a byte boundary
 *
 * The end tag matters: Apple's reference decoder and FFmpeg loop over elements until ID_END.
 */
object AlacEncoder {
    const val FRAMES_PER_PACKET = 352
    const val SAMPLE_RATE = 44_100
    const val BIT_DEPTH = 16
    const val CHANNELS = 2

    private const val ID_SCE = 0
    private const val ID_CPE = 1
    private const val ID_END = 7

    /** SDP fmtp parameters that describe these frames (frameLength 352, 16 bit, 2 ch, 44.1 kHz). */
    const val SDP_FMTP = "352 0 16 40 10 14 2 255 0 0 44100"

    /** Size in bytes of an encoded frame holding [frames] stereo samples. */
    fun encodedSize(frames: Int, channels: Int = CHANNELS, defaultFrames: Int = FRAMES_PER_PACKET): Int {
        val headerBits = 3 + 4 + 12 + 1 + 2 + 1 + (if (frames != defaultFrames) 32 else 0)
        val bits = headerBits + frames.toLong() * channels * BIT_DEPTH + 3
        return ((bits + 7) / 8).toInt()
    }

    /**
     * Encodes interleaved 16-bit PCM ([frames] x [channels] shorts starting at [offset]) into one
     * uncompressed ALAC frame. [defaultFrames] is the frameLength announced to the receiver; a
     * shorter frame is flagged as partial and carries its own sample count.
     */
    fun encode(
        pcm: ShortArray,
        offset: Int = 0,
        frames: Int = FRAMES_PER_PACKET,
        channels: Int = CHANNELS,
        defaultFrames: Int = FRAMES_PER_PACKET,
    ): ByteArray {
        require(channels == 1 || channels == 2) { "only mono or stereo supported" }
        require(frames in 1..defaultFrames) { "frames must be 1..$defaultFrames" }
        require(offset >= 0 && offset + frames * channels <= pcm.size) { "pcm too short" }
        val partial = frames != defaultFrames
        val w = BitWriter(encodedSize(frames, channels, defaultFrames))
        w.write(if (channels == 2) ID_CPE else ID_SCE, 3)
        w.write(0, 4)      // element instance tag
        w.write(0, 12)     // unused header bits
        w.write(if (partial) 1 else 0, 1)
        w.write(0, 2)      // bytes shifted
        w.write(1, 1)      // escape: uncompressed samples follow
        if (partial) w.write(frames, 32)
        val total = frames * channels
        for (i in 0 until total) {
            w.write(pcm[offset + i].toInt() and 0xFFFF, BIT_DEPTH)
        }
        w.write(ID_END, 3)
        return w.toByteArray()
    }

    /**
     * 24-byte ALACSpecificConfig ("magic cookie") matching [SDP_FMTP]; used by CAF/MP4 containers
     * (our FFmpeg interoperability test) and by AirPlay 2 receivers that ask for it.
     */
    fun magicCookie(frameLength: Int = FRAMES_PER_PACKET, channels: Int = CHANNELS, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val c = ByteArray(24)
        fun u32(off: Int, v: Int) {
            c[off] = (v ushr 24).toByte(); c[off + 1] = (v ushr 16).toByte()
            c[off + 2] = (v ushr 8).toByte(); c[off + 3] = v.toByte()
        }
        u32(0, frameLength)
        c[4] = 0                 // compatibleVersion
        c[5] = BIT_DEPTH.toByte()
        c[6] = 40                // pb
        c[7] = 10                // mb
        c[8] = 14                // kb
        c[9] = channels.toByte()
        c[10] = 0; c[11] = 255.toByte() // maxRun = 255
        u32(12, 0)               // maxFrameBytes (0 = unknown)
        u32(16, 0)               // avgBitRate (0 = unknown)
        u32(20, sampleRate)
        return c
    }
}

/**
 * Decoder for ALAC frames that use the escape (uncompressed) mode, following the element loop of
 * Apple's reference ALACDecoder. Used by the loopback mock receivers and the bit-exact tests.
 * Compressed (predictor-coded) frames are rejected: AirBridge never produces them.
 */
object AlacDecoder {
    class AlacFormatException(message: String) : Exception(message)

    /** Decodes one frame into interleaved 16-bit samples. */
    fun decode(frame: ByteArray, channels: Int = AlacEncoder.CHANNELS, defaultFrames: Int = AlacEncoder.FRAMES_PER_PACKET): ShortArray {
        val r = BitReader(frame)
        var out: ShortArray? = null
        var channelIndex = 0
        var frames = defaultFrames
        try {
            while (true) {
                val tag = r.read(3)
                when (tag) {
                    0, 1 -> { // ID_SCE or ID_CPE
                        val elementChannels = if (tag == 1) 2 else 1
                        if (channelIndex + elementChannels > channels) throw AlacFormatException("too many channels in frame")
                        r.read(4) // element instance tag
                        val unused = r.read(12)
                        if (unused != 0) throw AlacFormatException("non-zero unused header bits")
                        val partial = r.read(1)
                        val bytesShifted = r.read(2)
                        val escape = r.read(1)
                        if (bytesShifted != 0) throw AlacFormatException("shifted samples not supported")
                        if (escape != 1) throw AlacFormatException("compressed ALAC frames are not supported by this decoder")
                        if (partial == 1) {
                            frames = r.read(32)
                            if (frames <= 0 || frames > defaultFrames) throw AlacFormatException("bad partial sample count $frames")
                        }
                        val buffer = out ?: ShortArray(frames * channels).also { out = it }
                        if (buffer.size != frames * channels) throw AlacFormatException("inconsistent frame size")
                        for (i in 0 until frames) {
                            for (c in 0 until elementChannels) {
                                buffer[i * channels + channelIndex + c] = r.readSigned(AlacEncoder.BIT_DEPTH).toShort()
                            }
                        }
                        channelIndex += elementChannels
                    }
                    7 -> { // ID_END
                        r.byteAlign()
                        if (channelIndex != channels) throw AlacFormatException("frame ended after $channelIndex of $channels channels")
                        return out ?: throw AlacFormatException("empty frame")
                    }
                    else -> throw AlacFormatException("unsupported ALAC element tag $tag")
                }
            }
        } catch (e: IllegalStateException) {
            throw AlacFormatException("truncated ALAC frame: ${e.message}")
        }
    }
}
