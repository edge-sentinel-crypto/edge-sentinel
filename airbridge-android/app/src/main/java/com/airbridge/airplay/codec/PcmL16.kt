package com.airbridge.airplay.codec

/**
 * Big-endian 16-bit linear PCM ("L16", RFC 3551) framing. AirPlay receivers that list codec 0 in
 * their `cn` TXT field (or AirPlay 2 receivers with audio format PCM/44100/16/2) accept this
 * instead of ALAC. AudioRecord delivers native-order shorts; the wire wants network byte order.
 */
object PcmL16 {
    /** AirPlay 2 audioFormat bit for PCM/44100/16/2. */
    const val AP2_AUDIO_FORMAT_PCM_44100_16_2 = 0x800L

    fun encode(pcm: ShortArray, offset: Int = 0, samples: Int = pcm.size - offset): ByteArray {
        require(offset >= 0 && samples >= 0 && offset + samples <= pcm.size) { "pcm range out of bounds" }
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = pcm[offset + i].toInt()
            out[i * 2] = (v ushr 8).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ShortArray {
        require(length % 2 == 0) { "L16 payload must have an even length" }
        val out = ShortArray(length / 2)
        for (i in out.indices) {
            out[i] = (((bytes[offset + i * 2].toInt() and 0xFF) shl 8) or (bytes[offset + i * 2 + 1].toInt() and 0xFF)).toShort()
        }
        return out
    }

    /** Converts little-endian PCM bytes (AudioRecord byte reads) into shorts. */
    fun fromLittleEndian(bytes: ByteArray, offset: Int, length: Int, dst: ShortArray, dstOffset: Int = 0) {
        val n = length / 2
        for (i in 0 until n) {
            dst[dstOffset + i] = ((bytes[offset + i * 2].toInt() and 0xFF) or (bytes[offset + i * 2 + 1].toInt() shl 8)).toShort()
        }
    }
}
