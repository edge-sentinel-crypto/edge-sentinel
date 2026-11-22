package com.airbridge.airplay.mdns

import java.util.Locale

/**
 * AirPlay `features` / `ft` bitmask ("0xLOW,0xHIGH" or a single "0xLOW") and status flags
 * (`flags` / `sf`). Bit meanings follow pyatv's AirPlayFlags and owntone's status flags.
 */
object AirPlayFeatures {
    const val VIDEO = 0
    const val PHOTO = 1
    const val SCREEN = 7
    const val AUDIO = 9
    const val AUDIO_REDUNDANT = 11
    const val AUTHENTICATION_4 = 14 // FairPlay
    const val METADATA_TEXT = 17
    const val AUTHENTICATION_1 = 23 // RSA (AirPort Express)
    const val AUTHENTICATION_8 = 26 // MFi
    const val SUPPORTS_LEGACY_PAIRING = 27
    const val UNIFIED_ADVERTISER_INFO = 30
    const val UNIFIED_MEDIA_CONTROL = 38
    const val BUFFERED_AUDIO = 40
    const val PTP = 41
    const val SCREEN_MULTI_CODEC = 42
    const val SYSTEM_PAIRING = 43
    const val HK_PAIRING_AND_ACCESS_CONTROL = 46
    const val TRANSIENT_PAIRING = 48 // SupportsCoreUtilsPairingAndEncryption
    const val METADATA_FEATURES_3 = 50
    const val UNIFIED_PAIR_SETUP_AND_MFI = 51

    /** Parses a features string; returns 0 when absent or malformed. */
    fun parse(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        val parts = value.split(',').map { it.trim() }
        return try {
            val low = parseWord(parts[0])
            val high = if (parts.size > 1) parseWord(parts[1]) else 0L
            (high shl 32) or (low and 0xFFFFFFFFL)
        } catch (e: NumberFormatException) {
            0
        }
    }

    private fun parseWord(s: String): Long {
        val t = s.lowercase(Locale.US)
        return if (t.startsWith("0x")) java.lang.Long.parseLong(t.substring(2), 16) else java.lang.Long.parseLong(t)
    }

    fun has(features: Long, bit: Int): Boolean = (features ushr bit) and 1L == 1L

    /** Same heuristic as pyatv: AirPlay 2 when bit 38 or bit 48 is set (bit 46 also implies HAP). */
    fun isAirPlay2(features: Long): Boolean =
        has(features, UNIFIED_MEDIA_CONTROL) || has(features, TRANSIENT_PAIRING) || has(features, HK_PAIRING_AND_ACCESS_CONTROL)

    fun format(features: Long): String =
        "0x%X,0x%X".format(Locale.US, features and 0xFFFFFFFFL, features ushr 32)

    /** Parses a hex/decimal status flags value (`flags=0x4`, `sf=0x44`). */
    fun parseFlags(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        return try {
            parseWord(value.trim())
        } catch (e: NumberFormatException) {
            0
        }
    }
}

/** Status flag bits shared by the `_raop._tcp` `sf` and `_airplay._tcp` `flags` fields. */
object AirPlayStatusFlags {
    const val PROBLEM_DETECTED = 1L shl 0
    const val NOT_CONFIGURED = 1L shl 1
    const val AUDIO_CABLE_ATTACHED = 1L shl 2
    const val PIN_REQUIRED = 1L shl 3
    const val PASSWORD_REQUIRED = 1L shl 7
    const val ONE_TIME_PAIRING_REQUIRED = 1L shl 9
    const val RECEIVER_SESSION_ACTIVE = 1L shl 17
}
