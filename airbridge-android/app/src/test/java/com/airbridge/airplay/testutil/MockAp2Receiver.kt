package com.airbridge.airplay.testutil

import com.airbridge.airplay.airplay.HapSecureInputStream
import com.airbridge.airplay.airplay.HapSecureOutputStream
import com.airbridge.airplay.airplay.RtspCodec
import com.airbridge.airplay.airplay.RtspHeaders
import com.airbridge.airplay.airplay.RtspRequest
import com.airbridge.airplay.airplay.RtspResponse
import com.airbridge.airplay.codec.AlacDecoder
import com.airbridge.airplay.codec.BinaryPlist
import com.airbridge.airplay.codec.PcmL16
import com.airbridge.airplay.codec.Tlv8
import com.airbridge.airplay.codec.Tlv8.Type
import com.airbridge.airplay.crypto.Ed25519KeyPair
import com.airbridge.airplay.crypto.HapCrypto
import com.airbridge.airplay.crypto.SrpGroup
import com.airbridge.airplay.crypto.X25519KeyPair
import com.airbridge.airplay.util.ByteUtil
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loopback mock AirPlay 2 receiver implementing the receiver side of everything AirBridge does:
 * GET /info (bplist), HAP pair-setup (transient flag 0x10 with PIN 3939, or PIN/password M1..M6 with
 * Ed25519 long-term keys), pair-pin-start, pair-verify (X25519 + Ed25519), the ChaCha20-Poly1305
 * encrypted RTSP channel, both SETUP phases, RECORD, SET_PARAMETER, POST /feedback, FLUSH,
 * TEARDOWN, the encrypted event channel, and decryption + decoding of the realtime RTP audio.
 */
