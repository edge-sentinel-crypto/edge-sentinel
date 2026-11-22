package com.airbridge.airplay.airplay.raop

import com.airbridge.airplay.airplay.AirPlayException
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AirPlaySender
import com.airbridge.airplay.airplay.AirPlayVolume
import com.airbridge.airplay.airplay.AudioCodec
import com.airbridge.airplay.airplay.AudioFrame
import com.airbridge.airplay.airplay.AuthenticationFailedAirPlayException
import com.airbridge.airplay.airplay.DigestAuth
import com.airbridge.airplay.airplay.PasswordRequiredException
import com.airbridge.airplay.airplay.ProtocolFailureException
import com.airbridge.airplay.airplay.ReceiverBusyException
import com.airbridge.airplay.airplay.RtpPackets
import com.airbridge.airplay.airplay.RtpStreamer
import com.airbridge.airplay.airplay.RtspClient
import com.airbridge.airplay.airplay.RtspResponse
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.SenderStats
import com.airbridge.airplay.airplay.StreamSettings
import com.airbridge.airplay.airplay.StreamTimeline
import com.airbridge.airplay.airplay.SyncOffset
import com.airbridge.airplay.codec.AlacEncoder
import com.airbridge.airplay.crypto.X25519KeyPair
import com.airbridge.airplay.util.AirLog
import org.bouncycastle.crypto.params.RSAKeyParameters
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Per-receiver AirPlay 1 settings derived from its `_raop._tcp` TXT record (or manual entry). */
data class RaopConfig(
    val name: String,
    val host: InetAddress,
    val port: Int,
    val codec: AudioCodec = AudioCodec.ALAC,
    /** RSA/AES payload encryption (et=1 or ek=1 receivers such as early AirPort Express). */
    val encrypt: Boolean = false,
    /** Send POST /auth-setup first (receivers listing MFi `et=4`, e.g. Sonos, AirPort Express gen 3). */
    val authSetup: Boolean = false,
    val password: String? = null,
    val receiverPublicKey: RSAKeyParameters = RaopEncryption.AIRPORT_PUBLIC_KEY,
    val settings: StreamSettings = StreamSettings(),
    val keepAliveSeconds: Long = 25,
)

/**
 * AirPlay 1 (RAOP) sender: RTSP OPTIONS / ANNOUNCE (SDP) / SETUP / RECORD / SET_PARAMETER /
 * FLUSH / TEARDOWN over TCP, digest auth when the receiver is password protected, optional
 * RSA+AES payload encryption, and RTP audio (PT 96) plus the control and timing UDP channels.
 */
