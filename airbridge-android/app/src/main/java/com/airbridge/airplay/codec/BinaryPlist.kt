package com.airbridge.airplay.codec

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** A plist date (seconds since 2001-01-01T00:00:00Z). */
data class PlistDate(val secondsSince2001: Double)

/** A keyed-archiver UID object. */
data class PlistUid(val value: Long)

/**
 * Apple binary property list ("bplist00") encoder and decoder.
 *
 * AirPlay 2 uses binary plists for `GET /info`, the two-phase `SETUP`, `TEARDOWN` and event
 * messages. Supported value types: null, Boolean, integers (Byte/Short/Int/Long), Float/Double,
 * String (ASCII or UTF-16BE), ByteArray (data), List/Array (array), Set, Map<String, *> (dict),
 * [PlistDate] and [PlistUid]. Decoding returns Long for integers, Double for reals,
 * LinkedHashMap for dicts and ArrayList for arrays. Input from the network is untrusted, so the
 * decoder bounds-checks every offset and rejects reference cycles.
 */
object BinaryPlist {
    class PlistFormatException(message: String) : Exception(message)

    private val MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)
    private const val MAX_DEPTH = 64

    // ---------------------------------------------------------------- encoding

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        val refs = ArrayList<IntArray?>()
        flatten(root, objects, refs, 0)
        val count = objects.size
        val refSize = when {
            count < 0x100 -> 1
            count < 0x10000 -> 2
            else -> 4
        }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        val offsets = LongArray(count)
        for (i in 0 until count) {
            offsets[i] = out.size().toLong()
            writeObject(out, objects[i], refs[i], refSize)
        }
        val offsetTableOffset = out.size().toLong()
        val offsetSize = bytesNeeded(offsetTableOffset)
        for (o in offsets) writeSized(out, o, offsetSize)
        // Trailer: 5 unused bytes, sort version, offset int size, object ref size,
        // object count, top object index, offset table offset.
        repeat(6) { out.write(0) }
        out.write(offsetSize)
        out.write(refSize)
        writeSized(out, count.toLong(), 8)
        writeSized(out, 0L, 8)
        writeSized(out, offsetTableOffset, 8)
        return out.toByteArray()
    }

    private fun flatten(value: Any?, objects: ArrayList<Any?>, refs: ArrayList<IntArray?>, depth: Int): Int {
        if (depth > MAX_DEPTH) throw PlistFormatException("plist nesting too deep")
        val index = objects.size
        objects.add(value)
        refs.add(null)
        when (value) {
            is Map<*, *> -> {
                val r = IntArray(value.size * 2)
                var i = 0
                val entries = value.entries.toList()
                for (e in entries) {
                    val key = e.key as? String ?: throw PlistFormatException("dictionary keys must be strings")
                    r[i++] = flatten(key, objects, refs, depth + 1)
                }
                for (e in entries) r[i++] = flatten(e.value, objects, refs, depth + 1)
                refs[index] = r
            }
            is Collection<*> -> {
                val r = IntArray(value.size)
                var i = 0
                for (v in value) r[i++] = flatten(v, objects, refs, depth + 1)
                refs[index] = r
            }
            is Array<*> -> {
                val r = IntArray(value.size)
                for (i in value.indices) r[i] = flatten(value[i], objects, refs, depth + 1)
                refs[index] = r
            }
        }
        return index
    }

    private fun writeObject(out: ByteArrayOutputStream, value: Any?, refs: IntArray?, refSize: Int) {
        when (value) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (value) 0x09 else 0x08)
            is Byte, is Short, is Int, is Long -> writeInt(out, (value as Number).toLong())
            is Float, is Double -> {
                out.write(0x23)
                writeSized(out, java.lang.Double.doubleToRawLongBits((value as Number).toDouble()), 8)
            }
            is PlistDate -> {
                out.write(0x33)
                writeSized(out, java.lang.Double.doubleToRawLongBits(value.secondsSince2001), 8)
            }
            is PlistUid -> {
                val n = bytesNeeded(value.value)
                out.write(0x80 or (n - 1))
                writeSized(out, value.value, n)
            }
            is ByteArray -> {
                writeMarker(out, 0x40, value.size)
                out.write(value)
            }
            is String -> {
                if (value.all { it.code < 0x80 }) {
                    writeMarker(out, 0x50, value.length)
                    out.write(value.toByteArray(StandardCharsets.US_ASCII))
                } else {
                    writeMarker(out, 0x60, value.length) // UTF-16 code units
                    out.write(value.toByteArray(StandardCharsets.UTF_16BE))
                }
            }
            is Map<*, *> -> {
                writeMarker(out, 0xD0, value.size)
                for (r in refs!!) writeSized(out, r.toLong(), refSize)
            }
            is Set<*> -> {
                writeMarker(out, 0xC0, value.size)
                for (r in refs!!) writeSized(out, r.toLong(), refSize)
            }
            is Collection<*>, is Array<*> -> {
                writeMarker(out, 0xA0, refs!!.size)
                for (r in refs) writeSized(out, r.toLong(), refSize)
            }
            else -> throw PlistFormatException("unsupported plist type ${value.javaClass.name}")
        }
    }

    private fun writeMarker(out: ByteArrayOutputStream, type: Int, length: Int) {
        if (length < 15) {
            out.write(type or length)
        } else {
            out.write(type or 0x0F)
            writeInt(out, length.toLong())
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v < 0 -> { out.write(0x13); writeSized(out, v, 8) }
            v < 0x100 -> { out.write(0x10); writeSized(out, v, 1) }
            v < 0x10000 -> { out.write(0x11); writeSized(out, v, 2) }
            v < 0x100000000L -> { out.write(0x12); writeSized(out, v, 4) }
            else -> { out.write(0x13); writeSized(out, v, 8) }
        }
    }

    private fun writeSized(out: ByteArrayOutputStream, v: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun bytesNeeded(v: Long): Int = when {
        v < 0 -> 8
        v < 0x100 -> 1
        v < 0x10000 -> 2
        v < 0x100000000L -> 4
        else -> 8
    }

    // ---------------------------------------------------------------- decoding

    fun decode(data: ByteArray): Any? = Decoder(data).decodeRoot()

    /** Decodes and requires a dictionary at the root. */
    @Suppress("UNCHECKED_CAST")
    fun decodeDict(data: ByteArray): Map<String, Any?> =
        decode(data) as? Map<String, Any?> ?: throw PlistFormatException("plist root is not a dictionary")

    fun isBinaryPlist(data: ByteArray): Boolean =
        data.size >= 8 + 32 && data[0] == 'b'.code.toByte() && data[1] == 'p'.code.toByte() &&
            data[2] == 'l'.code.toByte() && data[3] == 'i'.code.toByte() && data[4] == 's'.code.toByte() &&
            data[5] == 't'.code.toByte()

    private class Decoder(private val d: ByteArray) {
        private var offsetSize = 0
        private var refSize = 0
        private var count = 0
        private var offsets = LongArray(0)
        private val inProgress = HashSet<Int>()

        fun decodeRoot(): Any? {
            if (!isBinaryPlist(d)) throw PlistFormatException("not a binary plist")
            val t = d.size - 32
            offsetSize = d[t + 6].toInt() and 0xFF
            refSize = d[t + 7].toInt() and 0xFF
            val numObjects = readSized(t + 8, 8)
            val top = readSized(t + 16, 8)
            val tableOffset = readSized(t + 24, 8)
            if (offsetSize !in 1..8 || refSize !in 1..8) throw PlistFormatException("bad trailer sizes")
            if (numObjects <= 0 || numObjects > 1_000_000) throw PlistFormatException("bad object count")
            if (top < 0 || top >= numObjects) throw PlistFormatException("bad top object")
            if (tableOffset < 8 || tableOffset + numObjects * offsetSize > t) throw PlistFormatException("bad offset table")
            count = numObjects.toInt()
            offsets = LongArray(count) { readSized((tableOffset + it * offsetSize).toInt(), offsetSize) }
            for (o in offsets) if (o < 8 || o >= tableOffset) throw PlistFormatException("object offset out of range")
            return obj(top.toInt(), 0)
        }

        private fun readSized(pos: Int, size: Int): Long {
            if (pos < 0 || pos + size > d.size) throw PlistFormatException("read past end")
            var v = 0L
            for (i in 0 until size) v = (v shl 8) or (d[pos + i].toLong() and 0xFF)
            return v
        }

        private fun lengthAndStart(pos: Int, nibble: Int): Pair<Int, Int> {
            if (nibble != 0x0F) return nibble to pos + 1
            val marker = d.getOrNull(pos + 1)?.toInt()?.and(0xFF) ?: throw PlistFormatException("truncated length")
            if (marker and 0xF0 != 0x10) throw PlistFormatException("bad length marker")
            val n = 1 shl (marker and 0x0F)
            if (n > 8) throw PlistFormatException("length too large")
            val len = readSized(pos + 2, n)
            if (len < 0 || len > Int.MAX_VALUE) throw PlistFormatException("bad length")
            return len.toInt() to pos + 2 + n
        }

        private fun obj(index: Int, depth: Int): Any? {
            if (index < 0 || index >= count) throw PlistFormatException("object reference out of range")
            if (depth > MAX_DEPTH) throw PlistFormatException("plist nesting too deep")
            val pos = offsets[index].toInt()
            val marker = d[pos].toInt() and 0xFF
            val type = marker and 0xF0
            val nib = marker and 0x0F
            return when (type) {
                0x00 -> when (marker) {
                    0x00 -> null
                    0x08 -> false
                    0x09 -> true
                    0x0F -> null // fill byte
                    else -> throw PlistFormatException("unknown simple marker 0x%02x".format(marker))
                }
                0x10 -> {
                    val n = 1 shl nib
                    when (n) {
                        1, 2, 4 -> readSized(pos + 1, n)
                        8 -> readSized(pos + 1, 8) // signed 64-bit
                        16 -> readSized(pos + 9, 8) // 128-bit: keep the low 64 bits
                        else -> throw PlistFormatException("bad int size")
                    }
                }
                0x20 -> when (1 shl nib) {
                    4 -> java.lang.Float.intBitsToFloat(readSized(pos + 1, 4).toInt()).toDouble()
                    8 -> java.lang.Double.longBitsToDouble(readSized(pos + 1, 8))
                    else -> throw PlistFormatException("bad real size")
                }
                0x30 -> PlistDate(java.lang.Double.longBitsToDouble(readSized(pos + 1, 8)))
                0x40 -> {
                    val (len, start) = lengthAndStart(pos, nib)
                    checkRange(start, len)
                    d.copyOfRange(start, start + len)
                }
                0x50 -> {
                    val (len, start) = lengthAndStart(pos, nib)
                    checkRange(start, len)
                    String(d, start, len, StandardCharsets.US_ASCII)
                }
                0x60 -> {
                    val (len, start) = lengthAndStart(pos, nib)
                    checkRange(start, len * 2)
                    String(d, start, len * 2, StandardCharsets.UTF_16BE)
                }
                0x70 -> { // UTF-8 string (newer plists)
                    val (len, start) = lengthAndStart(pos, nib)
                    checkRange(start, len)
                    String(d, start, len, StandardCharsets.UTF_8)
                }
                0x80 -> PlistUid(readSized(pos + 1, nib + 1))
                0xA0, 0xC0 -> {
                    val (len, start) = lengthAndStart(pos, nib)
                    checkRange(start, len * refSize)
                    enter(index)
                    val list = ArrayList<Any?>(len)
                    for (i in 0 until len) list.add(obj(readSized(start + i * refSize, refSize).toInt(), depth + 1))
                    leave(index)
                    if (type == 0xC0) LinkedHashSet(list) else list
                }
                0xD0 -> {
                    val (len, start) = lengthAndStart(pos, nib)
                    checkRange(start, len * refSize * 2)
                    enter(index)
                    val map = LinkedHashMap<String, Any?>()
                    for (i in 0 until len) {
                        val k = obj(readSized(start + i * refSize, refSize).toInt(), depth + 1)
                        val v = obj(readSized(start + (len + i) * refSize, refSize).toInt(), depth + 1)
                        map[k?.toString() ?: "null"] = v
                    }
                    leave(index)
                    map
                }
                else -> throw PlistFormatException("unknown object marker 0x%02x".format(marker))
            }
        }

        private fun checkRange(start: Int, len: Int) {
            if (len < 0 || start < 0 || start.toLong() + len > d.size) throw PlistFormatException("object extends past end")
        }

        private fun enter(index: Int) {
            if (!inProgress.add(index)) throw PlistFormatException("reference cycle")
        }

        private fun leave(index: Int) {
            inProgress.remove(index)
        }
    }
}
