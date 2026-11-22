package com.airbridge.airplay.airplay

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/** Case-insensitive header map that keeps insertion order for serialization. */
class RtspHeaders {
    private val order = ArrayList<Pair<String, String>>()
    private val index = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)

    operator fun get(name: String): String? = index[name]

    operator fun set(name: String, value: String) {
        order.removeAll { it.first.equals(name, ignoreCase = true) }
        order.add(name to value)
        index[name] = value
    }

    fun contains(name: String) = index.containsKey(name)

    fun entries(): List<Pair<String, String>> = order.toList()
}

/** An RTSP (or RTSP-framed HTTP) request. AirPlay uses both on the same control connection. */
class RtspRequest(
    val method: String,
    val uri: String,
    val headers: RtspHeaders = RtspHeaders(),
    val body: ByteArray = ByteArray(0),
    val protocol: String = "RTSP/1.0",
) {
    fun serialize(): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(uri).append(' ').append(protocol).append("\r\n")
        for ((k, v) in headers.entries()) {
            if (k.equals("Content-Length", true)) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (body.isNotEmpty()) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray(StandardCharsets.UTF_8)
        return if (body.isEmpty()) head else head + body
    }

    val cseq: Int? get() = headers["CSeq"]?.trim()?.toIntOrNull()
}

class RtspResponse(
    val protocol: String,
    val statusCode: Int,
    val reason: String,
    val headers: RtspHeaders = RtspHeaders(),
    val body: ByteArray = ByteArray(0),
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    fun serialize(): ByteArray {
        val sb = StringBuilder()
        sb.append(protocol).append(' ').append(statusCode).append(' ').append(reason).append("\r\n")
        for ((k, v) in headers.entries()) {
            if (k.equals("Content-Length", true)) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(StandardCharsets.UTF_8) + body
    }

    fun bodyText(): String = String(body, StandardCharsets.UTF_8)
}

/**
 * Minimal RTSP/HTTP message reader: start line, headers, optional Content-Length body.
 * Works over any InputStream (plain socket or the HAP-encrypted stream).
 */
object RtspCodec {
    private const val MAX_LINE = 8192
    private const val MAX_HEADERS = 100
    const val MAX_BODY = 4 * 1024 * 1024

    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw EOFException("connection closed mid-line")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_LINE) throw IOException("RTSP line too long")
        }
        return buf.toString(StandardCharsets.UTF_8.name())
    }

    private fun readHeadersAndBody(input: InputStream): Pair<RtspHeaders, ByteArray> {
        val headers = RtspHeaders()
        var count = 0
        while (true) {
            val line = readLine(input) ?: throw EOFException("connection closed in headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            if (++count > MAX_HEADERS) throw IOException("too many headers")
        }
        val length = headers["Content-Length"]?.trim()?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw IOException("bad Content-Length $length")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("connection closed in body")
            read += n
        }
        return headers to body
    }

    /** Reads a response; returns null on clean EOF before any byte. */
    fun readResponse(input: InputStream): RtspResponse? {
        var line = readLine(input) ?: return null
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ', limit = 3)
        if (parts.size < 2 || !(parts[0].startsWith("RTSP/") || parts[0].startsWith("HTTP/"))) {
            throw IOException("bad status line: $line")
        }
        val code = parts[1].toIntOrNull() ?: throw IOException("bad status code: $line")
        val (headers, body) = readHeadersAndBody(input)
        return RtspResponse(parts[0], code, parts.getOrElse(2) { "" }, headers, body)
    }

    /** Reads a request (used by the event channel and by the loopback mock receivers). */
    fun readRequest(input: InputStream): RtspRequest? {
        var line = readLine(input) ?: return null
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) throw IOException("bad request line: $line")
        val (headers, body) = readHeadersAndBody(input)
        return RtspRequest(parts[0], parts[1], headers, body, parts[2])
    }
}
