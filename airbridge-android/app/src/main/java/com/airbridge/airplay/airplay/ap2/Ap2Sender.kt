package com.airbridge.airplay.airplay.ap2

import com.airbridge.airplay.airplay.AirPlayException
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AirPlaySender
import com.airbridge.airplay.airplay.AirPlayVolume
import com.airbridge.airplay.airplay.AudioCodec
import com.airbridge.airplay.airplay.AudioFrame
import com.airbridge.airplay.airplay.AuthenticationFailedAirPlayException
import com.airbridge.airplay.airplay.PasswordRequiredException
import com.airbridge.airplay.airplay.PinRequiredException
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
import com.airbridge.airplay.airplay.raop.RaopSender
import com.airbridge.airplay.codec.AlacEncoder
import com.airbridge.airplay.codec.BinaryPlist
import com.airbridge.airplay.codec.PcmL16
import com.airbridge.airplay.crypto.HapCrypto
import com.airbridge.airplay.util.AirLog
import java.net.InetAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Where AirBridge keeps its HAP identity and PIN/password pairings (Android: private prefs). */
interface PairingStore {
    fun controllerIdentity(): ControllerIdentity
    fun load(receiverKey: String): AccessoryPairing?
    fun save(receiverKey: String, pairing: AccessoryPairing)
    fun remove(receiverKey: String)
}

class InMemoryPairingStore(private val identity: ControllerIdentity = ControllerIdentity.generate()) : PairingStore {
    private val pairings = ConcurrentHashMap<String, AccessoryPairing>()
    override fun controllerIdentity() = identity
    override fun load(receiverKey: String) = pairings[receiverKey]
    override fun save(receiverKey: String, pairing: AccessoryPairing) {
        pairings[receiverKey] = pairing
    }
    override fun remove(receiverKey: String) {
        pairings.remove(receiverKey)
    }
}

/** Per-receiver AirPlay 2 settings. */
data class Ap2Config(
    val name: String,
    val host: InetAddress,
    val port: Int,
    /** Stable key for stored pairings (the receiver's device id). */
    val receiverKey: String,
    val codec: AudioCodec = AudioCodec.ALAC,
    /** 4-digit code shown by the receiver after a PIN request. */
    val pin: String? = null,
    /** Receiver password (AirPlay 2 "require password"). */
    val password: String? = null,
    /** Status flags from the mDNS TXT record, used if GET /info is unavailable. */
    val statusFlagsHint: Long = 0,
    val settings: StreamSettings = StreamSettings(),
    val feedbackIntervalMs: Long = 2_000,
    val connectEventChannel: Boolean = true,
)

/**
 * AirPlay 2 sender (HAP pairing + "realtime" audio stream, type 96):
 *
 *  GET /info -> pair-setup (transient 3939, or PIN/password + pair-verify) -> encrypted RTSP ->
 *  SETUP #1 (session: NTP timing port, event port) -> RECORD -> SETUP #2 (audio stream with the
 *  32-byte `shk` key) -> SET_PARAMETER volume -> POST /feedback every 2 s; audio is RTP whose
 *  payload is ChaCha20-Poly1305 sealed (AAD = RTP timestamp + SSRC, 8-byte nonce appended).
 */
