package com.airbridge.airplay.mdns

import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AudioCodec
import java.util.Locale

/** DNS-SD service types AirBridge browses. */
enum class ServiceType(val dnsType: String) {
    RAOP("_raop._tcp"),
    AIRPLAY("_airplay._tcp");

    companion object {
        fun fromDnsType(t: String): ServiceType? {
            val clean = t.trim().trimEnd('.').lowercase(Locale.US).removeSuffix(".local")
            return values().firstOrNull { clean == it.dnsType || clean.endsWith(it.dnsType) }
        }
    }
}

/** One resolved DNS-SD service instance. */
data class DiscoveredService(
    val type: ServiceType,
    val instanceName: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
    val source: String,
) {
    /** RAOP instance names look like "AABBCCDDEEFF@Living Room". */
    val raopDeviceId: String? get() =
        if (type == ServiceType.RAOP) instanceName.substringBefore('@', "").takeIf { it.length == 12 && it.all { c -> c.isLetterOrDigit() } }?.let { normalizeDeviceId(it) } else null

    val displayName: String get() =
        if (type == ServiceType.RAOP && instanceName.contains('@')) instanceName.substringAfter('@') else instanceName

    /** Device id from the TXT (`deviceid`) or the RAOP instance-name prefix. */
    val deviceId: String? get() = txt["deviceid"]?.let { normalizeDeviceId(it) } ?: raopDeviceId

    companion object {
        /** "aa:bb:cc:dd:ee:ff" / "AABBCCDDEEFF" -> "AABBCCDDEEFF". */
        fun normalizeDeviceId(raw: String): String? {
            val hex = raw.filter { it.isLetterOrDigit() }.uppercase(Locale.US)
            return if (hex.length == 12 && hex.all { it in '0'..'9' || it in 'A'..'F' }) hex else null
        }
    }
}

/** Per-receiver protocol choice (Auto follows [AirPlayReceiver.recommendedProtocol]). */
enum class ProtocolPreference { AUTO, AIRPLAY_2, AIRPLAY_1 }

/**
 * A speaker as shown in the UI: the merge of its `_raop._tcp` and `_airplay._tcp` advertisements
 * (or a manual IP:port entry).
 */