class MockAp2Receiver(
    private val statusFlags: Long = 0,
    private val transientPin: String = "3939",
    /** Code "shown on screen" after pair-pin-start (PIN pairing). */
    private val screenPin: String? = null,
    /** Password for receivers set to "require password". */
    private val password: String? = null,
    /** Answer transient pair-setup with HTTP 470 (receiver insists on PIN pairing). */
    private val reject470ForTransient: Boolean = false,
    /** Refuse the NTP session SETUP (what a PTP-only AirPlay 2 receiver such as Shairport-Sync does). */
    private val rejectNtpSetup: Boolean = false,
    /**
     * Strict latency checking: the stream SETUP must declare integer `latencyMin` <= `latencyMax`
     * (else 400), and every sync packet must stay inside that window, both as announced and as the
     * total with the receiver's 11,025 frames; any packet outside it is recorded in [errors].
     */
    private val strictLatencyWindow: Boolean = false,
) : Closeable {
    private val loopback = InetAddress.getLoopbackAddress()
    @Volatile private var server = ServerSocket(0, 8, loopback)
    val port: Int get() = server.localPort

    /** Latency window from the last stream SETUP (frames). */
    @Volatile var latencyWindow: LongRange? = null

    /** Every sync packet checked in strict mode, in arrival order. */
    val syncChecks: MutableList<SyncLatency> = CopyOnWriteArrayList()

    val accessoryId = "4A:42:52:49:44:47"
    private val accessoryKey = Ed25519KeyPair.generate()
    private val group = SrpGroup.RFC5054_3072_SHA512
    val pairedControllers = ConcurrentHashMap<String, ByteArray>()

    val requests: MutableList<String> = CopyOnWriteArrayList()
    val encryptedRequests: MutableList<String> = CopyOnWriteArrayList()
    val volumes: MutableList<Double> = CopyOnWriteArrayList()
    val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile var pinStarts = 0
    @Volatile var transientSetups = 0
    @Volatile var fullSetups = 0
    @Volatile var verifies = 0
    @Volatile var feedbacks = 0
    @Volatile var flushes = 0
    @Volatile var teardown = false
    @Volatile var sessionSetup: Map<String, Any?>? = null
    @Volatile var streamSetup: Map<String, Any?>? = null
    @Volatile var eventAcknowledged = false
    @Volatile var sink: MockAudioSink? = null
    @Volatile private var running = true
    private val sockets: MutableList<Socket> = CopyOnWriteArrayList()
    private var eventServer: ServerSocket? = null

    fun start(): MockAp2Receiver {
        val listening = server
        Thread({ acceptLoop(listening) }, "mock-ap2-accept").apply { isDaemon = true; start() }
        return this
    }

    private fun acceptLoop(listening: ServerSocket) {
        while (running) {
            val s = try {
                listening.accept()
            } catch (_: Exception) {
                break
            }
            sockets += s
            Thread({ Connection(s).run() }, "mock-ap2-conn").apply { isDaemon = true; start() }
        }
    }

    /**
     * The same receiver (same keys and pairings) moves to a new address: the old listening port
     * and every open connection close, and it listens on a new port. Returns the new port.
     */
    fun moveToNewPort(): Int {
        val old = server
        val fresh = ServerSocket(0, 8, loopback)
        server = fresh
        old.close()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        eventServer?.close()
        Thread({ acceptLoop(fresh) }, "mock-ap2-accept").apply { isDaemon = true; start() }
        return fresh.localPort
    }

    /** Per-connection pairing state. */
    private inner class Connection(private val socket: Socket) {
        private val rawIn: InputStream = BufferedInputStream(socket.getInputStream())
        private val rawOut: OutputStream = socket.getOutputStream()
        private var input: InputStream = rawIn
        private var output: OutputStream = rawOut
        private var encrypted = false
        private var secret: ByteArray? = null
        private var srp: SrpServer? = null
        private var transient = false
        private var verifyEph: X25519KeyPair? = null
        private var verifyClientEph: ByteArray? = null
        private var verifyShared: ByteArray? = null

        fun run() {
            try {
                while (running) {
                    val req = RtspCodec.readRequest(input) ?: break
                    requests += "${req.method} ${req.uri}"
                    if (encrypted) encryptedRequests += "${req.method} ${req.uri}"
                    val (resp, enableWith) = respond(req)
                    output.write(resp.serialize())
                    output.flush()
                    if (enableWith != null) {
                        // Switch to HAP framing right after the last plaintext pairing response.
                        input = BufferedInputStream(HapSecureInputStream(rawIn, HapCrypto.hkdfSha512(enableWith, "Control-Salt", "Control-Write-Encryption-Key")))
                        output = HapSecureOutputStream(rawOut, HapCrypto.hkdfSha512(enableWith, "Control-Salt", "Control-Read-Encryption-Key"))
                        encrypted = true
                        secret = enableWith
                    }
                    if (req.method == "TEARDOWN") break
                }
            } catch (e: Exception) {
                if (running && !socket.isClosed) errors += "connection error: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                socket.close()
            }
        }

        private fun respond(req: RtspRequest): Pair<RtspResponse, ByteArray?> {
            val key = "${req.method} ${req.uri.substringBefore('?')}"
            if (!encrypted && req.method != "GET" && req.method != "POST") {
                errors += "plaintext ${req.method} before pairing"
                return status(req, 403) to null
            }
            return when {
                key == "GET /info" -> ok(req, BinaryPlist.encode(infoPlist()), BPLIST) to null
                key == "POST /pair-pin-start" -> {
                    pinStarts++
                    ok(req) to null
                }
                key == "POST /pair-setup" -> pairSetup(req)
                key == "POST /pair-verify" -> pairVerify(req)
                !encrypted -> {
                    errors += "plaintext $key after pairing should be encrypted"
                    status(req, 403) to null
                }
                key == "POST /feedback" -> {
                    feedbacks++
                    ok(req) to null
                }
                req.method == "SETUP" -> setup(req) to null
                req.method == "RECORD" -> ok(req) to null
                req.method == "SET_PARAMETER" -> {
                    Regex("volume:\\s*(-?[0-9.]+)").find(String(req.body, Charsets.UTF_8))?.let { volumes += it.groupValues[1].toDouble() }
                    ok(req) to null
                }
                req.method == "FLUSH" -> {
                    flushes++
                    ok(req) to null
                }
                req.method == "TEARDOWN" -> {
                    teardown = true
                    ok(req) to null
                }
                else -> status(req, 501) to null
            }
        }

        private fun pairSetup(req: RtspRequest): Pair<RtspResponse, ByteArray?> {
            val tlv = Tlv8.decode(req.body)
            val state = tlv[Type.STATE]?.get(0)?.toInt()
            when (state) {
                1 -> {
                    transient = tlv[Type.FLAGS]?.get(0)?.toInt() == 0x10
                    if (req.headers["X-Apple-HKP"] != (if (transient) "4" else "3")) errors += "wrong X-Apple-HKP for state 1"
                    if (transient && reject470ForTransient) return status(req, 470) to null
                    val pin = when {
                        transient -> transientPin
                        statusFlags and FLAG_PIN != 0L -> screenPin ?: return tlvError(2, 2) to null
                        statusFlags and FLAG_PASSWORD != 0L -> password ?: return tlvError(2, 2) to null
                        else -> screenPin ?: transientPin
                    }
                    if (!transient && statusFlags and FLAG_PIN != 0L && pinStarts == 0) errors += "PIN pair-setup without pair-pin-start"
                    val s = SrpServer(group, "Pair-Setup", pin.toByteArray())
                    srp = s
                    return okTlv(req, Type.STATE to Tlv8.byte(2), Type.SALT to s.salt, Type.PUBLIC_KEY to s.publicKeyBytes) to null
                }
                3 -> {
                    val s = srp ?: return tlvError(4, 1) to null
                    val a = tlv[Type.PUBLIC_KEY] ?: return tlvError(4, 1) to null
                    val m1 = tlv[Type.PROOF] ?: return tlvError(4, 1) to null
                    if (a.size != 384) errors += "client A not 384 bytes (${a.size})"
                    val m2 = s.verifyClient(a, m1) ?: return tlvError(4, Tlv8.Error.AUTHENTICATION) to null
                    return if (transient) {
                        transientSetups++
                        okTlv(req, Type.STATE to Tlv8.byte(4), Type.PROOF to m2) to s.sessionKey!!
                    } else {
                        okTlv(req, Type.STATE to Tlv8.byte(4), Type.PROOF to m2) to null
                    }
                }
                5 -> {
                    val k = srp?.sessionKey ?: return tlvError(6, 1) to null
                    val encKey = HapCrypto.hkdfSha512(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
                    val sub = Tlv8.decode(HapCrypto.open(encKey, HapCrypto.labelNonce("PS-Msg05"), tlv[Type.ENCRYPTED_DATA]!!))
                    val id = sub[Type.IDENTIFIER]!!
                    val ltpk = sub[Type.PUBLIC_KEY]!!
                    val x = HapCrypto.hkdfSha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
                    if (!Ed25519KeyPair.verify(ltpk, ByteUtil.concat(x, id, ltpk), sub[Type.SIGNATURE]!!)) {
                        return tlvError(6, Tlv8.Error.AUTHENTICATION) to null
                    }
                    pairedControllers[String(id, Charsets.UTF_8)] = ltpk
                    fullSetups++
                    val accId = accessoryId.toByteArray()
                    val ax = HapCrypto.hkdfSha512(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
                    val sig = accessoryKey.sign(ByteUtil.concat(ax, accId, accessoryKey.publicKey))
                    val m6 = Tlv8.encode(Type.IDENTIFIER to accId, Type.PUBLIC_KEY to accessoryKey.publicKey, Type.SIGNATURE to sig)
                    return okTlv(req, Type.STATE to Tlv8.byte(6), Type.ENCRYPTED_DATA to HapCrypto.seal(encKey, HapCrypto.labelNonce("PS-Msg06"), m6)) to null
                }
                else -> return tlvError((state ?: 0) + 1, 1) to null
            }
        }

        private fun pairVerify(req: RtspRequest): Pair<RtspResponse, ByteArray?> {
            val tlv = Tlv8.decode(req.body)
            when (tlv[Type.STATE]?.get(0)?.toInt()) {
                1 -> {
                    val clientEph = tlv[Type.PUBLIC_KEY]!!
                    val eph = X25519KeyPair.generate()
                    val shared = eph.agree(clientEph)
                    verifyEph = eph
                    verifyClientEph = clientEph
                    verifyShared = shared
                    val key = HapCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
                    val accId = accessoryId.toByteArray()
                    val sig = accessoryKey.sign(ByteUtil.concat(eph.publicKey, accId, clientEph))
                    val sub = Tlv8.encode(Type.IDENTIFIER to accId, Type.SIGNATURE to sig)
                    return okTlv(
                        req,
                        Type.STATE to Tlv8.byte(2),
                        Type.PUBLIC_KEY to eph.publicKey,
                        Type.ENCRYPTED_DATA to HapCrypto.seal(key, HapCrypto.labelNonce("PV-Msg02"), sub),
                    ) to null
                }
                3 -> {
                    val shared = verifyShared ?: return tlvError(4, 1) to null
                    val key = HapCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
                    val sub = Tlv8.decode(HapCrypto.open(key, HapCrypto.labelNonce("PV-Msg03"), tlv[Type.ENCRYPTED_DATA]!!))
                    val id = String(sub[Type.IDENTIFIER]!!, Charsets.UTF_8)
                    val ltpk = pairedControllers[id] ?: return tlvError(4, Tlv8.Error.AUTHENTICATION) to null
                    val ok = Ed25519KeyPair.verify(ltpk, ByteUtil.concat(verifyClientEph!!, sub[Type.IDENTIFIER]!!, verifyEph!!.publicKey), sub[Type.SIGNATURE]!!)
                    if (!ok) return tlvError(4, Tlv8.Error.AUTHENTICATION) to null
                    verifies++
                    return okTlv(req, Type.STATE to Tlv8.byte(4)) to shared
                }
                else -> return tlvError(2, 1) to null
            }
        }

        private fun setup(req: RtspRequest): RtspResponse {
            val plist = BinaryPlist.decodeDict(req.body)
            val streams = plist["streams"] as? List<*>
            if (streams == null) {
                sessionSetup = plist
                if (rejectNtpSetup) return status(req, 500)
                if (plist["timingProtocol"] != "NTP") errors += "timingProtocol is ${plist["timingProtocol"]}"
                val timingPort = (plist["timingPort"] as? Long)?.toInt() ?: 0
                // Fresh UDP endpoints for every session (a reconnect gets a clean sink).
                sink?.close()
                val s = MockAudioSink("ap2", payloadOf = { p -> openAudioPacket(p) }, decoder = { ShortArray(0) })
                sink = s
                s.clientTimingPort = timingPort
                val events = ServerSocket(0, 1, loopback)
                eventServer = events
                Thread({ eventLoop(events, secret!!) }, "mock-ap2-events").apply { isDaemon = true; start() }
                return ok(req, BinaryPlist.encode(mapOf("eventPort" to events.localPort.toLong(), "timingPort" to s.timingSocket.localPort.toLong())), BPLIST)
            }
            val stream = streams.first() as Map<*, *>
            streamSetup = stream.mapKeys { it.key.toString() }
            val latencyMin = stream["latencyMin"] as? Long
            val latencyMax = stream["latencyMax"] as? Long
            latencyWindow = if (latencyMin != null && latencyMax != null) latencyMin..latencyMax else null
            if (strictLatencyWindow) {
                if (latencyMin == null || latencyMax == null || latencyMin < 0 || latencyMin > latencyMax) {
                    errors += "stream SETUP latency window is not valid: latencyMin=$latencyMin latencyMax=$latencyMax"
                    return status(req, 400)
                }
            }
            val shk = stream["shk"] as ByteArray
            if (shk.size != 32) errors += "shk is ${shk.size} bytes"
            if (stream["type"] != 96L) errors += "stream type ${stream["type"]}"
            if (stream["spf"] != 352L) errors += "spf ${stream["spf"]}"
            val ct = stream["ct"] as Long
            val s = sink!!
            s.clientControlPort = (stream["controlPort"] as Long).toInt()
            s.decoder = if (ct == 2L) { b -> AlacDecoder.decode(b) } else { b -> PcmL16.decode(b) }
            payloadKey = shk
            if (strictLatencyWindow) {
                val min = latencyMin!!
                val max = latencyMax!!
                s.onSyncPacket = { packet ->
                    val check = SyncLatency.of(packet, min, max)
                    syncChecks += check
                    if (!check.inside) errors += "outside the SETUP latency window: $check"
                }
            }
            s.start()
            return ok(
                req,
                BinaryPlist.encode(
                    mapOf(
                        "streams" to listOf(
                            mapOf("type" to 96L, "dataPort" to s.dataSocket.localPort.toLong(), "controlPort" to s.controlSocket.localPort.toLong()),
                        ),
                    ),
                ),
                BPLIST,
            )
        }
    }

    /** Audio key from the stream SETUP; used to open each RTP payload. */
    @Volatile private var payloadKey: ByteArray? = null

    /** Opens one realtime packet: AAD = RTP timestamp+SSRC, nonce = 4 zero bytes + trailing 8 bytes. */
    fun openAudioPacket(packet: ByteArray): ByteArray {
        val key = payloadKey ?: throw IllegalStateException("no shk yet")
        val nonce = ByteArray(4) + packet.copyOfRange(packet.size - 8, packet.size)
        val aad = packet.copyOfRange(4, 12)
        return HapCrypto.open(key, nonce, packet, 12, packet.size - 12 - 8, aad)
    }

    private fun eventLoop(events: ServerSocket, secret: ByteArray) {
        try {
            events.accept().use { s ->
                // The receiver initiates requests on this channel: it writes with the "Write" key.
                val out = HapSecureOutputStream(s.getOutputStream(), HapCrypto.hkdfSha512(secret, "Events-Salt", "Events-Write-Encryption-Key"))
                val inp = BufferedInputStream(HapSecureInputStream(s.getInputStream(), HapCrypto.hkdfSha512(secret, "Events-Salt", "Events-Read-Encryption-Key")))
                val h = RtspHeaders()
                h["CSeq"] = "1"
                out.write(RtspRequest("POST", "/command", h, ByteArray(0)).serialize())
                out.flush()
                val resp = RtspCodec.readResponse(inp)
                eventAcknowledged = resp?.statusCode == 200
            }
        } catch (e: Exception) {
            if (running && !events.isClosed) errors += "event channel: ${e.message}"
        }
    }

    private fun infoPlist(): Map<String, Any?> = mapOf(
        "deviceID" to accessoryId,
        "features" to 0x1C340445F8A00L,
        "statusFlags" to statusFlags,
        "name" to "Mock HomePod",
        "model" to "AudioAccessory5,1",
        "pk" to accessoryKey.publicKey,
        "sourceVersion" to "770.8.1",
    )

    private fun ok(req: RtspRequest, body: ByteArray = ByteArray(0), contentType: String? = null): RtspResponse {
        val h = RtspHeaders()
        req.headers["CSeq"]?.let { h["CSeq"] = it }
        h["Server"] = "AirTunes/770.8.1"
        if (contentType != null) h["Content-Type"] = contentType
        return RtspResponse(req.protocol, 200, "OK", h, body)
    }

    private fun okTlv(req: RtspRequest, vararg items: Pair<Int, ByteArray>) = ok(req, Tlv8.encode(*items), "application/octet-stream")

    private fun status(req: RtspRequest, code: Int): RtspResponse {
        val h = RtspHeaders()
        req.headers["CSeq"]?.let { h["CSeq"] = it }
        return RtspResponse(req.protocol, code, if (code == 470) "Connection Authorization Required" else "Error", h)
    }

    private fun tlvError(state: Int, code: Int): RtspResponse {
        val h = RtspHeaders()
        h["Content-Type"] = "application/octet-stream"
        return RtspResponse("RTSP/1.0", 200, "OK", h, Tlv8.encode(Type.STATE to Tlv8.byte(state), Type.ERROR to Tlv8.byte(code)))
    }

    override fun close() {
        running = false
        server.close()
        eventServer?.close()
        sockets.forEach { runCatching { it.close() } }
        sink?.close()
    }

    companion object {
        const val BPLIST = "application/x-apple-binary-plist"
        const val FLAG_PIN = 1L shl 3
        const val FLAG_PASSWORD = 1L shl 7
        const val FLAG_ONE_TIME = 1L shl 9
    }
}