class Ap2Sender(
    private val config: Ap2Config,
    private val identity: SenderIdentity,
    private val pairingStore: PairingStore,
    private val random: SecureRandom = SecureRandom(),
) : AirPlaySender {
    override val protocol = AirPlayProtocol.AIRPLAY_2
    override val displayName: String = config.name
    override val stats = SenderStats()

    private var rtsp: RtspClient? = null
    @Volatile private var streamer: RtpStreamer? = null
    private var events: EventChannel? = null
    @Volatile private var control: ScheduledExecutorService? = null
    private var timeline: StreamTimeline? = null
    private var audioKey: ByteArray? = null
    private var uri = ""
    private var seq = 0
    private var ssrc = 0L
    private var marker = true
    private var nonceCounter = 0L
    private var sessionSetUp = false

    @Volatile private var closed = false

    @Volatile override var syncOffsetMs: Int = SyncOffset.clamp(config.settings.syncOffsetMs)
        private set
    private val resyncRequested = AtomicBoolean(false)
    private val pendingVolumeDb = AtomicReference<Double?>(null)

    /** Parsed GET /info dictionary (empty when the receiver does not answer it). */
    @Volatile var info: Map<String, Any?> = emptyMap()
        private set

    /** How pairing was done for this session (for diagnostics). */
    @Volatile var pairingMode: String = "none"
        private set

    override fun connect(timeline: StreamTimeline, firstFrameIndex: Long) {
        this.timeline = timeline
        val client = RtspClient(config.host, config.port, identity)
        rtsp = client
        try {
            client.connect()
            val localIp = RaopSender.hostLiteral(client.localAddress)
            val sessionId = (random.nextInt() ushr 1).toLong()
            uri = "rtsp://$localIp/$sessionId"
            ssrc = sessionId

            // 1. Receiver info (status flags decide how to pair).
            info = fetchInfo(client)
            val flags = (info["statusFlags"] as? Long) ?: config.statusFlagsHint

            // 2. Pair and switch the control connection to encrypted framing.
            val result = pair(client, flags)
            client.enableEncryption(result.controlWriteKey(), result.controlReadKey())
            audioKey = result.audioKey()

            // 3. Session SETUP (timing + event channel).
            val s = RtpStreamer(config.host, timeline.clock, config.name, stats)
            s.syncOffsetMs = syncOffsetMs
            streamer = s
            s.startResponders()
            val sessionBody = linkedMapOf<String, Any?>(
                "deviceID" to identity.deviceId,
                "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                "timingPort" to s.localTimingPort.toLong(),
                "timingProtocol" to "NTP",
                "isMultiSelectAirPlay" to true,
                "groupContainsGroupLeader" to false,
                "macAddress" to identity.deviceId,
                "model" to "Android",
                "name" to identity.name,
                "osName" to "Android",
                "senderSupportsRelay" to false,
                "sourceVersion" to "550.10",
                "statsCollectionEnabled" to false,
            )
            val setup1 = client.request("SETUP", uri, BinaryPlist.encode(sessionBody), BPLIST)
            requireSuccess(setup1, "SETUP (session)")
            sessionSetUp = true
            val setup1Dict = decodeDict(setup1)
            val eventPort = (setup1Dict["eventPort"] as? Long)?.toInt() ?: 0
            if (config.connectEventChannel && eventPort > 0) {
                try {
                    events = EventChannel(config.host, eventPort, result.eventWriteKey(), result.eventReadKey(), config.name).also { it.start() }
                } catch (e: Exception) {
                    AirLog.w(TAG, "${config.name}: event channel unavailable (${e.message}); continuing")
                }
            }

            // 4. RECORD (iOS sends it between the two SETUPs).
            val record = client.request("RECORD", uri)
            requireSuccess(record, "RECORD")

            // 5. Stream SETUP: realtime ALAC (or L16) with the audio key.
            val alac = config.codec == AudioCodec.ALAC
            val stream = linkedMapOf<String, Any?>(
                "audioFormat" to if (alac) AP2_FORMAT_ALAC_44100_16_2 else PcmL16.AP2_AUDIO_FORMAT_PCM_44100_16_2,
                "audioMode" to "default",
                "controlPort" to s.localControlPort.toLong(),
                "ct" to if (alac) 2L else 1L,
                "isMedia" to true,
                "latencyMax" to config.settings.ap2LatencyMax.toLong(),
                "latencyMin" to config.settings.ap2LatencyMin.toLong(),
                "shk" to audioKey!!,
                "spf" to AlacEncoder.FRAMES_PER_PACKET.toLong(),
                "sr" to AlacEncoder.SAMPLE_RATE.toLong(),
                "type" to 96L,
                "supportsDynamicStreamID" to false,
                "streamConnectionID" to sessionId,
            )
            val setup2 = client.request("SETUP", uri, BinaryPlist.encode(mapOf("streams" to listOf(stream))), BPLIST)
            requireSuccess(setup2, "SETUP (stream)")
            val streams = decodeDict(setup2)["streams"] as? List<*>
            val first = streams?.firstOrNull() as? Map<*, *> ?: throw ProtocolFailureException("${config.name}: stream SETUP response has no streams")
            s.remoteDataPort = (first["dataPort"] as? Long)?.toInt()
                ?: throw ProtocolFailureException("${config.name}: stream SETUP response has no dataPort")
            s.remoteControlPort = (first["controlPort"] as? Long)?.toInt() ?: 0

            // 6. Volume last (some receivers ignore it earlier), then sync and keep-alive.
            seq = random.nextInt(0x10000)
            marker = true
            sendVolume(config.settings.initialVolumeDb)
            s.maybeSendSync(timeline, config.settings.syncLatencyFrames, force = true)
            control = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ap2-ctl-${config.name}").apply { isDaemon = true } }
                .also { exec ->
                    exec.scheduleWithFixedDelay({ feedback() }, config.feedbackIntervalMs, config.feedbackIntervalMs, TimeUnit.MILLISECONDS)
                }
            AirLog.i(TAG, "${config.name}: AirPlay 2 stream ready (pairing=$pairingMode, codec=${config.codec}, data port ${s.remoteDataPort})")
        } catch (e: AirPlayException) {
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            throw ProtocolFailureException("${config.name}: AirPlay 2 connection failed: ${e.message}", e)
        }
    }

    private fun fetchInfo(client: RtspClient): Map<String, Any?> {
        return try {
            val resp = client.request("GET", "/info")
            if (resp.isSuccess && BinaryPlist.isBinaryPlist(resp.body)) BinaryPlist.decodeDict(resp.body) else emptyMap()
        } catch (e: BinaryPlist.PlistFormatException) {
            AirLog.w(TAG, "${config.name}: unreadable /info plist")
            emptyMap()
        }
    }

    private fun pair(client: RtspClient, statusFlags: Long): PairingResult {
        val pairing = HapPairing(client, pairingStore.controllerIdentity(), random)
        val stored = pairingStore.load(config.receiverKey)
        if (stored != null) {
            try {
                pairingMode = "pair-verify (saved pairing)"
                return pairing.pairVerify(stored)
            } catch (e: AuthenticationFailedAirPlayException) {
                // The receiver was reset or forgot us: drop the pairing; the next attempt re-pairs.
                pairingStore.remove(config.receiverKey)
                throw AuthenticationFailedAirPlayException("${config.name} no longer accepts the saved pairing; try again to re-pair")
            }
        }
        val needsPin = statusFlags and (FLAG_PIN_REQUIRED or FLAG_ONE_TIME_PAIRING_REQUIRED) != 0L
        val needsPassword = statusFlags and FLAG_PASSWORD_REQUIRED != 0L
        return when {
            needsPin -> pinPairing(pairing)
            needsPassword -> {
                val password = config.password ?: throw PasswordRequiredException("${config.name} requires its AirPlay password")
                pairingMode = "password pair-setup + pair-verify"
                val acc = pairing.pairSetupWithPin(password)
                pairingStore.save(config.receiverKey, acc)
                pairing.pairVerify(acc)
            }
            else -> try {
                pairingMode = "transient pair-setup"
                pairing.pairSetupTransient(config.password ?: HapPairing.TRANSIENT_PIN)
            } catch (e: PinRequiredException) {
                // HTTP 470: this receiver wants on-screen PIN pairing instead.
                pinPairing(pairing)
            }
        }
    }

    private fun pinPairing(pairing: HapPairing): PairingResult {
        val pin = config.pin
        if (pin == null) {
            pairing.pinStart()
            throw PinRequiredException("${config.name} is showing a 4-digit code; enter it in AirBridge")
        }
        pairingMode = "PIN pair-setup + pair-verify"
        val acc = pairing.pairSetupWithPin(pin)
        pairingStore.save(config.receiverKey, acc)
        return pairing.pairVerify(acc)
    }

    private fun decodeDict(resp: RtspResponse): Map<String, Any?> =
        if (resp.body.isNotEmpty() && BinaryPlist.isBinaryPlist(resp.body)) BinaryPlist.decodeDict(resp.body) else emptyMap()

    private fun requireSuccess(resp: RtspResponse, step: String) {
        if (resp.isSuccess) return
        when (resp.statusCode) {
            453 -> throw ReceiverBusyException("${config.name} is busy playing from another sender")
            470 -> throw PinRequiredException("${config.name} requires pairing")
            else -> throw ProtocolFailureException("${config.name}: $step failed with ${resp.statusCode} ${resp.reason}")
        }
    }

    override fun send(frame: AudioFrame) {
        val s = streamer ?: return
        val key = audioKey ?: return
        if (closed) return
        val header = RtpPackets.audioHeader(marker, seq, frame.rtpTimestamp, ssrc)
        val nonce = HapCrypto.counterNonce(nonceCounter++)
        val aad = header.copyOfRange(4, 12)
        val sealed = HapCrypto.seal(key, nonce, frame.payload(config.codec), aad)
        val packet = ByteArray(header.size + sealed.size + 8)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(sealed, 0, packet, header.size, sealed.size)
        System.arraycopy(nonce, 4, packet, header.size + sealed.size, 8)
        s.sendAudio(seq, packet)
        seq = (seq + 1) and 0xFFFF
        marker = false
    }

    override fun tick() {
        val s = streamer ?: return
        val t = timeline ?: return
        if (closed) return
        // A sync-offset change asks for an immediate sync packet; it is sent here, on the worker
        // thread that owns the stream, so the HAP session, keys and RTP sequence stay as they are.
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
        val c = rtsp ?: return
        val resp = c.request("SET_PARAMETER", uri, "volume: ${AirPlayVolume.format(db)}\r\n".toByteArray(Charsets.UTF_8), "text/parameters")
        if (!resp.isSuccess) AirLog.w(TAG, "${config.name}: SET_PARAMETER volume answered ${resp.statusCode}")
    }

    private fun feedback() {
        if (closed) return
        try {
            val resp = rtsp?.request("POST", "/feedback") ?: return
            stats.feedbackSent++
            stats.controlFailures = 0
            if (!resp.isSuccess) AirLog.d(TAG, "${config.name}: feedback answered ${resp.statusCode}")
        } catch (e: Exception) {
            if (!closed) {
                stats.controlFailures++
                AirLog.w(TAG, "${config.name}: feedback failed", e)
            }
        }
    }

    override fun flush(nextFrameIndex: Long) {
        val t = timeline ?: return
        val c = rtsp ?: return
        val rtptime = t.timestampForFrame(nextFrameIndex)
        val resp = c.request("FLUSH", uri, extraHeaders = mapOf("RTP-Info" to "seq=$seq;rtptime=$rtptime"))
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
        if (c != null && !c.closed && sessionSetUp) {
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
        events?.close()
        rtsp?.close()
        streamer?.close()
    }

    companion object {
        private const val TAG = "AP2"
        private const val BPLIST = "application/x-apple-binary-plist"

        /** AirPlay 2 audioFormat bit for ALAC/44100/16/2. */
        const val AP2_FORMAT_ALAC_44100_16_2 = 0x40000L

        const val FLAG_PIN_REQUIRED = 1L shl 3
        const val FLAG_PASSWORD_REQUIRED = 1L shl 7
        const val FLAG_ONE_TIME_PAIRING_REQUIRED = 1L shl 9
    }
}
