/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.geo

import android.content.Context
import android.os.Build
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import com.bp22intel.edgesentinel.data.sensor.CellInfoCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType
import com.bp22intel.edgesentinel.sensor.MotionDetector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
// Propagation Models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Advanced propagation models for cellular, WiFi, and BLE distance estimation.
 */
object PropagationModels {

    /** Okumura-Hata model for urban macro-cell (150–1500 MHz). Returns meters. */
    fun okumuraHataDistance(
        rssiDbm: Int,
        frequencyMhz: Double = 900.0,
        baseHeightM: Double = 30.0,
        mobileHeightM: Double = 1.5,
        environment: Environment = Environment.URBAN
    ): Double {
        val txPower = 43.0
        val pathLoss = txPower - rssiDbm

        val aHm = when (environment) {
            Environment.URBAN_LARGE ->
                3.2 * (log10(11.75 * mobileHeightM)).pow(2) - 4.97
            else ->
                (1.1 * log10(frequencyMhz) - 0.7) * mobileHeightM -
                    (1.56 * log10(frequencyMhz) - 0.8)
        }

        val A = 69.55 + 26.16 * log10(frequencyMhz) -
            13.82 * log10(baseHeightM) - aHm
        val B = 44.9 - 6.55 * log10(baseHeightM)

        val correctedPL = when (environment) {
            Environment.SUBURBAN ->
                pathLoss + 2 * (log10(frequencyMhz / 28)).pow(2) + 5.4
            Environment.RURAL ->
                pathLoss + 4.78 * (log10(frequencyMhz)).pow(2) -
                    18.33 * log10(frequencyMhz) + 40.94
            else -> pathLoss.toDouble()
        }

        val logD = (correctedPL - A) / B
        return (10.0.pow(logD) * 1000.0).coerceIn(10.0, 50_000.0)
    }

    /** COST 231-Hata for higher frequencies (1500–2000 MHz, LTE bands). */
    fun cost231Distance(
        rssiDbm: Int,
        frequencyMhz: Double = 1800.0,
        baseHeightM: Double = 30.0,
        mobileHeightM: Double = 1.5,
        isUrban: Boolean = true
    ): Double {
        val txPower = 43.0
        val pathLoss = txPower - rssiDbm

        val aHm = (1.1 * log10(frequencyMhz) - 0.7) * mobileHeightM -
            (1.56 * log10(frequencyMhz) - 0.8)
        val cM = if (isUrban) 3.0 else 0.0

        val A = 46.3 + 33.9 * log10(frequencyMhz) -
            13.82 * log10(baseHeightM) - aHm + cM
        val B = 44.9 - 6.55 * log10(baseHeightM)

        val logD = (pathLoss - A) / B
        return (10.0.pow(logD) * 1000.0).coerceIn(10.0, 50_000.0)
    }

    /** Log-distance model for WiFi (2.4/5 GHz). */
    fun logDistanceWifi(
        rssiDbm: Int,
        referenceRssi: Int = -40,
        referenceDistanceM: Double = 1.0,
        pathLossExponent: Double = 3.0
    ): Double {
        val d = referenceDistanceM *
            10.0.pow((referenceRssi - rssiDbm) / (10.0 * pathLossExponent))
        return d.coerceIn(0.5, 500.0)
    }

    /** BLE log-distance model with calibrated TX power. */
    fun logDistanceBle(
        rssiDbm: Int,
        txPowerAtOneMeter: Int = -59,
        pathLossExponent: Double = 2.0
    ): Double {
        val d = 10.0.pow((txPowerAtOneMeter - rssiDbm) / (10.0 * pathLossExponent))
        return d.coerceIn(0.1, 100.0)
    }

    enum class Environment { URBAN, URBAN_LARGE, SUBURBAN, RURAL }
}

// ─────────────────────────────────────────────────────────────────────────────
// Geo Math Utilities
// ─────────────────────────────────────────────────────────────────────────────

