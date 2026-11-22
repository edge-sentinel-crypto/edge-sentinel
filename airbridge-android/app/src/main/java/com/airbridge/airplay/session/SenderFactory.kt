package com.airbridge.airplay.session

import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AirPlaySender
import com.airbridge.airplay.airplay.AirPlayVolume
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.StreamSettings
import com.airbridge.airplay.airplay.SyncOffset
import com.airbridge.airplay.airplay.ap2.Ap2Config
import com.airbridge.airplay.airplay.ap2.Ap2Sender
import com.airbridge.airplay.airplay.ap2.PairingStore
import com.airbridge.airplay.airplay.raop.RaopConfig
import com.airbridge.airplay.airplay.raop.RaopSender
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import java.net.InetAddress
import java.security.SecureRandom

/** What the user chose for one speaker. */
data class ConnectOptions(
    val volumePercent: Int = 50,
    val preference: ProtocolPreference = ProtocolPreference.AUTO,
    val pin: String? = null,
    val password: String? = null,
    /** Per-speaker sync offset in ms (-250..+250): negative = plays earlier, positive = plays later. */
    val syncOffsetMs: Int = SyncOffset.DEFAULT_MS,
)

/** Creates the protocol-specific sender for a receiver (replaceable in tests). */
fun interface SenderFactory {
    fun create(receiver: AirPlayReceiver, protocol: AirPlayProtocol, options: ConnectOptions): AirPlaySender
}

class DefaultSenderFactory(
    private val identity: SenderIdentity,
    private val pairingStore: PairingStore,
    private val settings: StreamSettings = StreamSettings(),
    private val random: SecureRandom = SecureRandom(),
) : SenderFactory {
    override fun create(receiver: AirPlayReceiver, protocol: AirPlayProtocol, options: ConnectOptions): AirPlaySender {
        // Hosts are IP literals from mDNS or manual entry, so this never performs a DNS lookup.
        val host = InetAddress.getByName(receiver.host)
        val streamSettings = settings.copy(
            initialVolumeDb = AirPlayVolume.percentToDb(options.volumePercent),
            syncOffsetMs = SyncOffset.clamp(options.syncOffsetMs),
        )
        return when (protocol) {
            AirPlayProtocol.AIRPLAY_1 -> RaopSender(
                RaopConfig(
                    name = receiver.name,
                    host = host,
                    port = receiver.raopPortOrDefault,
                    codec = receiver.raopCodec,
                    encrypt = receiver.raopNeedsEncryption,
                    authSetup = receiver.raopWantsAuthSetup,
                    password = options.password,
                    settings = streamSettings,
                ),
                identity,
                random,
            )
            AirPlayProtocol.AIRPLAY_2 -> Ap2Sender(
                Ap2Config(
                    name = receiver.name,
                    host = host,
                    port = receiver.airplayPortOrDefault,
                    receiverKey = receiver.id,
                    pin = options.pin,
                    password = options.password,
                    statusFlagsHint = receiver.statusFlags,
                    settings = streamSettings,
                ),
                identity,
                pairingStore,
                random,
            )
        }
    }
}
