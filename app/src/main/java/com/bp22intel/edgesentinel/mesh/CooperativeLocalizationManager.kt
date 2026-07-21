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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bp22intel.edgesentinel.detection.geo.MeshPeerObservation
import com.bp22intel.edgesentinel.detection.geo.ThreatGeolocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

private const val TAG = "CoopLocManager"
private const val PREFS_NAME = "cooperative_localization"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_DEVICE_ID_TIMESTAMP = "device_id_timestamp"
private const val DEVICE_ID_ROTATION_MS = 24 * 60 * 60 * 1000L // 24 hours

/**
 * Manages the cooperative threat localization protocol for the Edge Sentinel mesh.
 *
 * Responsibilities:
 * - Anonymous device ID generation & rotation (24-hour cycle)
 * - Creating CooperativeObservations from local threat data
 * - Receiving peer observations and feeding them to ThreatGeolocation
 * - Tracking cooperative trilateration results per CID
 * - Privacy safeguards (grid-snapping, anonymous IDs, no PII)
 *
 * This class bridges the BLE GATT server (transport) with the geolocation engine
 * (consumer of observations).
 */
class CooperativeLocalizationManager(
    private val context: Context,
    private val threatGeolocation: ThreatGeolocation
) {
    companion object {
        /**
         * Global cooperative trilateration results, observable from any ViewModel.
         * Updated by the active CooperativeLocalizationManager instance.
         */
        private val _globalTrilaterations = MutableStateFlow<List<CooperativeTrilateration>>(emptyList())
        val globalTrilaterations: StateFlow<List<CooperativeTrilateration>> = _globalTrilaterations.asStateFlow()
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Anonymous 8-char hex device ID, rotated every 24 hours. */
    val deviceId: String get() = getOrRotateDeviceId()

    /** Peer observations received, keyed by CID. */
    private val peerObservationsByCid = mutableMapOf<Long, MutableList<CooperativeObservation>>()

    /** Cooperative trilateration results. */
    private val _trilaterations = MutableStateFlow<List<CooperativeTrilateration>>(emptyList())
    val trilaterations: StateFlow<List<CooperativeTrilateration>> = _trilaterations.asStateFlow()

    /** Count of observations we're sharing. */
    private val _sharingCount = MutableStateFlow(0)
    val sharingCount: StateFlow<Int> = _sharingCount.asStateFlow()

    /** Count of peers we've received observations from. */
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    /** Whether cooperative mode is actively producing results. */
    val isCooperativeActive: Boolean
        get() = _trilaterations.value.any { it.observations.size >= 2 }

    // ── Observation Creation (Local → Share) ─────────────────────────────

    /**
     * Create a CooperativeObservation from local threat detection data.
     * Position is grid-snapped for privacy.
     *
     * @param userLat raw user latitude (will be grid-snapped)
     * @param userLng raw user longitude (will be grid-snapped)
     * @param cid suspicious cell ID
     * @param mcc mobile country code
     * @param mnc mobile network code
     * @param lac location area code
     * @param rsrp signal strength in dBm
     * @param timingAdvance LTE timing advance, or -1 if unavailable
     * @param threatType e.g. "FAKE_BTS", "NETWORK_DOWNGRADE"
     * @param confidence 0.0–1.0
     */
    fun createObservation(
        userLat: Double,
        userLng: Double,
        cid: Long,
        mcc: Int,
        mnc: Int,
        lac: Int,
        rsrp: Int,
        timingAdvance: Int = -1,
        threatType: String,
        confidence: Float
    ): CooperativeObservation {
        return CooperativeObservation(
            deviceId = deviceId,
            timestamp = System.currentTimeMillis(),
            latCoarse = CooperativeObservation.snapToGrid(userLat),
            lngCoarse = CooperativeObservation.snapToGrid(userLng),
            suspiciousCid = cid,
            mcc = mcc,
            mnc = mnc,
            lac = lac,
            rsrp = rsrp,
            timingAdvance = timingAdvance,
            threatType = threatType,
            confidence = confidence
        )
    }

    // ── Observation Receiving (Peers → Us) ───────────────────────────────

    /**
     * Process an observation received from a mesh peer.
     * Feeds it to the geolocation engine and updates trilateration tracking.
     */
    fun onPeerObservationReceived(observation: CooperativeObservation) {
        // Ignore our own observations bounced back
        if (observation.deviceId == deviceId) return

        // Ignore stale observations (> 5 minutes old)
        val age = System.currentTimeMillis() - observation.timestamp
        if (age > 5 * 60 * 1000L) {
            Log.d(TAG, "Ignoring stale observation (${age / 1000}s old)")
            return
        }

        Log.d(TAG, "Received observation: CID=${observation.suspiciousCid} " +
            "from ${observation.deviceId} RSRP=${observation.rsrp}")

        // Feed to ThreatGeolocation for cooperative positioning
        threatGeolocation.addMeshPeerObservation(
            MeshPeerObservation(
                peerLat = observation.latCoarse,
                peerLng = observation.lngCoarse,
                cellCid = observation.suspiciousCid.toInt(),
                rsrpDbm = observation.rsrp,
                timestamp = observation.timestamp
            )
        )

        // Track in our local aggregation
        synchronized(peerObservationsByCid) {
            val cidObs = peerObservationsByCid.getOrPut(observation.suspiciousCid) { mutableListOf() }
            cidObs.add(observation)

            // Cap per-CID observations
            while (cidObs.size > 50) cidObs.removeFirst()

            // Update peer count
            _peerCount.value = peerObservationsByCid.values
                .flatten()
                .map { it.deviceId }
                .distinct()
                .size
        }

        // Recalculate trilaterations
        recalculateTrilaterations()
    }

    // ── Trilateration ────────────────────────────────────────────────────

    /**
     * Recalculate cooperative trilateration for all tracked CIDs.
     * When 3+ devices have observations for the same CID, we can estimate
     * the tower's position using weighted centroid of observer positions
     * adjusted by RSRP-estimated distances.
     */
    private fun recalculateTrilaterations() {
        val now = System.currentTimeMillis()
        val maxAge = 5 * 60 * 1000L // 5 minutes

        val results = synchronized(peerObservationsByCid) {
            peerObservationsByCid.map { (cid, observations) ->
                // Filter to recent observations
                val recent = observations.filter { now - it.timestamp < maxAge }

                // Count unique devices
                val uniqueDevices = recent.map { it.deviceId }.distinct().size

                // Estimate position if 3+ devices
                val (estLat, estLng, estAccuracy) = if (uniqueDevices >= 3) {
                    estimateTowerPosition(recent)
                } else {
                    Triple(null, null, null)
                }

                CooperativeTrilateration(
                    cellId = cid,
                    observations = recent,
                    estimatedLat = estLat,
                    estimatedLng = estLng,
                    estimatedAccuracyM = estAccuracy,
                    participatingDevices = uniqueDevices,
                    lastUpdated = recent.maxOfOrNull { it.timestamp } ?: now
                )
            }.filter { it.observations.isNotEmpty() }
        }

        val sorted = results.sortedByDescending { it.participatingDevices }
        _trilaterations.value = sorted
        _globalTrilaterations.value = sorted
    }

    /**
     * Estimate tower position from multiple peer observations using
     * RSRP-weighted centroid. Each peer's position is weighted by inverse
     * signal distance — closer peers (stronger signal) contribute more.
     *
     * Returns (lat, lng, accuracyMeters) or (null, null, null) if insufficient data.
     */
    private fun estimateTowerPosition(
        observations: List<CooperativeObservation>
    ): Triple<Double?, Double?, Double?> {
        if (observations.size < 3) return Triple(null, null, null)

        // Use RSRP to estimate relative distance weight
        // Stronger signal (less negative RSRP) = closer to tower = higher weight
        var sumLat = 0.0
        var sumLng = 0.0
        var sumWeight = 0.0

        for (obs in observations) {
            // Convert RSRP to distance-like weight
            // RSRP ranges roughly from -140 (far) to -44 (close)
            // Use inverse square of estimated distance
            val estimatedDistKm = rsrpToDistanceKm(obs.rsrp)
            val weight = 1.0 / (estimatedDistKm * estimatedDistKm + 0.001)

            sumLat += obs.latCoarse * weight
            sumLng += obs.lngCoarse * weight
            sumWeight += weight
        }

        if (sumWeight == 0.0) return Triple(null, null, null)

        val estLat = sumLat / sumWeight
        val estLng = sumLng / sumWeight

        // Estimate accuracy: spread of observations + RSRP uncertainty
        val spreadM = observations.map { obs ->
            haversineM(estLat, estLng, obs.latCoarse, obs.lngCoarse)
        }.average()

        // More devices → better accuracy
        val deviceBonus = when (observations.map { it.deviceId }.distinct().size) {
            in 5..Int.MAX_VALUE -> 0.5
            4 -> 0.7
            3 -> 1.0
            else -> 1.5
        }

        val accuracy = (spreadM * deviceBonus).coerceIn(50.0, 1000.0)

        return Triple(estLat, estLng, accuracy)
    }

    /**
     * Simple RSRP → distance estimate using COST-231 model approximation.
     * Returns distance in kilometers.
     */
    private fun rsrpToDistanceKm(rsrp: Int): Double {
        // Simplified: -70 dBm ≈ 0.5km, -90 dBm ≈ 2km, -110 dBm ≈ 5km
        val pathLoss = 43.0 - rsrp // 43 dBm typical macro TX power
        val logD = (pathLoss - 128.1) / 36.7 // COST-231 simplified
        val distKm = Math.pow(10.0, logD).coerceIn(0.01, 50.0)
        return distKm
    }

    /** Haversine distance in meters. */
    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2.0 * R * Math.asin(Math.sqrt(a))
    }

    // ── Maintenance ──────────────────────────────────────────────────────

    /**
     * Prune stale observations (> 5 minutes old).
     * Called periodically from MeshViewModel maintenance loop.
     */
    fun pruneStale() {
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
        synchronized(peerObservationsByCid) {
            val iter = peerObservationsByCid.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                entry.value.removeAll { it.timestamp < cutoff }
                if (entry.value.isEmpty()) iter.remove()
            }
        }
        recalculateTrilaterations()
    }

    /** Reset all state. */
    fun clear() {
        synchronized(peerObservationsByCid) { peerObservationsByCid.clear() }
        _trilaterations.value = emptyList()
        _globalTrilaterations.value = emptyList()
        _sharingCount.value = 0
        _peerCount.value = 0
    }

    fun updateSharingCount(count: Int) {
        _sharingCount.value = count
    }

    // ── Device ID Management ─────────────────────────────────────────────

    /**
     * Get or rotate the anonymous device ID.
     * IDs are 8-char random hex strings, rotated every 24 hours for privacy.
     */
    private fun getOrRotateDeviceId(): String {
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        val existingTimestamp = prefs.getLong(KEY_DEVICE_ID_TIMESTAMP, 0L)

        return if (existingId != null &&
            System.currentTimeMillis() - existingTimestamp < DEVICE_ID_ROTATION_MS) {
            existingId
        } else {
            val newId = generateDeviceId()
            prefs.edit()
                .putString(KEY_DEVICE_ID, newId)
                .putLong(KEY_DEVICE_ID_TIMESTAMP, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Rotated anonymous device ID")
            newId
        }
    }

    private fun generateDeviceId(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
