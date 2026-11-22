package com.airbridge.airplay.util

import java.math.BigInteger
import java.security.MessageDigest

/** Hex helpers used for logging, test vectors and DACP identifiers. */
object Hex {
    private val LOWER = "0123456789abcdef".toCharArray()
    private val UPPER = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, upper: Boolean = false): String {
        val digits = if (upper) UPPER else LOWER
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    /** Decodes hex, ignoring whitespace, ':' and '-' separators. */
    fun decode(hex: String): ByteArray {
        val clean = StringBuilder(hex.length)
        for (c in hex) {
            if (c.isWhitespace() || c == ':' || c == '-') continue
            clean.append(c)
        }
        require(clean.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex digit" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

fun ByteArray.toHex(): String = Hex.encode(this)
fun String.hexToBytes(): ByteArray = Hex.decode(this)

/** Big/little-endian helpers and big-integer conversions. */
object ByteUtil {
    fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    fun putU16BE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 8).toByte()
        buf[off + 1] = v.toByte()
    }

    fun putU32BE(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v ushr 24).toByte()
        buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte()
        buf[off + 3] = v.toByte()
    }

    fun putU64BE(buf: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) buf[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    fun putU64LE(buf: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) buf[off + i] = (v ushr (8 * i)).toByte()
    }

    fun getU16BE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    fun getU16LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    fun getU32BE(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or (buf[off + 3].toLong() and 0xFF)

    fun getU64BE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    fun getU64LE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    /** Unsigned big-endian encoding of [v] left-padded with zeros to exactly [length] bytes. */
    fun toUnsignedPadded(v: BigInteger, length: Int): ByteArray {
        require(v.signum() >= 0) { "negative value" }
        val raw = v.toByteArray()
        val start = if (raw.size > 1 && raw[0].toInt() == 0) 1 else 0
        val significant = raw.size - start
        require(significant <= length) { "value does not fit in $length bytes" }
        val out = ByteArray(length)
        System.arraycopy(raw, start, out, length - significant, significant)
        return out
    }

    /** Unsigned big-endian encoding without leading zero bytes (zero encodes as one 0x00 byte). */
    fun toUnsignedMinimal(v: BigInteger): ByteArray {
        require(v.signum() >= 0) { "negative value" }
        val raw = v.toByteArray()
        if (raw.size > 1 && raw[0].toInt() == 0) return raw.copyOfRange(1, raw.size)
        return raw
    }

    fun stripLeadingZeros(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1 && b[i].toInt() == 0) i++
        return if (i == 0) b else b.copyOfRange(i, b.size)
    }

    fun unsigned(b: ByteArray): BigInteger = BigInteger(1, b)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
