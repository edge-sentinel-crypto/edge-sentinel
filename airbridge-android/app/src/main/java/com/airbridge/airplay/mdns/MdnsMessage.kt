package com.airbridge.airplay.mdns

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Minimal DNS message codec for multicast DNS service discovery (RFC 1035 / 6762 / 6763):
 * builds PTR/SRV/TXT/A queries and parses responses including name compression. Used by the raw
 * mDNS fallback browser (Android's NsdManager drops TXT records on some OEM builds).
 */
object MdnsMessage {
    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val CLASS_IN = 1

    class DnsFormatException(message: String) : Exception(message)

    data class Question(val name: String, val type: Int, val unicastResponse: Boolean = true)

    data class Record(
        val name: String,
        val type: Int,
        val ttl: Long,
        val rdata: ByteArray,
        /** Decoded target for PTR (instance name) or SRV (host name). */
        val target: String? = null,
        val port: Int = 0,
        val address: InetAddress? = null,
    ) {
        val txt: Map<String, String> get() = if (type == TYPE_TXT) TxtRecord.parseRdata(rdata) else emptyMap()
    }

    data class Message(val id: Int, val isResponse: Boolean, val questions: List<Question>, val records: List<Record>)

    fun buildQuery(id: Int, questions: List<Question>): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0) // standard query
        writeU16(out, questions.size)
        writeU16(out, 0); writeU16(out, 0); writeU16(out, 0)
        for (q in questions) {
            writeName(out, q.name)
            writeU16(out, q.type)
            writeU16(out, CLASS_IN or (if (q.unicastResponse) 0x8000 else 0))
        }
        return out.toByteArray()
    }

    /** Builds a response (used by tests and the loopback discovery fixture). */
    fun buildResponse(id: Int, records: List<Record>): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0x8400) // response, authoritative
        writeU16(out, 0)
        writeU16(out, records.size)
        writeU16(out, 0); writeU16(out, 0)
        for (r in records) {
            writeName(out, r.name)
            writeU16(out, r.type)
            writeU16(out, CLASS_IN)
            writeU32(out, r.ttl)
            val rdata = when (r.type) {
                TYPE_PTR -> nameBytes(r.target!!)
                TYPE_SRV -> ByteArrayOutputStream().also { b ->
                    writeU16(b, 0); writeU16(b, 0); writeU16(b, r.port); b.write(nameBytes(r.target!!))
                }.toByteArray()
                TYPE_A, TYPE_AAAA -> r.address!!.address
                else -> r.rdata
            }
            writeU16(out, rdata.size)
            out.write(rdata)
        }
        return out.toByteArray()
    }

    fun parse(data: ByteArray, length: Int = data.size): Message {
        if (length < 12) throw DnsFormatException("DNS message too short")
        val id = u16(data, 0)
        val flags = u16(data, 2)
        val qd = u16(data, 4)
        val an = u16(data, 6)
        val ns = u16(data, 8)
        val ar = u16(data, 10)
        var pos = 12
        val questions = ArrayList<Question>()
        repeat(qd) {
            val (name, next) = readName(data, length, pos)
            if (next + 4 > length) throw DnsFormatException("truncated question")
            questions.add(Question(name, u16(data, next), u16(data, next + 2) and 0x8000 != 0))
            pos = next + 4
        }
        val records = ArrayList<Record>()
        repeat(an + ns + ar) {
            val (name, next) = readName(data, length, pos)
            if (next + 10 > length) throw DnsFormatException("truncated record header")
            val type = u16(data, next)
            val ttl = ((data[next + 4].toLong() and 0xFF) shl 24) or ((data[next + 5].toLong() and 0xFF) shl 16) or
                ((data[next + 6].toLong() and 0xFF) shl 8) or (data[next + 7].toLong() and 0xFF)
            val rdLen = u16(data, next + 8)
            val rdStart = next + 10
            if (rdStart + rdLen > length) throw DnsFormatException("truncated rdata")
            val rdata = data.copyOfRange(rdStart, rdStart + rdLen)
            val record = when (type) {
                TYPE_PTR -> Record(name, type, ttl, rdata, target = readName(data, length, rdStart).first)
                TYPE_SRV -> {
                    if (rdLen < 7) throw DnsFormatException("short SRV")
                    Record(name, type, ttl, rdata, target = readName(data, length, rdStart + 6).first, port = u16(data, rdStart + 4))
                }
                TYPE_A -> Record(name, type, ttl, rdata, address = if (rdLen == 4) InetAddress.getByAddress(rdata) else null)
                TYPE_AAAA -> Record(name, type, ttl, rdata, address = if (rdLen == 16) InetAddress.getByAddress(rdata) else null)
                else -> Record(name, type, ttl, rdata)
            }
            records.add(record)
            pos = rdStart + rdLen
        }
        return Message(id, flags and 0x8000 != 0, questions, records)
    }

    /** Reads a possibly-compressed name; returns the name and the position after it. */
    fun readName(data: ByteArray, length: Int, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            if (pos >= length) throw DnsFormatException("name runs past end")
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (end < 0) end = pos + 1
                    break
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= length) throw DnsFormatException("truncated pointer")
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    if (++jumps > 32) throw DnsFormatException("name compression loop")
                }
                len and 0xC0 != 0 -> throw DnsFormatException("bad label type")
                else -> {
                    if (pos + 1 + len > length) throw DnsFormatException("label runs past end")
                    labels.add(String(data, pos + 1, len, Charsets.UTF_8))
                    pos += 1 + len
                }
            }
        }
        return labels.joinToString(".") to end
    }

    private fun nameBytes(name: String): ByteArray = ByteArrayOutputStream().also { writeName(it, name) }.toByteArray()

    /**
     * Writes a dotted name as DNS labels. A literal dot inside a label (possible in DNS-SD
     * instance names) can be escaped as "\.".
     */
    fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in splitLabels(name)) {
            val b = label.toByteArray(Charsets.UTF_8)
            require(b.size in 1..63) { "bad DNS label length" }
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    private fun splitLabels(name: String): List<String> {
        val labels = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        val n = name.trimEnd('.')
        while (i < n.length) {
            val c = n[i]
            if (c == '\\' && i + 1 < n.length) {
                sb.append(n[i + 1]); i += 2; continue
            }
            if (c == '.') {
                labels.add(sb.toString()); sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) labels.add(sb.toString())
        return labels
    }

    private fun u16(d: ByteArray, p: Int) = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
    private fun writeU16(out: ByteArrayOutputStream, v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt()); out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt())
    }
}
