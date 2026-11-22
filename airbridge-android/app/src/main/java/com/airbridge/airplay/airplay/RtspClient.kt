package com.airbridge.airplay.airplay

import com.airbridge.airplay.util.AirLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale

/** RFC 2617-style digest authentication as used by password-protected AirPlay 1 receivers. */
object DigestAuth {
    data class Challenge(val realm: String, val nonce: String)

    fun parseChallenge(header: String?): Challenge? {
        if (header == null || !header.trim().startsWith("Digest", ignoreCase = true)) return null
        val params = HashMap<String, String>()
        Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"").findAll(header).forEach { params[it.groupValues[1].lowercase(Locale.US)] = it.groupValues[2] }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return Challenge(realm, nonce)
    }

    private fun md5Hex(s: String, upper: Boolean): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val hex = d.joinToString("") { "%02x".format(it) }
        return if (upper) hex.uppercase(Locale.US) else hex
    }

    /** response = MD5(MD5(user:realm:password):nonce:MD5(method:uri)). */
    fun response(user: String, realm: String, password: String, method: String, uri: String, nonce: String, upperHex: Boolean = false): String {
        val ha1 = md5Hex("$user:$realm:$password", upperHex)
        val ha2 = md5Hex("$method:$uri", upperHex)
        return md5Hex("$ha1:$nonce:$ha2", upperHex)
    }

    fun authorizationHeader(user: String, challenge: Challenge, password: String, method: String, uri: String, upperHex: Boolean = false): String {
        val resp = response(user, challenge.realm, password, method, uri, challenge.nonce, upperHex)
        return "Digest username=\"$user\", realm=\"${challenge.realm}\", nonce=\"${challenge.nonce}\", uri=\"$uri\", response=\"$resp\""
    }
}

/**
 * Blocking RTSP client for the AirPlay control connection. One request is in flight at a time
 * (calls are serialized); responses are matched in order. After HAP pairing the connection is
 * switched to the encrypted framing with [enableEncryption].
 */
class RtspClient(
    private val host: InetAddress,
    private val port: Int,
    private val identity: SenderIdentity,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : Closeable {
    private val socket = Socket()
    private lateinit var rawIn: InputStream
    private lateinit var rawOut: OutputStream
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var cseq = 0

    @Volatile
    var closed = false
        private set

    /** Set after a 401 challenge; every later request carries an Authorization header. */
    var digestChallenge: DigestAuth.Challenge? = null
    var digestPassword: String? = null
    var digestUpperHex: Boolean = false
    var digestUser: String = "iTunes"

    /** RTSP Session id (AirPlay 1) added to requests once known. */
    var sessionHeader: String? = null

    var encrypted = false
        private set

    val localAddress: InetAddress get() = socket.localAddress
    val remoteAddress: InetAddress get() = host

    fun connect() {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs
        rawIn = BufferedInputStream(socket.getInputStream(), 16 * 1024)
        rawOut = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
        input = rawIn
        output = rawOut
    }

    /** Switches both directions to HAP ChaCha20-Poly1305 framing. */
    @Synchronized
    fun enableEncryption(writeKey: ByteArray, readKey: ByteArray) {
        output = HapSecureOutputStream(rawOut, writeKey)
        input = BufferedInputStream(HapSecureInputStream(rawIn, readKey), 16 * 1024)
        encrypted = true
    }

    @Synchronized
    fun request(
        method: String,
        uri: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = "RTSP/1.0",
    ): RtspResponse {
        if (closed) throw IOException("RTSP connection closed")
        val headers = RtspHeaders()
        headers["CSeq"] = (++cseq).toString()
        headers["User-Agent"] = identity.userAgent
        headers["DACP-ID"] = identity.dacpId
        headers["Active-Remote"] = identity.activeRemote
        headers["Client-Instance"] = identity.dacpId
        sessionHeader?.let { headers["Session"] = it }
        val challenge = digestChallenge
        val password = digestPassword
        if (challenge != null && password != null) {
            headers["Authorization"] = DigestAuth.authorizationHeader(digestUser, challenge, password, method, uri, digestUpperHex)
        }
        if (contentType != null) headers["Content-Type"] = contentType
        for ((k, v) in extraHeaders) headers[k] = v
        val req = RtspRequest(method, uri, headers, body, protocol)
        AirLog.d(TAG, "-> $method $uri (${body.size} bytes)")
        output.write(req.serialize())
        output.flush()
        val resp = RtspCodec.readResponse(input) ?: throw IOException("connection closed by receiver during $method")
        AirLog.d(TAG, "<- ${resp.statusCode} ${resp.reason} for $method $uri (${resp.body.size} bytes)")
        return resp
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "RTSP"
    }
}

/** How AirBridge identifies itself on the wire (stable per install, no personal data). */
data class SenderIdentity(
    val dacpId: String,
    val activeRemote: String,
    val deviceId: String,
    val name: String = "AirBridge",
    val userAgent: String = "AirPlay/550.10",
) {
    companion object {
        fun random(name: String = "AirBridge", random: java.util.Random = java.security.SecureRandom()): SenderIdentity {
            val dacp = (0 until 8).joinToString("") { "%02X".format(random.nextInt(256)) }
            val active = (random.nextInt() ushr 1).toString()
            // Locally administered unicast MAC-style id (bit 1 of first octet set, bit 0 clear).
            val mac = ByteArray(6).also { random.nextBytes(it) }
            mac[0] = ((mac[0].toInt() and 0xFC) or 0x02).toByte()
            val deviceId = mac.joinToString(":") { "%02X".format(it) }
            return SenderIdentity(dacp, active, deviceId, name)
        }
    }
}
