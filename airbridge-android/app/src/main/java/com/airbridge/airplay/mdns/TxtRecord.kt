package com.airbridge.airplay.mdns

import java.util.Locale

/**
 * DNS-SD TXT record parsing. A TXT RDATA is a sequence of length-prefixed strings, each
 * "key=value" (or a bare "key" meaning a boolean attribute). Keys are case-insensitive, so they are
 * normalized to lower case. Values are decoded as UTF-8.
 */
object TxtRecord {
    /** Parses raw TXT RDATA bytes (as found in an mDNS response). */
    fun parseRdata(rdata: ByteArray, offset: Int = 0, length: Int = rdata.size - offset): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val len = rdata[pos].toInt() and 0xFF
            pos++
            if (len == 0) continue
            if (pos + len > end) break // truncated; keep what we have
            val entry = String(rdata, pos, len, Charsets.UTF_8)
            pos += len
            addEntry(out, entry)
        }
        return out
    }

    /** Parses "key=value" strings (e.g. from tests or logs). */
    fun parseStrings(entries: Iterable<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in entries) addEntry(out, e)
        return out
    }

    /** Normalizes Android NsdServiceInfo attributes (values may be null for boolean keys). */
    fun fromAttributes(attributes: Map<String, ByteArray?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in attributes) {
            val key = k.trim().lowercase(Locale.US)
            if (key.isEmpty() || out.containsKey(key)) continue
            out[key] = v?.toString(Charsets.UTF_8) ?: ""
        }
        return out
    }

    /** Builds TXT RDATA (used by tests and the loopback receivers). */
    fun buildRdata(entries: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (e in entries) {
            val b = e.toByteArray(Charsets.UTF_8)
            require(b.size <= 255) { "TXT entry too long" }
            out.write(b.size)
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun addEntry(out: MutableMap<String, String>, entry: String) {
        val eq = entry.indexOf('=')
        val key = (if (eq < 0) entry else entry.substring(0, eq)).trim().lowercase(Locale.US)
        if (key.isEmpty() || out.containsKey(key)) return // RFC 6763: first occurrence wins
        out[key] = if (eq < 0) "" else entry.substring(eq + 1)
    }
}
