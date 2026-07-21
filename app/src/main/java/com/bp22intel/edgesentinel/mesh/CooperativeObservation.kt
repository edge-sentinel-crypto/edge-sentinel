/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.mesh

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * A compact observation packet shared between Edge Sentinel devices via BLE GATT.
 *
 * Privacy safeguards:
 * - Position is ALWAYS grid-snapped to ~100m (3 decimal places)
 * - Device ID is a random 8-char hex, rotated every 24 hours
 * - No personal data — only anonymous signal observations
 */
data class CooperativeObservation(
    val deviceId: String,       // 8-char anonymous device identifier
    val timestamp: Long,        // when observed (epoch ms)
    val latCoarse: Double,      // grid-snapped to ~100m for privacy
    val lngCoarse: Double,
    val suspiciousCid: Long,    // cell ID of the suspicious tower
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val rsrp: Int,              // signal strength (dBm)
    val timingAdvance: Int,     // if available, -1 if not
    val threatType: String,     // FAKE_BTS, NETWORK_DOWNGRADE, etc.
    val confidence: Float       // 0.0–1.0
) {
    /**
     * Serialize to compact JSON for BLE transfer (~200 bytes).
     * BLE GATT characteristics can handle ~512 bytes per read/write.
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("did", deviceId)
        json.put("ts", timestamp)
        json.put("lat", latCoarse)
        json.put("lng", lngCoarse)
        json.put("cid", suspiciousCid)
        json.put("mcc", mcc)
        json.put("mnc", mnc)
        json.put("lac", lac)
        json.put("rsrp", rsrp)
        json.put("ta", timingAdvance)
        json.put("tt", threatType)
        json.put("conf", (confidence * 100).roundToLong() / 100.0) // 2 decimal places
        return json.toString()
    }

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * Grid-snap a coordinate to ~100m precision (3 decimal places).
         * lat/lng at 3 decimals ≈ 111m latitude, ~85-111m longitude depending on latitude.
         */
        fun snapToGrid(coord: Double): Double =
            (coord * 1000.0).toLong() / 1000.0

        fun fromJson(raw: String): CooperativeObservation? {
            return try {
                val json = JSONObject(raw)
                CooperativeObservation(
                    deviceId = json.getString("did"),
                    timestamp = json.getLong("ts"),
                    latCoarse = json.getDouble("lat"),
                    lngCoarse = json.getDouble("lng"),
                    suspiciousCid = json.getLong("cid"),
                    mcc = json.getInt("mcc"),
                    mnc = json.getInt("mnc"),
                    lac = json.getInt("lac"),
                    rsrp = json.getInt("rsrp"),
                    timingAdvance = json.optInt("ta", -1),
                    threatType = json.getString("tt"),
                    confidence = json.getDouble("conf").toFloat()
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromBytes(bytes: ByteArray): CooperativeObservation? =
            fromJson(String(bytes, Charsets.UTF_8))

        /**
         * Serialize a list of observations to JSON array for the GATT read characteristic.
         * Multiple observations can be bundled into a single read.
         */
        fun listToJson(observations: List<CooperativeObservation>): String {
            val array = JSONArray()
            observations.forEach { array.put(JSONObject(it.toJson())) }
            return array.toString()
        }

        /**
         * Deserialize a JSON array of observations from a GATT read.
         */
        fun listFromJson(raw: String): List<CooperativeObservation> {
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    fromJson(array.getJSONObject(i).toString())
                }
            } catch (e: Exception) {
                // Try single object fallback
                fromJson(raw)?.let { listOf(it) } ?: emptyList()
            }
        }
    }
}

/**
 * Result of cooperative trilateration from multiple device observations.
 */
data class CooperativeTrilateration(
    val cellId: Long,
    val observations: List<CooperativeObservation>,
    val estimatedLat: Double?,      // null until 3+ observations
    val estimatedLng: Double?,
    val estimatedAccuracyM: Double?,
    val participatingDevices: Int,
    val lastUpdated: Long
) {
    /** Whether we have enough data for a position estimate. */
    val hasEstimate: Boolean get() = estimatedLat != null && estimatedLng != null

    /** Human-readable accuracy string. */
    val accuracyLabel: String get() = when {
        estimatedAccuracyM == null -> "Unknown"
        estimatedAccuracyM < 100 -> "±${estimatedAccuracyM.toInt()}m (High)"
        estimatedAccuracyM < 250 -> "±${estimatedAccuracyM.toInt()}m (Medium)"
        else -> "±${estimatedAccuracyM.toInt()}m (Low)"
    }
}
