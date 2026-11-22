package com.airbridge.airplay

import android.content.Context
import android.content.SharedPreferences
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.SyncOffset
import com.airbridge.airplay.airplay.ap2.AccessoryPairing
import com.airbridge.airplay.airplay.ap2.ControllerIdentity
import com.airbridge.airplay.airplay.ap2.PairingStore
import com.airbridge.airplay.crypto.Ed25519KeyPair
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.multiroom.SpeakerGroup
import com.airbridge.airplay.multiroom.SpeakerGroupCodec
import com.airbridge.airplay.multiroom.SpeakerStore
import com.airbridge.airplay.util.Hex
import java.security.SecureRandom

/**
 * Small persisted settings in app-private SharedPreferences (never backed up, see
 * data_extraction_rules.xml). Receiver passwords and PINs are NOT stored; only the HAP pairing
 * keys produced by a PIN pairing are kept so the speaker does not ask again.
 */
class AppSettings(context: Context) : SpeakerStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("airbridge", Context.MODE_PRIVATE)

    override fun selectedReceivers(): Set<String> = prefs.getStringSet(KEY_SELECTED, emptySet())?.toSet() ?: emptySet()

    override fun setSelectedReceivers(ids: Set<String>) = prefs.edit().putStringSet(KEY_SELECTED, ids.toSet()).apply()

    override fun volume(id: String): Int = prefs.getInt("vol:$id", SpeakerStore.DEFAULT_VOLUME).coerceIn(0, 100)

    override fun setVolume(id: String, percent: Int) = prefs.edit().putInt("vol:$id", percent.coerceIn(0, 100)).apply()

    /** One write for a whole master-volume change. */
    override fun setVolumes(volumes: Map<String, Int>) {
        val e = prefs.edit()
        volumes.forEach { (id, v) -> e.putInt("vol:$id", v.coerceIn(0, 100)) }
        e.apply()
    }

    override fun preference(id: String): ProtocolPreference =
        prefs.getString("proto:$id", null)?.let { runCatching { ProtocolPreference.valueOf(it) }.getOrNull() } ?: ProtocolPreference.AUTO

    override fun setPreference(id: String, pref: ProtocolPreference) = prefs.edit().putString("proto:$id", pref.name).apply()

    /** Sync offset in ms (-250..+250, 5 ms steps, 0 when unset): negative = earlier, positive = later. */
    override fun syncOffsetMs(id: String): Int = SyncOffset.snap(prefs.getInt("sync:$id", SyncOffset.DEFAULT_MS))

    override fun setSyncOffsetMs(id: String, ms: Int) {
        val v = SyncOffset.snap(ms)
        val e = prefs.edit()
        if (v == SyncOffset.DEFAULT_MS) e.remove("sync:$id") else e.putInt("sync:$id", v)
        e.apply()
    }

    /** Saved speaker groups, stored as hex-encoded text (see [SpeakerGroupCodec]). */
    override fun speakerGroups(): List<SpeakerGroup> = SpeakerGroupCodec.decode(prefs.getString(KEY_GROUPS, null))

    override fun setSpeakerGroups(groups: List<SpeakerGroup>) =
        prefs.edit().putString(KEY_GROUPS, SpeakerGroupCodec.encode(groups)).apply()

    var batteryPromptDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(KEY_BATTERY_DISMISSED, v).apply()

    /** Manual receivers stored as "host|port|protocol|name" lines. */
    fun manualReceivers(): List<AirPlayReceiver> {
        val raw = prefs.getStringSet(KEY_MANUAL, emptySet()) ?: emptySet()
        return raw.mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size < 4) return@mapNotNull null
            val port = p[1].toIntOrNull() ?: return@mapNotNull null
            val proto = when (p[2]) {
                "1" -> AirPlayProtocol.AIRPLAY_1
                "2" -> AirPlayProtocol.AIRPLAY_2
                else -> null
            }
            AirPlayReceiver.manual(p[0], port, proto, p[3])
        }
    }

    fun addManualReceiver(host: String, port: Int, protocol: AirPlayProtocol?, name: String) {
        val code = when (protocol) {
            AirPlayProtocol.AIRPLAY_1 -> "1"
            AirPlayProtocol.AIRPLAY_2 -> "2"
            null -> "auto"
        }
        val set = (prefs.getStringSet(KEY_MANUAL, emptySet()) ?: emptySet()).toMutableSet()
        set.removeAll { it.startsWith("$host|$port|") }
        set.add("$host|$port|$code|${name.replace('|', ' ')}")
        prefs.edit().putStringSet(KEY_MANUAL, set).apply()
    }

    fun removeManualReceiver(host: String, port: Int) {
        val set = (prefs.getStringSet(KEY_MANUAL, emptySet()) ?: emptySet()).toMutableSet()
        set.removeAll { it.startsWith("$host|$port|") }
        prefs.edit().putStringSet(KEY_MANUAL, set).apply()
    }

    /** Stable random identity used on the wire (DACP id, device id); contains no personal data. */
    fun senderIdentity(): SenderIdentity {
        val existing = prefs.getString(KEY_SENDER, null)?.split('|')
        if (existing != null && existing.size == 3) return SenderIdentity(existing[0], existing[1], existing[2])
        val id = SenderIdentity.random()
        prefs.edit().putString(KEY_SENDER, "${id.dacpId}|${id.activeRemote}|${id.deviceId}").apply()
        return id
    }

    fun pairingStore(): PairingStore = PrefsPairingStore(prefs)

    companion object {
        private const val KEY_SELECTED = "selected"
        private const val KEY_MANUAL = "manual"
        private const val KEY_SENDER = "sender_identity"
        private const val KEY_BATTERY_DISMISSED = "battery_prompt_dismissed"
        private const val KEY_GROUPS = "speaker_groups"
    }
}

/** HAP identity + PIN pairings in private prefs (keys are device-local, excluded from backups). */
private class PrefsPairingStore(private val prefs: SharedPreferences) : PairingStore {
    @Synchronized
    override fun controllerIdentity(): ControllerIdentity {
        val id = prefs.getString(KEY_ID, null)
        val seed = prefs.getString(KEY_SEED, null)
        if (id != null && seed != null) return ControllerIdentity(id, Ed25519KeyPair.fromSeed(Hex.decode(seed)))
        val created = ControllerIdentity.generate(SecureRandom())
        prefs.edit().putString(KEY_ID, created.pairingId).putString(KEY_SEED, Hex.encode(created.signingKey.seed)).apply()
        return created
    }

    override fun load(receiverKey: String): AccessoryPairing? {
        val v = prefs.getString("pair:$receiverKey", null) ?: return null
        val parts = v.split('|')
        if (parts.size != 2) return null
        return runCatching { AccessoryPairing(String(Hex.decode(parts[0]), Charsets.UTF_8), Hex.decode(parts[1])) }.getOrNull()
    }

    override fun save(receiverKey: String, pairing: AccessoryPairing) {
        val id = Hex.encode(pairing.accessoryId.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("pair:$receiverKey", "$id|${Hex.encode(pairing.accessoryLtpk)}").apply()
    }

    override fun remove(receiverKey: String) {
        prefs.edit().remove("pair:$receiverKey").apply()
    }

    companion object {
        private const val KEY_ID = "hap_pairing_id"
        private const val KEY_SEED = "hap_ltsk_seed"
    }
}