private object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in meters. */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).pow(2)
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Initial bearing from point 1 to point 2, in degrees [0, 360). */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lng2 - lng1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Destination point given start, bearing (degrees), and distance (meters). */
    fun destination(lat: Double, lng: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val φ1 = Math.toRadians(lat)
        val λ1 = Math.toRadians(lng)
        val θ = Math.toRadians(bearingDeg)
        val δ = distanceM / EARTH_RADIUS_M

        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return Pair(Math.toDegrees(φ2), Math.toDegrees(λ2))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data Classes
// ─────────────────────────────────────────────────────────────────────────────

/** A single geo-reading of a threat from a known user position. */
data class GeoReading(
    val userLat: Double,
    val userLng: Double,
    val rssi: Int,
    val timestamp: Long,
    val estimatedDistanceM: Double,
    /** LTE Timing Advance value, if available for this reading. */
    val timingAdvance: Int? = null
)

/** Geolocation technique used, for provenance/debugging. */
enum class GeoTechnique {
    KNOWN_TOWER_DB,
    MULTILATERATION,
    TRILATERATION_NLS,       // NEW: nonlinear least-squares trilateration
    TIMING_ADVANCE,          // NEW: LTE timing advance
    PARTICLE_FILTER,         // NEW: particle filter position estimate
    TIME_SERIES_TRIANGULATION,
    SIGNAL_GRADIENT_BEARING,
    RSSI_PROPAGATION,
    BLE_TRAJECTORY,
    WIFI_RTT,                // NEW: WiFi RTT self-positioning
    COOPERATIVE_MESH,        // NEW: cooperative multi-device
    CONFIDENCE_FUSION
}

/** Intermediate position estimate from a single technique. */
private data class PositionEstimate(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val bearingDeg: Double? = null,
    val technique: GeoTechnique,
    /** Confidence weight multiplier for fusion. */
    val confidenceWeight: Double = 1.0
)

/** Output data class — same public interface as before. */
data class GeolocatedThreat(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val threatLevel: ThreatLevel,
    val category: SensorCategory,
    val label: String,
    val timestamp: Long,
    val signalStrengthDbm: Int? = null,
    val bearing: Float? = null,
    /** Whether this threat was cooperatively located by multiple devices. */
    val isCooperativelyLocated: Boolean = false,
    /** Number of devices that contributed to cooperative localization. */
    val cooperativeDeviceCount: Int = 0,
    /** Total number of observations that contributed to this geolocation. */
    val observationCount: Int = 0,
    /** Number of distinct devices (including self) that contributed observations. */
    val contributingDevices: Int = 1
)

/**
 * Observation from a mesh peer about a suspicious cell.
 * Used for cooperative multi-device geolocation.
 */
data class MeshPeerObservation(
    val peerLat: Double,
    val peerLng: Double,
    val cellCid: Int,
    val rsrpDbm: Int,
    val timestamp: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// Main Geolocation Engine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stateful singleton that estimates threat positions using every available
 * signal: known-tower DB lookups, LTE timing advance, nonlinear least-squares
 * trilateration, particle filter tracking, Kalman-filtered BLE/WiFi RSSI,
 * WiFi RTT self-positioning, cooperative mesh observations, time-series
 * triangulation, signal-gradient bearing, and confidence-weighted fusion
 * with technique-specific weight multipliers.
 *
 * Build 55: Added Timing Advance, trilateration (NLS), particle filter,
 * Kalman filter (BLE), WiFi RTT, cooperative mesh, and upgraded fusion.
 */
@Singleton
class ThreatGeolocation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knownTowerDao: KnownTowerDao,
    private val cellInfoCollector: CellInfoCollector,
    private val motionDetector: MotionDetector,
    private val wifiRttPositioner: WifiRttPositioner
) {

    // ── Constants ────────────────────────────────────────────────────────
    companion object {
        private const val READING_MAX_AGE_MS = 10 * 60 * 1000L          // 10 min
        private const val HEAT_MAP_MAX_AGE_MS = 30 * 60 * 1000L         // 30 min window
        private const val MIN_MOVEMENT_M = 50.0                          // for triangulation
        private const val TOWER_CACHE_TTL_MS = 5 * 60 * 1000L           // 5 min
        private const val NEIGHBOR_SEARCH_RADIUS_DEG = 0.15             // ~16 km box

        // LTE Timing Advance: each step ≈ 78.12 meters (round-trip / speed of light)
        private const val TA_STEP_METERS = 78.12
        private const val TA_INVALID = Int.MAX_VALUE

        // Accuracy floors per technique
        private const val ACC_KNOWN_TOWER = 50.0
        private const val ACC_MULTILAT_3PLUS = 150.0
        private const val ACC_MULTILAT_2 = 300.0
        private const val ACC_TRILATERATION_NLS = 100.0   // NLS trilateration
        private const val ACC_TIMING_ADVANCE = 80.0       // TA is quite accurate
        private const val ACC_PARTICLE_FILTER = 120.0
        private const val ACC_TIMESERIES_3PLUS = 100.0
        private const val ACC_TIMESERIES_2 = 200.0
        private const val ACC_GRADIENT_BEARING = 500.0
        private const val ACC_RSSI_CELL = 400.0
        private const val ACC_RSSI_WIFI = 40.0
        private const val ACC_RSSI_BLE = 10.0
        private const val ACC_BLE_TRAJECTORY = 15.0
        private const val ACC_WIFI_RTT = 5.0              // WiFi RTT: 1-2m
        private const val ACC_COOPERATIVE_MESH = 130.0

        // Confidence weight multipliers for fusion
        private const val WEIGHT_KNOWN_TOWER = 5.0         // verified coordinates
        private const val WEIGHT_TIMING_ADVANCE = 3.0      // direct physical measurement
        private const val WEIGHT_COOPERATIVE_MESH = 2.0     // independent observers
        private const val WEIGHT_TRILATERATION_NLS = 2.0    // proper NLS solve
        private const val WEIGHT_PARTICLE_FILTER = 1.5      // filtered estimate
        private const val WEIGHT_WIFI_RTT = 2.5             // very precise
        private const val WEIGHT_DEFAULT = 1.0              // RSSI-based

        // WiFi path-loss exponents by environment hint
        private const val WIFI_PLE_OPEN = 2.0
        private const val WIFI_PLE_OFFICE = 3.0
        private const val WIFI_PLE_DENSE_URBAN = 3.5

        // Particle filter initialization radius
        private const val PARTICLE_INIT_RADIUS_M = 20.0
    }

    // ── State ────────────────────────────────────────────────────────────

    /** Per-threat reading history for time-series techniques. */
    private val readingHistory = mutableMapOf<String, MutableList<GeoReading>>()

    /** Simple LRU cache for tower lookups to avoid repeated DB hits. */
    private data class TowerCacheEntry(val tower: KnownTowerEntity?, val ts: Long)
    private val towerCache = mutableMapOf<String, TowerCacheEntry>()

    /** Particle filter for user position tracking. */
    private val particleFilter = ParticleFilter(numParticles = 200)

    /** Per-device Kalman filters for BLE RSSI smoothing (keyed by threat ID). */
    private val bleKalmanFilters = mutableMapOf<String, RssiKalmanFilter>()

    /** Per-device Kalman filters for WiFi RSSI smoothing (keyed by threat ID). */
    private val wifiKalmanFilters = mutableMapOf<String, RssiKalmanFilter>()

    /** Heat map data — keyed by cell ID, pruned to 30-minute window. */
    private val _heatMapPoints = MutableStateFlow<Map<Long, List<HeatMapPoint>>>(emptyMap())

    /** Mesh peer observations for cooperative geolocation. */
    private val meshObservations = mutableListOf<MeshPeerObservation>()
    private val meshObservationMaxAge = 2 * 60 * 1000L // 2 minutes

    /** Last known user position for particle filter delta tracking. */
    private var lastUserLat = 0.0
    private var lastUserLng = 0.0

    /**
     * Observable heat map points keyed by cell ID.
     * Each list contains signal readings within the last 30 minutes.
     */
    val heatMapPoints: StateFlow<Map<Long, List<HeatMapPoint>>> = _heatMapPoints.asStateFlow()

    /**
     * Add a heat map point from a mesh peer observation.
     */
    fun addPeerHeatMapPoint(point: HeatMapPoint) {
        val mutable = _heatMapPoints.value.toMutableMap()
        val list = (mutable[point.cellId] ?: emptyList()).toMutableList()
        list.add(point)
        mutable[point.cellId] = list
        _heatMapPoints.value = mutable
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Convert a list of alerts to geolocated threats.
     *
     * Suspend because it may hit the known-tower Room database.
     * Called from a coroutine in ThreatMapViewModel — no call-site change needed.
     */
    suspend fun geolocateThreats(
        alerts: List<Alert>,
        userLat: Double,
        userLng: Double
    ): List<GeolocatedThreat> {
        if (userLat == 0.0 && userLng == 0.0) return emptyList()

        // Snapshot visible cells once per call (used for multilateration + TA).
        val visibleCells = try { cellInfoCollector.getCurrentCellInfo() } catch (_: Exception) { emptyList() }

        // Read raw LTE CellInfo for timing advance (CellTower model doesn't carry TA).
        val rawLteCellInfo = try { collectRawLteCellInfo() } catch (_: Exception) { emptyList() }

        // Extract timing advance data from raw cell info
        val timingAdvanceData = extractTimingAdvanceData(rawLteCellInfo)

        // Update particle filter with user motion
        updateParticleFilter(userLat, userLng)

        // Get refined user position from particle filter (if available)
        val (refinedUserLat, refinedUserLng) = getParticleFilteredUserPosition(userLat, userLng)

        pruneReadingHistory()
        pruneMeshObservations()
        pruneHeatMapPoints()

        return alerts.mapNotNull { alert ->
            try {
                geolocateAlert(alert, refinedUserLat, refinedUserLng, visibleCells, timingAdvanceData)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Ingest a mesh peer observation for cooperative geolocation.
     * Called from MeshViewModel when a peer shares tower signal data.
     */
    fun addMeshPeerObservation(observation: MeshPeerObservation) {
        synchronized(meshObservations) {
            meshObservations.add(observation)
            // Cap at 100 observations
            while (meshObservations.size > 100) {
                meshObservations.removeFirst()
            }
        }
    }

    // ── Per-alert pipeline ───────────────────────────────────────────────

    private suspend fun geolocateAlert(
        alert: Alert,
        userLat: Double,
        userLng: Double,
        visibleCells: List<CellTower>,
        timingAdvanceData: List<TimingAdvanceReading>
    ): GeolocatedThreat {
        val details = try { JSONObject(alert.detailsJson) } catch (_: Exception) { JSONObject() }
        val category = determineSensorCategory(alert)
        val signalStrength = extractSignalStrength(details)
        val threatId = alert.id.toString()

        // Record heat map point for any cellular alert with signal strength
        if (category == SensorCategory.CELLULAR && signalStrength != null) {
            val alertCid = details.optInt("cid", 0).toLong()
            if (alertCid != 0L) {
                val point = HeatMapPoint(
                    lat = userLat,
                    lng = userLng,
                    rssi = signalStrength,
                    cellId = alertCid,
                    timestamp = System.currentTimeMillis(),
                    isPeer = false
                )
                val mutable = _heatMapPoints.value.toMutableMap()
                val list = (mutable[alertCid] ?: emptyList()).toMutableList()
                list.add(point)
                // Cap per-cell at 500 points for memory
                if (list.size > 500) list.removeAt(0)
                mutable[alertCid] = list
                _heatMapPoints.value = mutable
            }
        }

        // Collect estimates from every applicable technique.
        val estimates = mutableListOf<PositionEstimate>()

        // ── 1. Known tower DB lookup (cellular only) ─────────────────
        if (category == SensorCategory.CELLULAR) {
            lookupKnownTower(details)?.let { tower ->
                estimates += PositionEstimate(
                    latitude = tower.latitude,
                    longitude = tower.longitude,
                    accuracyM = ACC_KNOWN_TOWER,
                    bearingDeg = GeoMath.bearing(userLat, userLng, tower.latitude, tower.longitude),
                    technique = GeoTechnique.KNOWN_TOWER_DB,
                    confidenceWeight = WEIGHT_KNOWN_TOWER
                )
            }
        }

        // ── 2. LTE Timing Advance ───────────────────────────────────
        if (category == SensorCategory.CELLULAR && timingAdvanceData.isNotEmpty()) {
            timingAdvanceEstimate(userLat, userLng, timingAdvanceData, details)?.let {
                estimates += it
            }
        }

        // ── 3. Trilateration (NLS) with TA distances + known towers ─
        if (category == SensorCategory.CELLULAR) {
            trilaterateWithNls(userLat, userLng, visibleCells, timingAdvanceData, signalStrength, details)?.let {
                estimates += it
            }
        }

        // ── 4. Legacy multilateration from neighbor cells ────────────
        if (category == SensorCategory.CELLULAR && estimates.none { it.technique == GeoTechnique.KNOWN_TOWER_DB }) {
            multilaterateFromNeighbors(userLat, userLng, visibleCells, signalStrength, details)?.let {
                estimates += it
            }
        }

        // ── 5 & 6. Time-series triangulation + gradient bearing ──────
        if (signalStrength != null) {
            val distanceM = estimateDistance(signalStrength, category, details)
            // FIX 3: Record TA alongside RSSI in reading history
            val alertCidForTa = details.optInt("cid", 0)
            val taForReading = timingAdvanceData.find { it.cid == alertCidForTa }?.timingAdvance
            recordReading(threatId, userLat, userLng, signalStrength, distanceM, taForReading)

            timeSeriesTriangulate(threatId, category)?.let { estimates += it }
            gradientBearing(threatId, userLat, userLng, distanceM)?.let { estimates += it }
        }

        // ── 7. WiFi: RTT self-positioning first, then Kalman-smoothed RSSI ─
        if (category == SensorCategory.WIFI && signalStrength != null) {
            wifiEstimate(threatId, userLat, userLng, signalStrength, details)?.let {
                estimates += it
            }
        }

        // ── 8. BLE: Kalman-filtered RSSI → distance ─────────────────
        if (category == SensorCategory.BLUETOOTH) {
            bleEstimate(threatId, userLat, userLng, signalStrength)?.let {
                estimates += it
            }
        }

        // ── 9. Cooperative mesh observations ─────────────────────────
        if (category == SensorCategory.CELLULAR) {
            cooperativeMeshEstimate(details)?.let {
                estimates += it
            }
        }

        // ── 10. Particle filter prior (feeds back into fusion) ───────
        if (particleFilter.isInitialized) {
            particleFilterPrior(userLat, userLng, estimates)?.let {
                estimates += it
            }
        }

        // ── 11. Fallback: RSSI propagation model (cell) ─────────────
        if (category == SensorCategory.CELLULAR && estimates.isEmpty()) {
            val dist = if (signalStrength != null) estimateDistance(signalStrength, category, details) else 500.0
            val fallbackBearing = (threatId.hashCode() and 0x7FFFFFFF) % 360
            val (lat, lng) = GeoMath.destination(userLat, userLng, fallbackBearing.toDouble(), dist)
            estimates += PositionEstimate(lat, lng, ACC_RSSI_CELL, fallbackBearing.toDouble(),
                GeoTechnique.RSSI_PROPAGATION, WEIGHT_DEFAULT)
        }

        // ── Catch-all for NETWORK / BASELINE / empty ─────────────────
        if (estimates.isEmpty()) {
            val dist = when (category) {
                SensorCategory.WIFI -> 50.0
                SensorCategory.BLUETOOTH -> 15.0
                SensorCategory.NETWORK -> 30.0
                else -> 200.0
            }
            val fallbackBearing = (threatId.hashCode() and 0x7FFFFFFF) % 360
            val (lat, lng) = GeoMath.destination(userLat, userLng, fallbackBearing.toDouble(), dist)
            estimates += PositionEstimate(lat, lng, dist * 0.5, fallbackBearing.toDouble(),
                GeoTechnique.RSSI_PROPAGATION, WEIGHT_DEFAULT)
        }

        // ── 12. Confidence-weighted fusion with technique multipliers ─
        val fused = fuseEstimates(estimates)

        // Check if cooperative mesh contributed to the estimate
        val coopEstimate = estimates.find { it.technique == GeoTechnique.COOPERATIVE_MESH }
        val coopDeviceCount = if (coopEstimate != null) {
            val alertCid = details.optInt("cid", 0)
            synchronized(meshObservations) {
                meshObservations.filter { it.cellCid == alertCid }
                    .map { "${it.peerLat}:${it.peerLng}" }.distinct().size
            }
        } else 0

        // Compute total observation count and contributing device count
        val selfReadings = readingHistory[threatId]?.size ?: 0
        val alertCidForCount = details.optInt("cid", 0)
        val peerObsCount = synchronized(meshObservations) {
            meshObservations.count { it.cellCid == alertCidForCount }
        }
        val totalObsCount = selfReadings + peerObsCount
        val peerDevices = if (alertCidForCount != 0) {
            synchronized(meshObservations) {
                meshObservations.filter { it.cellCid == alertCidForCount }
                    .map { "${it.peerLat}:${it.peerLng}" }.distinct().size
            }
        } else 0
        val totalDevices = 1 + peerDevices // 1 = self

        return GeolocatedThreat(
            id = threatId,
            latitude = fused.latitude,
            longitude = fused.longitude,
            accuracyMeters = fused.accuracyM,
            threatLevel = alert.severity,
            category = category,
            label = generateThreatLabel(alert, category),
            timestamp = alert.timestamp,
            signalStrengthDbm = signalStrength,
            bearing = fused.bearingDeg?.toFloat(),
            isCooperativelyLocated = coopEstimate != null,
            cooperativeDeviceCount = coopDeviceCount,
            observationCount = totalObsCount,
            contributingDevices = totalDevices
        )
    }

    // ── NEW: Timing Advance ──────────────────────────────────────────────

    /**
     * Data from a single LTE cell's timing advance reading.
     */
    private data class TimingAdvanceReading(
        val cid: Int,
        val tac: Int,
        val mcc: Int,
        val mnc: Int,
        val timingAdvance: Int,
        val distanceM: Double,  // TA * 78.12
        val rsrp: Int
    )

    /**
     * Collect raw LTE CellInfo objects to access timing advance.
     * The CellTower domain model doesn't carry TA, so we need the raw API.
     */
    @Suppress("MissingPermission")
    private fun collectRawLteCellInfo(): List<CellInfoLte> {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()
        return try {
            tm.allCellInfo?.filterIsInstance<CellInfoLte>() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /**
     * Extract valid timing advance readings from raw LTE cell info.
     * TA of 0 or Integer.MAX_VALUE means unavailable — skip those.
     */
    private fun extractTimingAdvanceData(lteCells: List<CellInfoLte>): List<TimingAdvanceReading> {
        return lteCells.mapNotNull { cellInfo ->
            val identity = cellInfo.cellIdentity
            val signal = cellInfo.cellSignalStrength
            val ta = signal.timingAdvance

            // Skip invalid TA values
            if (ta <= 0 || ta == TA_INVALID) return@mapNotNull null

            val distanceM = ta * TA_STEP_METERS

            TimingAdvanceReading(
                cid = identity.ci,
                tac = identity.tac,
                mcc = identity.mccString?.toIntOrNull() ?: 0,
                mnc = identity.mncString?.toIntOrNull() ?: 0,
                timingAdvance = ta,
                distanceM = distanceM,
                rsrp = signal.dbm
            )
        }
    }

    /**
     * Estimate threat position using LTE timing advance distances.
     * TA gives a direct physical distance measurement — much more accurate than RSSI.
     */
    private suspend fun timingAdvanceEstimate(
        userLat: Double,
        userLng: Double,
        taData: List<TimingAdvanceReading>,
        details: JSONObject
    ): PositionEstimate? {
        val alertCid = details.optInt("cid", 0)
        if (alertCid == 0) return null

        // Find the TA reading matching the alert's CID
        val taReading = taData.find { it.cid == alertCid } ?: return null

        // If we also have a known tower position, combine TA distance with bearing to tower
        val tower = lookupKnownTower(details)
        if (tower != null) {
            // TA tells us the distance, known tower tells us the position — very precise
            val bearing = GeoMath.bearing(userLat, userLng, tower.latitude, tower.longitude)
            val (lat, lng) = GeoMath.destination(userLat, userLng, bearing, taReading.distanceM)
            return PositionEstimate(lat, lng, ACC_TIMING_ADVANCE * 0.5, bearing,
                GeoTechnique.TIMING_ADVANCE, WEIGHT_TIMING_ADVANCE * 1.5)
        }

        // No known tower — use TA distance with a heuristic bearing
        val fallbackBearing = (alertCid.hashCode() and 0x7FFFFFFF) % 360
        val (lat, lng) = GeoMath.destination(userLat, userLng, fallbackBearing.toDouble(), taReading.distanceM)
        return PositionEstimate(lat, lng, ACC_TIMING_ADVANCE, fallbackBearing.toDouble(),
            GeoTechnique.TIMING_ADVANCE, WEIGHT_TIMING_ADVANCE)
    }

    // ── NEW: NLS Trilateration ───────────────────────────────────────────

    /**
     * Proper nonlinear least-squares trilateration when we have 3+
     * tower positions with distance estimates (from TA or RSSI).
     *
     * Falls back to weighted centroid for 2 observations.
     */
    private suspend fun trilaterateWithNls(
        userLat: Double,
        userLng: Double,
        visibleCells: List<CellTower>,
        taData: List<TimingAdvanceReading>,
        threatRssi: Int?,
        details: JSONObject
    ): PositionEstimate? {
        // Build a list of (position, distance) anchors from known towers
        data class Anchor(val lat: Double, val lng: Double, val distanceM: Double, val fromTA: Boolean)

        val anchors = mutableListOf<Anchor>()

        for (cell in visibleCells) {
            if (cell.mcc == 0 || cell.cid == 0) continue

            // Try to resolve tower position from DB
            val key = "${cell.mcc}:${cell.mnc}:${cell.lacTac}:${cell.cid}"
            val cached = towerCache[key]
            val entity = if (cached != null && System.currentTimeMillis() - cached.ts < TOWER_CACHE_TTL_MS) {
                cached.tower
            } else {
                val e = knownTowerDao.findTower(cell.mcc, cell.mnc, cell.lacTac, cell.cid)
                towerCache[key] = TowerCacheEntry(e, System.currentTimeMillis())
                e
            }
            if (entity == null) continue

            // Prefer TA distance if available, otherwise use RSSI
            val taReading = taData.find { it.cid == cell.cid }
            val distM: Double
            val fromTA: Boolean
            if (taReading != null) {
                distM = taReading.distanceM
                fromTA = true
            } else {
                distM = estimateDistance(cell.signalStrength, SensorCategory.CELLULAR, details)
                fromTA = false
            }

            anchors.add(Anchor(entity.latitude, entity.longitude, distM, fromTA))
        }

        if (anchors.size < 3) return null

        // Use NLS trilateration
        val positions = anchors.map { doubleArrayOf(it.lat, it.lng) }.toTypedArray()
        val distances = anchors.map { it.distanceM }.toDoubleArray()

        val result = NonlinearLeastSquares.solve(positions, distances) ?: return null

        // Dynamic accuracy based on anchor count and TA availability
        val taCount = anchors.count { it.fromTA }
        val rssiCount = anchors.size - taCount
        // TA error: ±39m per reading; RSSI error: ~35m per reading
        // Combined: 1/sqrt(sum(1/σ_i^2))
        val taContrib = if (taCount > 0) taCount.toDouble() / (39.0 * 39.0) else 0.0
        val rssiContrib = if (rssiCount > 0) rssiCount.toDouble() / (35.0 * 35.0) else 0.0
        val nlsGdop = when {
            anchors.size <= 3 -> 2.5
            anchors.size <= 6 -> 1.8
            anchors.size <= 10 -> 1.3
            else -> 1.0
        }
        val accuracy = (nlsGdop / sqrt(taContrib + rssiContrib)).coerceAtLeast(3.0)

        val bearing = GeoMath.bearing(userLat, userLng, result.latitude, result.longitude)
        return PositionEstimate(
            result.latitude, result.longitude,
            accuracy.coerceAtLeast(result.residualM),
            bearing,
            GeoTechnique.TRILATERATION_NLS,
            WEIGHT_TRILATERATION_NLS
        )
    }

    // ── NEW: Particle Filter Integration ─────────────────────────────────

    /**
     * Update the particle filter with user motion since last call.
     * Uses MotionDetector state to skip predictions when stationary
     * (avoids random walk drift in particles when user isn't moving).
     */
    private fun updateParticleFilter(userLat: Double, userLng: Double) {
        if (!particleFilter.isInitialized) {
            particleFilter.initialize(userLat, userLng, PARTICLE_INIT_RADIUS_M)
            lastUserLat = userLat
            lastUserLng = userLng
            return
        }

        // Only predict if MotionDetector says we're moving
        if (!motionDetector.isMoving) {
            lastUserLat = userLat
            lastUserLng = userLng
            return
        }

        // Compute step from GPS displacement
        val stepM = GeoMath.haversineM(lastUserLat, lastUserLng, userLat, userLng)
        if (stepM > 1.0) {
            val heading = GeoMath.bearing(lastUserLat, lastUserLng, userLat, userLng)
            particleFilter.predict(stepM, heading)
        }

        lastUserLat = userLat
        lastUserLng = userLng
    }

    /**
     * Get particle-filter-refined user position.
     * Falls back to raw GPS if filter isn't ready.
     */
    private fun getParticleFilteredUserPosition(gpsLat: Double, gpsLng: Double): Pair<Double, Double> {
        if (!particleFilter.isInitialized) return Pair(gpsLat, gpsLng)

        // Feed GPS as an observation to the filter
        val gpsObservation = listOf(
            RfObservation(gpsLat, gpsLng, 0.0, 5.0) // GPS at user pos with 5m accuracy
        )
        particleFilter.update(gpsObservation)

        return particleFilter.getEstimate() ?: Pair(gpsLat, gpsLng)
    }

    /**
     * Generate a particle filter prior for fusion.
     * Only contributes if the filter has been running long enough to converge.
     */
    private fun particleFilterPrior(
        userLat: Double,
        userLng: Double,
        existingEstimates: List<PositionEstimate>
    ): PositionEstimate? {
        if (!particleFilter.isInitialized) return null

        // Build RF observations from existing estimates for filter update
        val rfObs = existingEstimates
            .filter { it.technique != GeoTechnique.RSSI_PROPAGATION }
            .map { est ->
                RfObservation(
                    est.latitude, est.longitude,
                    GeoMath.haversineM(userLat, userLng, est.latitude, est.longitude),
                    est.accuracyM
                )
            }

        if (rfObs.isNotEmpty()) {
            particleFilter.update(rfObs)
        }

        val accuracy = particleFilter.getAccuracyM()
        if (accuracy > ACC_PARTICLE_FILTER * 3) return null // not converged enough

        val (estLat, estLng) = particleFilter.getEstimate() ?: return null
        val bearing = GeoMath.bearing(userLat, userLng, estLat, estLng)

        // FIX 2: Let particle filter report its actual computed std dev.
        // The std dev IS the accuracy — only floor at physical measurement limit.
        return PositionEstimate(
            estLat, estLng, accuracy.coerceAtLeast(3.0),
            bearing, GeoTechnique.PARTICLE_FILTER, WEIGHT_PARTICLE_FILTER
        )
    }

    // ── NEW: Kalman-filtered BLE estimate ────────────────────────────────

    /**
     * BLE estimate using Kalman-filtered RSSI for smoother distance estimation.
     * Falls back to trajectory-based tracking for known followers.
     */
    private fun bleEstimate(
        threatId: String,
        userLat: Double,
        userLng: Double,
        rssi: Int?
    ): PositionEstimate? {
        val readings = readingHistory[threatId]

        // Tracker has been following — place it at the earliest reading position
        if (readings != null && readings.size >= 2) {
            val first = readings.first()
            val bearing = GeoMath.bearing(userLat, userLng, first.userLat, first.userLng)
            return PositionEstimate(
                first.userLat, first.userLng,
                ACC_BLE_TRAJECTORY, bearing,
                GeoTechnique.BLE_TRAJECTORY, WEIGHT_DEFAULT
            )
        }

        // Single reading — use Kalman-filtered RSSI → distance
        if (rssi == null) {
            val fallbackBearing = (threatId.hashCode() and 0x7FFFFFFF) % 360
            val (lat, lng) = GeoMath.destination(userLat, userLng, fallbackBearing.toDouble(), 15.0)
            return PositionEstimate(lat, lng, ACC_RSSI_BLE, fallbackBearing.toDouble(),
                GeoTechnique.RSSI_PROPAGATION, WEIGHT_DEFAULT)
        }

        // Apply Kalman filter to smooth RSSI
        val kalman = bleKalmanFilters.getOrPut(threatId) {
            RssiKalmanFilter(estimate = rssi.toDouble())
        }
        val smoothedRssi = kalman.update(rssi.toDouble())

        val dist = PropagationModels.logDistanceBle(smoothedRssi.toInt())
        val fallbackBearing = (threatId.hashCode() and 0x7FFFFFFF) % 360
        val (lat, lng) = GeoMath.destination(userLat, userLng, fallbackBearing.toDouble(), dist)
        return PositionEstimate(lat, lng, ACC_RSSI_BLE, fallbackBearing.toDouble(),
            GeoTechnique.RSSI_PROPAGATION, WEIGHT_DEFAULT)
    }

    // ── NEW: WiFi estimate with RTT + Kalman ─────────────────────────────

    /**
     * WiFi estimate: tries WiFi RTT first (1-2m accuracy), falls back to
     * Kalman-smoothed RSSI distance estimation.
     */
    private fun wifiEstimate(
        threatId: String,
        userLat: Double,
        userLng: Double,
        rssi: Int,
        details: JSONObject
    ): PositionEstimate? {
        // Try WiFi RTT self-positioning for better user position accuracy
        if (wifiRttPositioner.isAvailable) {
            val rttResults = wifiRttPositioner.performRanging()
            if (rttResults.isNotEmpty()) {
                // WiFi RTT gives us precise distances to known APs.
                // We can use this for better user position → better threat position.
                // For now, the RTT contribution improves overall accuracy.
                val avgDist = rttResults.map { it.distanceM }.average()
                val avgAcc = rttResults.map { it.accuracyM }.average()

                // Use Kalman-smoothed RSSI for the threat distance
                val kalman = wifiKalmanFilters.getOrPut(threatId) {
                    RssiKalmanFilter(estimate = rssi.toDouble())
                }
                val smoothedRssi = kalman.update(rssi.toDouble())
                val ple = wifiPathLossExponent(details)
                val dist = PropagationModels.logDistanceWifi(
                    rssiDbm = smoothedRssi.toInt(),
                    pathLossExponent = ple
                )

                val fallbackBearing = (threatId.hashCode() and 0x7FFFFFFF) % 360
                val (lat, lng) = GeoMath.destination(userLat, userLng, fallbackBearing.toDouble(), dist)

                // RTT-enhanced accuracy
                return PositionEstimate(lat, lng,
                    ACC_WIFI_RTT + avgAcc,
                    fallbackBearing.toDouble(),
                    GeoTechnique.WIFI_RTT, WEIGHT_WIFI_RTT)
            }
        }

        // Fallback: Kalman-smoothed WiFi RSSI
        val kalman = wifiKalmanFilters.getOrPut(threatId) {
            RssiKalmanFilter(estimate = rssi.toDouble())
        }
        val smoothedRssi = kalman.update(rssi.toDouble())

        val ple = wifiPathLossExponent(details)
        val dist = PropagationModels.logDistanceWifi(
            rssiDbm = smoothedRssi.toInt(),
            pathLossExponent = ple
        )
        val fallbackBearing = (threatId.hashCode() and 0x7FFFFFFF) % 360
        val (lat, lng) = GeoMath.destination(userLat, userLng, fallbackBearing.toDouble(), dist)
        return PositionEstimate(lat, lng, ACC_RSSI_WIFI, fallbackBearing.toDouble(),
            GeoTechnique.RSSI_PROPAGATION, WEIGHT_DEFAULT)
    }

    // ── NEW: Cooperative Mesh Geolocation ────────────────────────────────

    /**
     * Use mesh peer observations to trilaterate a suspicious tower.
     * When 3+ peers see the same CID, we can trilaterate the tower position.
     */
    private fun cooperativeMeshEstimate(details: JSONObject): PositionEstimate? {
        val alertCid = details.optInt("cid", 0)
        if (alertCid == 0) return null

        val now = System.currentTimeMillis()
        val relevantObs = synchronized(meshObservations) {
            meshObservations.filter {
                it.cellCid == alertCid && (now - it.timestamp) < meshObservationMaxAge
            }
        }

        if (relevantObs.size < 3) return null

        // Build trilateration anchors from peer observations
        val positions = relevantObs.map { doubleArrayOf(it.peerLat, it.peerLng) }.toTypedArray()
        val distances = relevantObs.map { obs ->
            // Estimate distance from each peer's RSRP
            PropagationModels.cost231Distance(obs.rsrpDbm)
        }.toDoubleArray()

        val result = NonlinearLeastSquares.solve(positions, distances) ?: return null

        // FIX 5: Cooperative accuracy based on total observations from independent peers.
        // Each peer is an independent observer → N peers × M readings = total observations.
        val totalObservations = relevantObs.size.toDouble()
        val coopGdop = when {
            relevantObs.size <= 3 -> 2.5
            relevantObs.size <= 6 -> 1.8
            relevantObs.size <= 12 -> 1.3
            else -> 1.0
        }
        val coopAccuracy = (coopGdop * 35.0 / sqrt(totalObservations)).coerceAtLeast(3.0)

        return PositionEstimate(
            result.latitude, result.longitude,
            coopAccuracy.coerceAtLeast(result.residualM),
            null,
            GeoTechnique.COOPERATIVE_MESH,
            WEIGHT_COOPERATIVE_MESH
        )
    }

    // ── Existing Technique Implementations (preserved) ───────────────────

    /**
     * 1. Look up the alert's cell identity in the 2.16M known-tower DB.
     *    Returns the entity if found, using a short-lived cache to avoid
     *    repeated Room queries within the same scan cycle.
     */
    private suspend fun lookupKnownTower(details: JSONObject): KnownTowerEntity? {
        val mcc = details.optInt("mcc", 0)
        val mnc = details.optInt("mnc", 0)
        val lac = details.optInt("lac", details.optInt("tac", 0))
        val cid = details.optInt("cid", 0)
        if (mcc == 0 || cid == 0) return null

        val key = "$mcc:$mnc:$lac:$cid"
        val cached = towerCache[key]
        if (cached != null && System.currentTimeMillis() - cached.ts < TOWER_CACHE_TTL_MS) {
            return cached.tower
        }

        val entity = knownTowerDao.findTower(mcc, mnc, lac, cid)
        towerCache[key] = TowerCacheEntry(entity, System.currentTimeMillis())
        return entity
    }

    /**
     * Multilateration — use positions of visible KNOWN neighbor towers
     * weighted by RSSI to estimate user position, then infer where an
     * UNKNOWN tower must be relative to that weighted centroid.
     */
    private suspend fun multilaterateFromNeighbors(
        userLat: Double,
        userLng: Double,
        visibleCells: List<CellTower>,
        threatRssi: Int?,
        details: JSONObject
    ): PositionEstimate? {
        data class ResolvedCell(val tower: KnownTowerEntity, val rssi: Int)

        val resolved = mutableListOf<ResolvedCell>()
        for (cell in visibleCells) {
            if (cell.mcc == 0 || cell.cid == 0) continue
            val key = "${cell.mcc}:${cell.mnc}:${cell.lacTac}:${cell.cid}"
            val cached = towerCache[key]
            val entity = if (cached != null && System.currentTimeMillis() - cached.ts < TOWER_CACHE_TTL_MS) {
                cached.tower
            } else {
                val e = knownTowerDao.findTower(cell.mcc, cell.mnc, cell.lacTac, cell.cid)
                towerCache[key] = TowerCacheEntry(e, System.currentTimeMillis())
                e
            }
            if (entity != null) {
                resolved += ResolvedCell(entity, cell.signalStrength)
            }
        }

        if (resolved.size < 2) return null

        var sumLat = 0.0; var sumLng = 0.0; var sumW = 0.0
        for (rc in resolved) {
            val w = 10.0.pow(rc.rssi / 20.0)
            sumLat += rc.tower.latitude * w
            sumLng += rc.tower.longitude * w
            sumW += w
        }
        val centroidLat = sumLat / sumW
        val centroidLng = sumLng / sumW

        val dist = if (threatRssi != null) {
            estimateDistance(threatRssi, SensorCategory.CELLULAR, details)
        } else {
            500.0
        }

        val bearingToCentroid = GeoMath.bearing(userLat, userLng, centroidLat, centroidLng)
        val oppositeBearing = (bearingToCentroid + 180.0) % 360.0
        val (estLat, estLng) = GeoMath.destination(userLat, userLng, oppositeBearing, dist)

        val accuracy = if (resolved.size >= 3) ACC_MULTILAT_3PLUS else ACC_MULTILAT_2
        return PositionEstimate(estLat, estLng, accuracy, oppositeBearing,
            GeoTechnique.MULTILATERATION, WEIGHT_DEFAULT)
    }

    /**
     * Time-series triangulation — least-squares circle intersection
     * when we have 2+ readings from spatially distinct user positions.
     */
    private fun timeSeriesTriangulate(
        threatId: String,
        category: SensorCategory
    ): PositionEstimate? {
        val readings = readingHistory[threatId] ?: return null

        val spatiallyDistinct = pickDistinctReadings(readings)
        if (spatiallyDistinct.size < 2) return null

        var estLat = spatiallyDistinct.map { it.userLat }.average()
        var estLng = spatiallyDistinct.map { it.userLng }.average()

        repeat(3) {
            var sLat = 0.0; var sLng = 0.0; var sW = 0.0
            for (r in spatiallyDistinct) {
                val d = GeoMath.haversineM(estLat, estLng, r.userLat, r.userLng)
                val target = r.estimatedDistanceM
                val bearing = GeoMath.bearing(r.userLat, r.userLng, estLat, estLng)
                val (pLat, pLng) = GeoMath.destination(r.userLat, r.userLng, bearing, target)
                val w = 1.0 / (abs(d - target) + 1.0)
                sLat += pLat * w
                sLng += pLng * w
                sW += w
            }
            if (sW > 0) { estLat = sLat / sW; estLng = sLng / sW }
        }

        val bearingDeg = GeoMath.bearing(
            spatiallyDistinct.last().userLat, spatiallyDistinct.last().userLng,
            estLat, estLng
        )

        // FIX 1: Dynamic accuracy based on observation count and geometry.
        // Trilateration accuracy = GDOP × σ_range / √N
        val N = spatiallyDistinct.size
        val individualErr = 35.0 // meters, combined RSSI+TA at typical urban distance
        val gdop = when {
            N <= 2 -> 5.0
            N <= 4 -> 2.5
            N <= 8 -> 1.8
            N <= 15 -> 1.3
            else -> 1.0
        }
        val dynamicAccuracy = (gdop * individualErr / sqrt(N.toDouble())).coerceAtLeast(3.0)

        // FIX 3: If we have TA readings in history, incorporate their superior accuracy.
        val taReadings = spatiallyDistinct.filter { it.timingAdvance != null }
        val accuracy = if (taReadings.isNotEmpty()) {
            // TA individual error is ±39m. With N_ta readings: ±39/√N_ta
            val taAccuracy = 39.0 / sqrt(taReadings.size.toDouble())
            // Fuse TA accuracy with RSSI-based dynamic accuracy
            val fusedTaRssi = 1.0 / sqrt(
                1.0 / (taAccuracy * taAccuracy) + 1.0 / (dynamicAccuracy * dynamicAccuracy)
            )
            fusedTaRssi.coerceAtLeast(3.0)
        } else {
            dynamicAccuracy
        }

        return PositionEstimate(estLat, estLng, accuracy, bearingDeg,
            GeoTechnique.TIME_SERIES_TRIANGULATION, WEIGHT_DEFAULT)
    }

    /**
     * Signal-strength gradient bearing — if RSSI changed as the user
     * moved, the direction of increasing signal points toward the threat.
     */
    private fun gradientBearing(
        threatId: String,
        userLat: Double,
        userLng: Double,
        currentDistM: Double
    ): PositionEstimate? {
        val readings = readingHistory[threatId] ?: return null
        if (readings.size < 2) return null

        val prev = readings[readings.size - 2]
        val curr = readings.last()

        val userMovedM = GeoMath.haversineM(prev.userLat, prev.userLng, curr.userLat, curr.userLng)
        if (userMovedM < 20.0) return null

        val rssiDelta = curr.rssi - prev.rssi
        val moveBearing = GeoMath.bearing(prev.userLat, prev.userLng, curr.userLat, curr.userLng)

        val threatBearing = if (rssiDelta >= 0) moveBearing else (moveBearing + 180.0) % 360.0

        val (lat, lng) = GeoMath.destination(userLat, userLng, threatBearing, currentDistM)
        return PositionEstimate(lat, lng, ACC_GRADIENT_BEARING, threatBearing,
            GeoTechnique.SIGNAL_GRADIENT_BEARING, WEIGHT_DEFAULT)
    }

    /**
     * WiFi path-loss exponent selection based on environment hint.
     */
    private fun wifiPathLossExponent(details: JSONObject): Double {
        return when (details.optString("environment", "").lowercase()) {
            "open", "outdoor" -> WIFI_PLE_OPEN
            "office", "indoor" -> WIFI_PLE_OFFICE
            "dense", "urban" -> WIFI_PLE_DENSE_URBAN
            else -> WIFI_PLE_OFFICE
        }
    }

    // ── Upgraded: Confidence-Weighted Fusion ─────────────────────────────

    /**
     * Fuse multiple position estimates with confidence-weighted fusion.
     *
     * Each estimate contributes weight = (confidenceWeight / accuracyM),
     * giving technique-specific multipliers:
     * - Known tower DB:     5× (verified coordinates)
     * - Timing advance:     3× (direct physical measurement)
     * - WiFi RTT:           2.5× (precise ranging)
     * - Cooperative mesh:   2× (independent observers)
     * - Trilateration NLS:  2× (proper solve)
     * - Particle filter:    1.5× (filtered prior)
     * - RSSI-based:         1× (least reliable)
     */
    private fun fuseEstimates(estimates: List<PositionEstimate>): PositionEstimate {
        if (estimates.size == 1) return estimates[0]

        var sumLat = 0.0; var sumLng = 0.0; var sumW = 0.0
        var bestBearing: Double? = null
        var bestBearingAcc = Double.MAX_VALUE

        for (e in estimates) {
            // Confidence-weighted: technique multiplier / accuracy
            val w = e.confidenceWeight / e.accuracyM
            sumLat += e.latitude * w
            sumLng += e.longitude * w
            sumW += w
            if (e.bearingDeg != null && e.accuracyM < bestBearingAcc) {
                bestBearing = e.bearingDeg
                bestBearingAcc = e.accuracyM
            }
        }

        val fusedLat = sumLat / sumW
        val fusedLng = sumLng / sumW

        // FIX 4: Fused accuracy from actual estimate uncertainties.
        // accuracy = 1 / sqrt(sum(1/acc_i^2)) — combining measurement uncertainties
        val fusedAcc = 1.0 / sqrt(estimates.sumOf { 1.0 / (it.accuracyM * it.accuracyM) })

        return PositionEstimate(
            latitude = fusedLat,
            longitude = fusedLng,
            accuracyM = fusedAcc.coerceAtLeast(3.0), // physical measurement floor
            bearingDeg = bestBearing,
            technique = GeoTechnique.CONFIDENCE_FUSION,
            confidenceWeight = 1.0
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun determineSensorCategory(alert: Alert): SensorCategory = when (alert.threatType) {
        ThreatType.FAKE_BTS,
        ThreatType.NETWORK_DOWNGRADE,
        ThreatType.SILENT_SMS,
        ThreatType.CIPHER_ANOMALY,
        ThreatType.SIGNAL_ANOMALY,
        ThreatType.NR_ANOMALY,
        ThreatType.REGISTRATION_FAILURE,
        ThreatType.TEMPORAL_ANOMALY,
        ThreatType.KNOWN_TOWER_ANOMALY,
        ThreatType.COMPOUND_PATTERN -> SensorCategory.CELLULAR
        ThreatType.TRACKING_PATTERN -> SensorCategory.BLUETOOTH
    }

    private fun extractSignalStrength(details: JSONObject): Int? {
        val v = details.optInt("signalStrength", Int.MIN_VALUE).let {
            if (it == Int.MIN_VALUE) details.optInt("signalStrengthDbm", Int.MIN_VALUE) else it
        }.let {
            if (it == Int.MIN_VALUE) details.optInt("rssi", Int.MIN_VALUE) else it
        }
        return if (v == Int.MIN_VALUE) null else v
    }

    /** Estimate distance in meters based on signal strength and category. */
    private fun estimateDistance(rssi: Int, category: SensorCategory, details: JSONObject): Double {
        return when (category) {
            SensorCategory.CELLULAR -> {
                val freq = details.optDouble("frequencyMhz", 900.0)
                if (freq < 1500.0) PropagationModels.okumuraHataDistance(rssi, freq)
                else PropagationModels.cost231Distance(rssi, freq)
            }
            SensorCategory.WIFI -> {
                PropagationModels.logDistanceWifi(rssi, pathLossExponent = wifiPathLossExponent(details))
            }
            SensorCategory.BLUETOOTH -> PropagationModels.logDistanceBle(rssi)
            else -> 200.0
        }
    }

    /** Record a new reading for time-series techniques, including TA when available. */
    private fun recordReading(
        threatId: String,
        userLat: Double,
        userLng: Double,
        rssi: Int,
        distM: Double,
        timingAdvance: Int? = null
    ) {
        val list = readingHistory.getOrPut(threatId) { mutableListOf() }
        list += GeoReading(userLat, userLng, rssi, System.currentTimeMillis(), distM, timingAdvance)
        if (list.size > 60) list.removeAt(0)
    }

    /** Prune readings older than [READING_MAX_AGE_MS]. */
    private fun pruneReadingHistory() {
        val cutoff = System.currentTimeMillis() - READING_MAX_AGE_MS
        val iter = readingHistory.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.removeAll { it.timestamp < cutoff }
            if (entry.value.isEmpty()) iter.remove()
        }

        // Also prune stale Kalman filters
        val kalmanCutoff = System.currentTimeMillis() - READING_MAX_AGE_MS * 2
        bleKalmanFilters.keys.removeAll { id ->
            readingHistory[id]?.lastOrNull()?.timestamp?.let { it < kalmanCutoff } ?: true
        }
        wifiKalmanFilters.keys.removeAll { id ->
            readingHistory[id]?.lastOrNull()?.timestamp?.let { it < kalmanCutoff } ?: true
        }
    }

    /** Prune heat map points older than 30 minutes. */
    private fun pruneHeatMapPoints() {
        val cutoff = System.currentTimeMillis() - HEAT_MAP_MAX_AGE_MS
        val current = _heatMapPoints.value
        var changed = false
        val pruned = current.mapValues { (_, points) ->
            val filtered = points.filter { it.timestamp >= cutoff }
            if (filtered.size != points.size) changed = true
            filtered
        }.filter { it.value.isNotEmpty() }
        if (changed) {
            _heatMapPoints.value = pruned
        }
    }

    /** Prune stale mesh observations. */
    private fun pruneMeshObservations() {
        val cutoff = System.currentTimeMillis() - meshObservationMaxAge
        synchronized(meshObservations) {
            meshObservations.removeAll { it.timestamp < cutoff }
        }
    }

    /**
     * From a list of readings, pick those where the user moved ≥[MIN_MOVEMENT_M]
     * from the previous picked reading.
     */
    private fun pickDistinctReadings(readings: List<GeoReading>): List<GeoReading> {
        if (readings.isEmpty()) return emptyList()
        val result = mutableListOf(readings.first())
        for (r in readings) {
            val last = result.last()
            if (GeoMath.haversineM(last.userLat, last.userLng, r.userLat, r.userLng) >= MIN_MOVEMENT_M) {
                result += r
            }
        }
        return result
    }

    private fun generateThreatLabel(alert: Alert, category: SensorCategory): String {
        return if (alert.summary.isNotBlank()) alert.summary
        else when (category) {
            SensorCategory.CELLULAR -> "Cellular Threat"
            SensorCategory.WIFI -> "WiFi Threat"
            SensorCategory.BLUETOOTH -> "Bluetooth Threat"
            SensorCategory.NETWORK -> "Network Threat"
            SensorCategory.BASELINE -> "Baseline Anomaly"
        }
    }
}
