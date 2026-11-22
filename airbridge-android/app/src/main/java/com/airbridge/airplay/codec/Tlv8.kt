package com.airbridge.airplay.codec

import java.io.ByteArrayOutputStream

/**
 * HomeKit TLV8 encoding used by AirPlay 2 pair-setup and pair-verify.
 *
 * Each item is type (1 byte), length (1 byte), value. Values longer than 255 bytes are split into
 * consecutive fragments of the same type (255 bytes each, the last one shorter or equal); readers
 * concatenate adjacent fragments. The 384-byte SRP public keys always need fragmentation.
 */
object Tlv8 {
    class TlvFormatException(message: String) : Exception(message)

    /** HAP TLV types. */
    object Type {
        const val METHOD = 0x00
        const val IDENTIFIER = 0x01
        const val SALT = 0x02
        const val PUBLIC_KEY = 0x03
        const val PROOF = 0x04
        const val ENCRYPTED_DATA = 0x05
        const val STATE = 0x06
        const val ERROR = 0x07
        const val RETRY_DELAY = 0x08
        const val CERTIFICATE = 0x09
        const val SIGNATURE = 0x0A
        const val PERMISSIONS = 0x0B
        const val FRAGMENT_DATA = 0x0C
        const val FRAGMENT_LAST = 0x0D
        const val FLAGS = 0x13
        const val SEPARATOR = 0xFF
    }

    /** HAP error codes carried in [Type.ERROR]. */
    object Error {
        const val UNKNOWN = 0x01
        const val AUTHENTICATION = 0x02
        const val BACKOFF = 0x03
        const val MAX_PEERS = 0x04
        const val MAX_TRIES = 0x05
        const val UNAVAILABLE = 0x06
        const val BUSY = 0x07

        fun describe(code: Int): String = when (code) {
            UNKNOWN -> "unknown error"
            AUTHENTICATION -> "wrong PIN or password"
            BACKOFF -> "too many attempts, try again later"
            MAX_PEERS -> "receiver cannot accept more pairings"
            MAX_TRIES -> "too many failed attempts"
            UNAVAILABLE -> "pairing unavailable"
            BUSY -> "receiver is busy pairing with another device"
            else -> "error $code"
        }
    }

    /** Encodes items in order, fragmenting values over 255 bytes. */
    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        var previousType = -1
        for ((type, value) in items) {
            require(type in 0..255) { "TLV type out of range" }
            // Two separate items of the same type must be divided by a separator.
            if (type == previousType && type != Type.SEPARATOR) {
                out.write(Type.SEPARATOR); out.write(0)
            }
            if (value.isEmpty()) {
                out.write(type); out.write(0)
            } else {
                var pos = 0
                while (pos < value.size) {
                    val len = minOf(255, value.size - pos)
                    out.write(type)
                    out.write(len)
                    out.write(value, pos, len)
                    pos += len
                }
            }
            previousType = type
        }
        return out.toByteArray()
    }

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray = encode(items.toList())

    /**
     * Decodes into an ordered list. Adjacent items of the same type are fragments of one value and
     * are joined (HAP rule); a separator item ends a value so the next same-type item starts anew.
     */
    fun decodeList(data: ByteArray): List<Pair<Int, ByteArray>> {
        val result = ArrayList<Pair<Int, ByteArray>>()
        var pos = 0
        var currentType = -1
        var current: ByteArrayOutputStream? = null
        while (pos < data.size) {
            if (pos + 2 > data.size) throw TlvFormatException("truncated TLV header")
            val type = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            pos += 2
            if (pos + len > data.size) throw TlvFormatException("truncated TLV value")
            val continuesFragment = current != null && type == currentType
            if (continuesFragment) {
                current!!.write(data, pos, len)
            } else {
                if (current != null) result.add(currentType to current.toByteArray())
                current = null
                currentType = -1
                if (type != Type.SEPARATOR) {
                    current = ByteArrayOutputStream().also { it.write(data, pos, len) }
                    currentType = type
                }
            }
            pos += len
        }
        if (current != null) result.add(currentType to current.toByteArray())
        return result
    }

    /** Decodes into a map (first occurrence of each type wins). */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val map = LinkedHashMap<Int, ByteArray>()
        for ((t, v) in decodeList(data)) if (!map.containsKey(t)) map[t] = v
        return map
    }

    fun byte(value: Int): ByteArray = byteArrayOf(value.toByte())
}
