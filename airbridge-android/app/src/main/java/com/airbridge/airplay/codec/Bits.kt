package com.airbridge.airplay.codec

/** MSB-first bit writer used by the ALAC encoder. */
class BitWriter(capacityBytes: Int) {
    private var buf = ByteArray(capacityBytes.coerceAtLeast(1))
    private var bitPos = 0L

    val bitLength: Long get() = bitPos

    fun write(value: Int, bits: Int) {
        require(bits in 0..32) { "bits out of range: $bits" }
        for (i in bits - 1 downTo 0) {
            writeBit((value ushr i) and 1)
        }
    }

    fun writeBit(bit: Int) {
        val byteIndex = (bitPos ushr 3).toInt()
        if (byteIndex >= buf.size) buf = buf.copyOf(buf.size * 2)
        if (bit != 0) {
            val shift = 7 - (bitPos and 7).toInt()
            buf[byteIndex] = (buf[byteIndex].toInt() or (1 shl shift)).toByte()
        }
        bitPos++
    }

    /** Returns the written bytes; the last byte is zero-padded to a byte boundary. */
    fun toByteArray(): ByteArray = buf.copyOf(((bitPos + 7) ushr 3).toInt())
}

/** MSB-first bit reader with bounds checking (used by the ALAC decoder and mock receivers). */
class BitReader(private val data: ByteArray, private val offset: Int = 0, length: Int = data.size - offset) {
    private val endBit = (offset + length).toLong() * 8
    private var bitPos = offset.toLong() * 8

    val bitsLeft: Long get() = endBit - bitPos

    fun read(bits: Int): Int {
        require(bits in 0..32) { "bits out of range: $bits" }
        if (bitsLeft < bits) throw IllegalStateException("bitstream underrun: need $bits, have $bitsLeft")
        var v = 0L
        for (i in 0 until bits) {
            val byteIndex = (bitPos ushr 3).toInt()
            val shift = 7 - (bitPos and 7).toInt()
            v = (v shl 1) or ((data[byteIndex].toInt() ushr shift) and 1).toLong()
            bitPos++
        }
        return v.toInt()
    }

    /** Reads [bits] and sign-extends them. */
    fun readSigned(bits: Int): Int {
        val raw = read(bits)
        val shift = 32 - bits
        return (raw shl shift) shr shift
    }

    fun byteAlign() {
        val rem = (bitPos and 7).toInt()
        if (rem != 0) bitPos += (8 - rem)
    }
}
