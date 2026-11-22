package com.airbridge.airplay.testutil

import com.airbridge.airplay.airplay.DigestAuth
import com.airbridge.airplay.airplay.RtspCodec
import com.airbridge.airplay.airplay.RtspHeaders
import com.airbridge.airplay.airplay.RtspRequest
import com.airbridge.airplay.airplay.RtspResponse
import com.airbridge.airplay.airplay.raop.RaopEncryption
import com.airbridge.airplay.codec.AlacDecoder
import com.airbridge.airplay.codec.PcmL16
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.engines.RSAEngine
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loopback mock AirPlay 1 (RAOP) receiver: a real TCP RTSP server on 127.0.0.1 that checks
 * digest auth, parses the ANNOUNCE SDP (ALAC or L16, optional RSA-wrapped AES key), answers
 * SETUP with real UDP ports ([MockAudioSink]) and records volume, FLUSH and TEARDOWN.
 */
class MockRaopReceiver(
    private val password: String? = null,
    private val rsaKeyPair: AsymmetricCipherKeyPair? = null,
    private val requireAuthSetup: Boolean = false,
) : Closeable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 8, loopback)
    val port: Int get() = server.localPort

    val requests: MutableList<String> = CopyOnWriteArrayList()
    val volumes: MutableList<Double> = CopyOnWriteArrayList()
    val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile var sdp: String? = null
    @Volatile var codec: String? = null
    @Volatile var aesKey: ByteArray? = null
    @Volatile var aesIv: ByteArray? = null
    @Volatile var recordRtpInfo: String? = null
    @Volatile var teardown = false
    @Volatile var flushes = 0
    @Volatile var authSetupSeen = false
    @Volatile var unauthorizedResponses = 0
    @Volatile var sink: MockAudioSink? = null
    @Volatile private var running = true
    private val nonce = "6f2c9a1d77e04b3c"
    private val sockets: MutableList<Socket> = CopyOnWriteArrayList()

    fun start(): MockRaopReceiver {
        Thread({ acceptLoop() }, "mock-raop-accept").apply { isDaemon = true; start() }
        return this
    }

    private fun acceptLoop() {
        while (running) {
            val s = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            sockets += s
            Thread({ handle(s) }, "mock-raop-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (running) {
                val req = RtspCodec.readRequest(input) ?: break
                requests += "${req.method} ${req.uri}"
                val resp = respond(req, socket)
                output.write(resp.serialize())
                output.flush()
                if (req.method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            if (running) errors += "connection error: ${e.message}"
        } finally {
            socket.close()
        }
    }

    private fun ok(req: RtspRequest, extra: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): RtspResponse {
        val h = RtspHeaders()
        req.headers["CSeq"]?.let { h["CSeq"] = it }
        h["Server"] = "AirTunes/366.0"
        extra.forEach { (k, v) -> h[k] = v }
        return RtspResponse("RTSP/1.0", 200, "OK", h, body)
    }

    private fun status(req: RtspRequest, code: Int, reason: String, extra: Map<String, String> = emptyMap()): RtspResponse {
        val h = RtspHeaders()
        req.headers["CSeq"]?.let { h["CSeq"] = it }
        extra.forEach { (k, v) -> h[k] = v }
        return RtspResponse("RTSP/1.0", code, reason, h)
    }

    private fun authorized(req: RtspRequest): Boolean {
        val pw = password ?: return true
        val auth = req.headers["Authorization"] ?: return false
        val params = HashMap<String, String>()
        Regex("(\\w+)=\"([^\"]*)\"").findAll(auth).forEach { params[it.groupValues[1]] = it.groupValues[2] }
        val user = params["username"] ?: return false
        val expected = DigestAuth.response(user, "raop", pw, req.method, params["uri"] ?: req.uri, nonce)
        return params["nonce"] == nonce && params["response"].equals(expected, ignoreCase = true)
    }

    private fun respond(req: RtspRequest, socket: Socket): RtspResponse {
        if (!authorized(req)) {
            unauthorizedResponses++
            return status(req, 401, "Unauthorized", mapOf("WWW-Authenticate" to "Digest realm=\"raop\", nonce=\"$nonce\""))
        }
        return when (req.method) {
            "OPTIONS" -> ok(req, mapOf("Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"))
            "POST" -> if (req.uri == "/auth-setup") {
                authSetupSeen = true
                if (req.body.size != 33 || req.body[0].toInt() != 1) errors += "bad auth-setup body (${req.body.size} bytes)"
                ok(req, mapOf("Content-Type" to "application/octet-stream"), ByteArray(32 + 64) { it.toByte() })
            } else status(req, 404, "Not Found")
            "ANNOUNCE" -> {
                if (requireAuthSetup && !authSetupSeen) return status(req, 403, "Forbidden")
                val text = String(req.body, Charsets.UTF_8)
                sdp = text
                codec = when {
                    text.contains("a=rtpmap:96 AppleLossless") -> "ALAC"
                    text.contains("a=rtpmap:96 L16/44100/2") -> "L16"
                    else -> null
                }
                if (codec == "ALAC" && !text.contains("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100")) errors += "unexpected fmtp"
                val keyLine = text.lines().firstOrNull { it.startsWith("a=rsaaeskey:") }
                val ivLine = text.lines().firstOrNull { it.startsWith("a=aesiv:") }
                if (keyLine != null) {
                    val kp = rsaKeyPair ?: return status(req, 500, "No RSA key")
                    val wrapped = Base64.getDecoder().decode(keyLine.substringAfter(':').trim())
                    val oaep = OAEPEncoding(RSAEngine(), SHA1Digest())
                    oaep.init(false, kp.private)
                    aesKey = oaep.processBlock(wrapped, 0, wrapped.size)
                    aesIv = Base64.getDecoder().decode(ivLine!!.substringAfter(':').trim())
                }
                if (codec == null) status(req, 415, "Unsupported Media Type") else ok(req)
            }
            "SETUP" -> {
                val transport = req.headers["Transport"] ?: return status(req, 400, "Bad Request")
                val ports = HashMap<String, Int>()
                transport.split(';').forEach { part ->
                    val kv = part.split('=')
                    if (kv.size == 2) kv[1].toIntOrNull()?.let { ports[kv[0]] = it }
                }
                val key = aesKey
                val iv = aesIv
                sink?.close()
                val decoder: (ByteArray) -> ShortArray = if (codec == "ALAC") { b -> AlacDecoder.decode(b) } else { b -> PcmL16.decode(b) }
                val s = MockAudioSink(
                    "raop",
                    payloadOf = { packet ->
                        val payload = packet.copyOfRange(12, packet.size)
                        if (key != null) RaopEncryption.decryptPayload(key, iv!!, payload) else payload
                    },
                    decoder = decoder,
                )
                s.clientControlPort = ports["control_port"] ?: 0
                s.clientTimingPort = ports["timing_port"] ?: 0
                if (s.clientControlPort == 0 || s.clientTimingPort == 0) errors += "SETUP without control/timing ports"
                s.start()
                sink = s
                ok(
                    req,
                    mapOf(
                        "Session" to "DEADBEEF;timeout=60",
                        "Transport" to "RTP/AVP/UDP;unicast;mode=record;server_port=${s.dataSocket.localPort};" +
                            "control_port=${s.controlSocket.localPort};timing_port=${s.timingSocket.localPort}",
                    ),
                )
            }
            "RECORD" -> {
                if (req.headers["Session"] != "DEADBEEF") errors += "RECORD without session"
                recordRtpInfo = req.headers["RTP-Info"]
                ok(req, mapOf("Audio-Latency" to "11025"))
            }
            "SET_PARAMETER" -> {
                val body = String(req.body, Charsets.UTF_8)
                Regex("volume:\\s*(-?[0-9.]+)").find(body)?.let { volumes += it.groupValues[1].toDouble() }
                ok(req)
            }
            "FLUSH" -> {
                flushes++
                ok(req)
            }
            "TEARDOWN" -> {
                teardown = true
                ok(req)
            }
            else -> status(req, 501, "Not Implemented")
        }
    }

    override fun close() {
        running = false
        server.close()
        sockets.forEach { runCatching { it.close() } }
        sink?.close()
    }
}