data class AirPlayReceiver(
    val id: String,
    val name: String,
    val host: String,
    val raopPort: Int? = null,
    val airplayPort: Int? = null,
    val raopTxt: Map<String, String> = emptyMap(),
    val airplayTxt: Map<String, String> = emptyMap(),
    val manual: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis(),
) {
    val features: Long get() = AirPlayFeatures.parse(airplayTxt["features"] ?: raopTxt["ft"] ?: raopTxt["features"])

    val statusFlags: Long get() = AirPlayFeatures.parseFlags(airplayTxt["flags"] ?: raopTxt["sf"] ?: raopTxt["flags"])

    val model: String? get() = airplayTxt["model"] ?: raopTxt["am"]

    val manufacturer: String? get() = airplayTxt["manufacturer"]

    val sourceVersion: String? get() = airplayTxt["srcvers"] ?: raopTxt["vs"]

    val publicKey: String? get() = airplayTxt["pk"] ?: raopTxt["pk"]

    val encryptionTypes: Set<Int> get() = intList(raopTxt["et"])

    val codecs: Set<Int> get() = intList(raopTxt["cn"])

    val metadataTypes: Set<Int> get() = intList(raopTxt["md"])

    val isShairportSync: Boolean get() = model?.contains("shairport", ignoreCase = true) == true

    val supportsAirPlay2: Boolean get() = airplayPort != null && AirPlayFeatures.isAirPlay2(features)

    val supportsAirPlay1: Boolean get() = raopPort != null

    val requiresPassword: Boolean get() =
        raopTxt["pw"].equals("true", true) || airplayTxt["pw"].equals("true", true) ||
            statusFlags and AirPlayStatusFlags.PASSWORD_REQUIRED != 0L

    val requiresPin: Boolean get() =
        statusFlags and (AirPlayStatusFlags.PIN_REQUIRED or AirPlayStatusFlags.ONE_TIME_PAIRING_REQUIRED) != 0L

    val isLocked: Boolean get() = requiresPassword || requiresPin

    /**
     * Which protocol AirBridge uses in Auto mode:
     *  - AirPlay 2 when the receiver advertises HAP/transient pairing (feature bit 38, 46 or 48),
     *  - except Shairport-Sync, whose AirPlay 2 mode only accepts PTP timing (needs privileged
     *    ports on Android), so its AirPlay 1 service is used,
     *  - otherwise AirPlay 1 (RAOP).
     */
    val recommendedProtocol: AirPlayProtocol get() = when {
        isShairportSync && supportsAirPlay1 -> AirPlayProtocol.AIRPLAY_1
        supportsAirPlay2 -> AirPlayProtocol.AIRPLAY_2
        supportsAirPlay1 -> AirPlayProtocol.AIRPLAY_1
        else -> AirPlayProtocol.AIRPLAY_2
    }

    fun protocolFor(pref: ProtocolPreference): AirPlayProtocol = when (pref) {
        ProtocolPreference.AUTO -> recommendedProtocol
        ProtocolPreference.AIRPLAY_2 -> if (airplayPort != null || raopPort == null) AirPlayProtocol.AIRPLAY_2 else AirPlayProtocol.AIRPLAY_1
        ProtocolPreference.AIRPLAY_1 -> if (raopPort != null || airplayPort == null) AirPlayProtocol.AIRPLAY_1 else AirPlayProtocol.AIRPLAY_2
    }

    /** AirPlay 1 payload codec: ALAC unless the receiver only lists PCM (cn=0). */
    val raopCodec: AudioCodec get() {
        val cn = codecs
        return if (cn.isEmpty() || cn.contains(1)) AudioCodec.ALAC else if (cn.contains(0)) AudioCodec.L16 else AudioCodec.ALAC
    }

    /** RSA/AES payload encryption is used only where the receiver needs it (owntone's rules). */
    val raopNeedsEncryption: Boolean get() {
        val et = encryptionTypes
        if (raopTxt["ek"] == "1" && (et.isEmpty() || et.contains(1))) return true
        if (et.contains(1) && !et.contains(0)) return true
        val m = raopTxt["am"]
        return et.contains(1) && (m == null || m.startsWith("AirPort4", ignoreCase = true))
    }

    /** POST /auth-setup up front for AirPort Express units listing MFi (et=4); others only on a 403. */
    val raopWantsAuthSetup: Boolean get() = encryptionTypes.contains(4) && (raopTxt["am"]?.startsWith("AirPort", ignoreCase = true) == true)

    val raopPortOrDefault: Int get() = raopPort ?: airplayPort ?: 7000
    val airplayPortOrDefault: Int get() = airplayPort ?: raopPort ?: 7000

    /** Friendly model label for the UI. */
    val modelLabel: String get() {
        val m = model ?: return if (manual) "Manual receiver" else "AirPlay receiver"
        return when {
            m.startsWith("AudioAccessory5") -> "HomePod mini"
            m.startsWith("AudioAccessory") -> "HomePod"
            m.startsWith("AppleTV") -> "Apple TV"
            m.startsWith("AirPort", ignoreCase = true) -> "AirPort Express"
            isShairportSync -> "Shairport-Sync"
            manufacturer != null -> "$manufacturer $m"
            else -> m
        }
    }

    companion object {
        fun intList(v: String?): Set<Int> =
            v?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()

        fun manual(host: String, port: Int, protocol: AirPlayProtocol?, name: String? = null): AirPlayReceiver {
            val label = name?.takeIf { it.isNotBlank() } ?: "$host:$port"
            return when (protocol) {
                AirPlayProtocol.AIRPLAY_1 -> AirPlayReceiver("manual:$host:$port", label, host, raopPort = port, manual = true)
                AirPlayProtocol.AIRPLAY_2 -> AirPlayReceiver(
                    "manual:$host:$port", label, host, airplayPort = port, manual = true,
                    airplayTxt = mapOf("features" to "0x0,0x10000"), // assume transient-pairing capable
                )
                null -> AirPlayReceiver("manual:$host:$port", label, host, raopPort = port, airplayPort = port, manual = true)
            }
        }
    }
}