class RaopSender(
    private val config: RaopConfig,
    private val identity: SenderIdentity,
    private val random: SecureRandom = SecureRandom(),
) : AirPlaySender {
    override val protocol = AirPlayProtocol.AIRPLAY_1
    override val displayName: String = config.name
    override val stats = SenderStats()

    private var rtsp: RtspClient? = null
    @Volatile private var streamer: RtpStreamer? = null
    @Volatile private var control: ScheduledExecutorService? = null
    private var encryption: RaopEncryption? = null
    private var timeline: StreamTimeline? = null
    private var uri = ""
    private var seq = 0
    private var ssrc = 0L
    private var marker = true
    private var authSetupDone = false

    @Volatile private var closed = false

    @Volatile override var syncOffsetMs: Int = SyncOffset.clamp(config.settings.syncOffsetMs)
        private set
    private val resyncRequested = AtomicBoolean(false)
    private val pendingVolumeDb = AtomicReference<Double?>(null)

    /** Audio-Latency header from RECORD, if the receiver reports one (frames). */
    @Volatile var reportedLatency: Int? = null
        private set

    override fun connect(timeline: StreamTimeline, firstFrameIndex: Long) {
        this.timeline = timeline
        val client = RtspClient(config.host, config.port, identity)
        rtsp = client
        try {
            client.connect()
            val localIp = hostLiteral(client.localAddress)
            val remoteIp = hostLiteral(config.host)
            val sessionId = (random.nextInt() ushr 1).toLong()
            uri = "rtsp://$localIp/$sessionId"
            ssrc = sessionId
            if (config.encrypt) encryption = RaopEncryption(random, config.receiverPublicKey)

            // 1. OPTIONS (with an Apple-Challenge when encrypting, like iTunes).
            val optionsHeaders = HashMap<String, String>()
            if (config.encrypt) {
                val challenge = ByteArray(16).also { random.nextBytes(it) }
                optionsHeaders["Apple-Challenge"] = Base64.getEncoder().withoutPadding().encodeToString(challenge)
            }
            val options = authed("OPTIONS", "*", extraHeaders = optionsHeaders)
            requireSuccess(options, "OPTIONS")
            val supportsPost = options.headers["Public"]?.contains("POST", ignoreCase = true) ?: true

            // 2. Optional auth-setup for MFi receivers that otherwise reject ANNOUNCE with 403.
            if (config.authSetup && supportsPost) doAuthSetup()

            // 3. ANNOUNCE the stream format.
            val sdp = buildSdp(sessionId, localIp, remoteIp, client.localAddress is Inet6Address)
            var announce = authed("ANNOUNCE", uri, sdp.toByteArray(Charsets.UTF_8), "application/sdp")
            if (announce.statusCode == 403 && !authSetupDone && supportsPost) {
                AirLog.i(TAG, "${config.name}: ANNOUNCE got 403, retrying after auth-setup")
                doAuthSetup()
                announce = authed("ANNOUNCE", uri, sdp.toByteArray(Charsets.UTF_8), "application/sdp")
            }
            requireSuccess(announce, "ANNOUNCE")

            // 4. SETUP the UDP channels.
            val s = RtpStreamer(config.host, timeline.clock, config.name, stats)
            s.syncOffsetMs = syncOffsetMs
            streamer = s
            s.startResponders()
            val transport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                "control_port=${s.localControlPort};timing_port=${s.localTimingPort}"
            val setup = authed("SETUP", uri, extraHeaders = mapOf("Transport" to transport))
            requireSuccess(setup, "SETUP")
            client.sessionHeader = setup.headers["Session"]?.substringBefore(';')?.trim()
                ?: throw ProtocolFailureException("${config.name}: SETUP response has no Session header")
            val tp = parseTransport(setup.headers["Transport"] ?: "")
            s.remoteDataPort = tp["server_port"] ?: throw ProtocolFailureException("${config.name}: no server_port in SETUP response")
            s.remoteControlPort = tp["control_port"] ?: 0

            // 5. RECORD from the next frame we will send.
            seq = random.nextInt(0x10000)
            val rtptime = timeline.timestampForFrame(firstFrameIndex)
            val record = authed(
                "RECORD", uri,
                extraHeaders = mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$rtptime"),
            )
            requireSuccess(record, "RECORD")
            reportedLatency = record.headers["Audio-Latency"]?.trim()?.toIntOrNull()
            marker = true

            // 6. Initial volume, then start sync + keep-alive.
            sendVolume(config.settings.initialVolumeDb)
            s.maybeSendSync(timeline, config.settings.syncLatencyFrames, force = true)
            control = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "raop-ctl-${config.name}").apply { isDaemon = true } }
                .also { exec ->
                    exec.scheduleWithFixedDelay({ keepAlive() }, config.keepAliveSeconds, config.keepAliveSeconds, TimeUnit.SECONDS)
                }
            AirLog.i(TAG, "${config.name}: AirPlay 1 stream ready (codec=${config.codec}, encrypted=${config.encrypt}, data port ${s.remoteDataPort})")
        } catch (e: AirPlayException) {
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            throw ProtocolFailureException("${config.name}: AirPlay 1 connection failed: ${e.message}", e)
        }
    }

    private fun doAuthSetup() {
        val c = rtsp ?: return
        val kp = X25519KeyPair.generate(random)
        // 0x01 = "no encryption" flag followed by a Curve25519 public key; the receiver's MFi
        // certificate in the reply is not needed for unencrypted streaming and is ignored.
        val resp = c.request("POST", "/auth-setup", byteArrayOf(0x01) + kp.publicKey, "application/octet-stream")
        authSetupDone = true
        if (!resp.isSuccess) AirLog.w(TAG, "${config.name}: auth-setup answered ${resp.statusCode}, continuing")
    }

    private fun buildSdp(sessionId: Long, localIp: String, remoteIp: String, ipv6: Boolean): String {
        val family = if (ipv6) "IP6" else "IP4"
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=iTunes $sessionId 0 IN $family $localIp\r\n")
        sb.append("s=iTunes\r\n")
        sb.append("c=IN $family $remoteIp\r\n")
        sb.append("t=0 0\r\n")
        sb.append("m=audio 0 RTP/AVP 96\r\n")
        if (config.codec == AudioCodec.ALAC) {
            sb.append("a=rtpmap:96 AppleLossless\r\n")
            sb.append("a=fmtp:96 ${AlacEncoder.SDP_FMTP}\r\n")
        } else {
            sb.append("a=rtpmap:96 L16/44100/2\r\n")
        }
        encryption?.let { sb.append(it.sdpAttributes()) }
        return sb.toString()
    }

    /** Sends a request, answering a digest challenge with the configured password. */
    private fun authed(
        method: String,
        uri: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): RtspResponse {
        val c = rtsp!!
        var resp = c.request(method, uri, body, contentType, extraHeaders)
        if (resp.statusCode != 401) return resp
        val challenge = DigestAuth.parseChallenge(resp.headers["WWW-Authenticate"])
            ?: throw AuthenticationFailedAirPlayException("${config.name}: receiver refused access (401 without digest challenge)")
        val password = config.password ?: throw PasswordRequiredException("${config.name} requires an AirPlay password")
        c.digestChallenge = challenge
        c.digestPassword = password
        c.digestUpperHex = false
        resp = c.request(method, uri, body, contentType, extraHeaders)
        if (resp.statusCode == 401) {
            // Some older receivers compare upper-case hex digests.
            c.digestUpperHex = true
            resp = c.request(method, uri, body, contentType, extraHeaders)
        }
        if (resp.statusCode == 401) throw AuthenticationFailedAirPlayException("${config.name}: wrong AirPlay password")
        return resp
    }

    private fun requireSuccess(resp: RtspResponse, step: String) {
        if (resp.isSuccess) return
        when (resp.statusCode) {
            453 -> throw ReceiverBusyException("${config.name} is busy playing from another sender")
            else -> throw ProtocolFailureException("${config.name}: $step failed with ${resp.statusCode} ${resp.reason}")
        }
    }

    override fun send(frame: AudioFrame) {
        val s = streamer ?: return
        if (closed) return
        val raw = frame.payload(config.codec)
        val payload = encryption?.encryptPayload(raw) ?: raw
        val header = RtpPackets.audioHeader(marker, seq, frame.rtpTimestamp, ssrc)
        val packet = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(payload, 0, packet, header.size, payload.size)
        s.sendAudio(seq, packet)
        seq = (seq + 1) and 0xFFFF
        marker = false
    }

    override fun tick() {
        val s = streamer ?: return
        val t = timeline ?: return
        if (closed) return
        // A sync-offset change asks for an immediate sync packet; it is sent here, on the worker
        // thread that owns the stream, so the session (RTSP, UDP ports, sequence) stays as it is.
        val force = resyncRequested.getAndSet(false)
        if (force) s.syncOffsetMs = syncOffsetMs
        s.maybeSendSync(t, config.settings.syncLatencyFrames, force)
    }

    override fun setSyncOffsetMs(offsetMs: Int) {
        syncOffsetMs = SyncOffset.clamp(offsetMs)
        streamer?.syncOffsetMs = syncOffsetMs
        resyncRequested.set(true)
    }

    /**
     * Asynchronous (never blocks the caller, which may be the UI thread); ignored before connect.
     * Rapid changes (a master-volume drag) are coalesced: at most one SET_PARAMETER is queued and
     * it carries the latest value.
     */
    override fun setVolume(db: Double) {
        val exec = control ?: return
        if (closed) return
        if (pendingVolumeDb.getAndSet(db) != null) return // a queued send will pick up this value
        try {
            exec.execute {
                val latest = pendingVolumeDb.getAndSet(null) ?: return@execute
                try {
                    sendVolume(latest)
                } catch (e: Exception) {
                    AirLog.w(TAG, "${config.name}: volume change failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            pendingVolumeDb.set(null) // the sender is closing
        }
    }

    private fun sendVolume(db: Double) {
        val body = "volume: ${AirPlayVolume.format(db)}\r\n".toByteArray(Charsets.UTF_8)
        val resp = authed("SET_PARAMETER", uri, body, "text/parameters")
        if (!resp.isSuccess) AirLog.w(TAG, "${config.name}: SET_PARAMETER volume answered ${resp.statusCode}")
    }

    private fun keepAlive() {
        if (closed) return
        try {
            authed("OPTIONS", "*")
            stats.controlFailures = 0
        } catch (e: Exception) {
            stats.controlFailures++
            AirLog.w(TAG, "${config.name}: keep-alive failed", e)
        }
    }

    override fun flush(nextFrameIndex: Long) {
        val t = timeline ?: return
        val rtptime = t.timestampForFrame(nextFrameIndex)
        val resp = authed("FLUSH", uri, extraHeaders = mapOf("RTP-Info" to "seq=$seq;rtptime=$rtptime"))
        if (!resp.isSuccess) AirLog.w(TAG, "${config.name}: FLUSH answered ${resp.statusCode}")
        marker = true
        streamer?.resetSync()
        streamer?.retransmitBuffer?.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        control?.shutdownNow()
        val c = rtsp
        if (c != null && !c.closed && c.sessionHeader != null) {
            try {
                c.request("TEARDOWN", uri)
            } catch (e: Exception) {
                AirLog.d(TAG, "${config.name}: TEARDOWN failed: ${e.message}")
            }
        }
        closeQuietly()
    }

    private fun closeQuietly() {
        control?.shutdownNow()
        rtsp?.close()
        streamer?.close()
    }

    companion object {
        private const val TAG = "RAOP"

        fun hostLiteral(addr: InetAddress): String = addr.hostAddress?.substringBefore('%') ?: "0.0.0.0"

        /** Parses `key=value` pairs of an RTSP Transport header into ports. */
        fun parseTransport(header: String): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (part in header.split(';')) {
                val kv = part.split('=', limit = 2)
                if (kv.size == 2) kv[1].trim().substringBefore('-').toIntOrNull()?.let { out[kv[0].trim()] = it }
            }
            return out
        }
    }
}
