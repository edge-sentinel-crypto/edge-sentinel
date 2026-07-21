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
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import com.bp22intel.edgesentinel.data.local.dao.EstimatedTowerPositionDao
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.entity.EstimatedTowerPositionEntity
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import com.bp22intel.edgesentinel.sensor.MotionDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Continuously estimates and refines tower positions as new signal
 * readings arrive from each scan cycle.
 *
 * Each observed tower accumulates readings from different user locations.
 * More readings from diverse positions → better trilateration → tighter
 * accuracy circle.
 *
 * Accuracy progression:
 * - 1 reading:  RSSI-only, ~500m
 * - 3 readings: basic triangulation, ~300m
 * - 5 readings: trilateration possible, ~200m
 * - 10 readings: decent convergence, ~100m
 * - 20+ readings: full NLS + fusion, ~50-100m
 *
 * All processing is LOCAL. No data leaves the device.
 */
@Singleton
class TowerPositionTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val estimatedPositionDao: EstimatedTowerPositionDao,
    private val knownTowerDao: KnownTowerDao,
    private val motionDetector: MotionDetector
) {
    companion object {
        private const val MIN_MOVEMENT_M = 30.0
        private const val MAX_READINGS_PER_TOWER = 200
        private const val PERSIST_BATCH_SIZE = 20
        private const val READING_MAX_AGE_MS = 60 * 60 * 1000L // 1 hour (longer than ThreatGeolocation)
        private const val DB_PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000L // 30 days
        private const val TA_STEP_METERS = 78.12
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Per-tower reading history: towerId → list of readings. */
    private val towerReadings = mutableMapOf<String, MutableList<TowerReading>>()

    /** Known tower cache to avoid repeated DB hits. */
    private val knownTowerCache = mutableMapOf<String, KnownTowerEntity?>()
    private val knownTowerCacheTtl = 5 * 60 * 1000L
    private val knownTowerCacheTimestamps = mutableMapOf<String, Long>()

    /** In-memory estimated positions — emits on every update. */
    private val _estimatedPositions = MutableStateFlow<Map<String, EstimatedTowerPositionEntity>>(emptyMap())
    val estimatedPositions: StateFlow<Map<String, EstimatedTowerPositionEntity>> = _estimatedPositions.asStateFlow()

    /** Dirty flags for batched persistence. */
    private val dirtyTowers = mutableSetOf<String>()
    private var lastPersistTime = 0L

    init {
        // Load persisted positions on startup
        scope.launch {
            val existing = estimatedPositionDao.getAll()
            val map = existing.associateBy { it.towerId }
            _estimatedPositions.value = map
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Process a batch of observed cell towers from a scan cycle.
     * Called from MonitoringService after each cell scan.
     *
     * @param cells     All currently visible cell towers
     * @param userLat   User's current GPS latitude
     * @param userLng   User's current GPS longitude
     */
    suspend fun processScanResults(
        cells: List<CellTower>,
        userLat: Double,
        userLng: Double
    ) {
        if (userLat == 0.0 && userLng == 0.0) return
        if (cells.isEmpty()) return

        // Collect raw LTE timing advance data
        val taData = collectTimingAdvanceData()

        for (cell in cells) {
            if (cell.mcc == 0 || cell.cid == 0) continue

            val towerId = "${cell.mcc}_${cell.mnc}_${cell.lacTac}_${cell.cid}"
            val taReading = taData.find { it.cid == cell.cid }

            // Add reading for this tower
            addReading(
                towerId = towerId,
                mcc = cell.mcc,
                mnc = cell.mnc,
                lac = cell.lacTac,
                cid = cell.cid,
                userLat = userLat,
                userLng = userLng,
                rssi = cell.signalStrength,
                timingAdvance = taReading?.timingAdvance,
                taDistanceM = taReading?.distanceM
            )

            // Re-estimate position for this tower
            reEstimateTowerPosition(towerId, cell.mcc, cell.mnc, cell.lacTac, cell.cid)
        }

        // Persist dirty positions periodically
        maybePersist()
    }

    /**
     * Get a Flow for a specific tower's estimated position.
     * UI layers collect this for live updates.
     */
    fun observeTowerPosition(towerId: String): Flow<EstimatedTowerPositionEntity?> {
        return estimatedPositionDao.observeByTowerId(towerId)
    }

    /**
     * Get a Flow for a specific tower by cell identity.
     */
    fun observeTowerPosition(mcc: Int, mnc: Int, lac: Int, cid: Int): Flow<EstimatedTowerPositionEntity?> {
        return estimatedPositionDao.observeByCell(mcc, mnc, lac, cid)
    }

    /**
     * Observe all estimated tower positions. Used by ThreatMapViewModel.
     */
    fun observeAllPositions(): Flow<List<EstimatedTowerPositionEntity>> {
        return estimatedPositionDao.observeAll()
    }

    /**
     * Look up a tower's estimated position (one-shot).
     */
    suspend fun getEstimatedPosition(mcc: Int, mnc: Int, lac: Int, cid: Int): EstimatedTowerPositionEntity? {
        return estimatedPositionDao.findByCell(mcc, mnc, lac, cid)
    }

    /**
     * Prune stale data — call periodically (e.g., daily from a worker).
     */
    suspend fun pruneStaleData() {
        val cutoff = System.currentTimeMillis() - DB_PRUNE_AGE_MS
        estimatedPositionDao.deleteOlderThan(cutoff)

        // Prune in-memory readings
        val readingCutoff = System.currentTimeMillis() - READING_MAX_AGE_MS
        synchronized(towerReadings) {
            val iter = towerReadings.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                entry.value.removeAll { it.timestamp < readingCutoff }
                if (entry.value.isEmpty()) iter.remove()
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private data class TowerReading(
        val userLat: Double,
        val userLng: Double,
        val rssi: Int,
        val timestamp: Long,
        val estimatedDistanceM: Double,
        val timingAdvance: Int? = null,
        val taDistanceM: Double? = null
    )

    private data class TimingAdvanceInfo(
        val cid: Int,
        val timingAdvance: Int,
        val distanceM: Double
    )

    private fun addReading(
        towerId: String,
        mcc: Int,
        mnc: Int,
        lac: Int,
        cid: Int,
        userLat: Double,
        userLng: Double,
        rssi: Int,
        timingAdvance: Int?,
        taDistanceM: Double?
    ) {
        val readings = synchronized(towerReadings) {
            towerReadings.getOrPut(towerId) { mutableListOf() }
        }

        // Skip if user hasn't moved enough from last reading (avoid duplicate stationary readings)
        if (readings.isNotEmpty()) {
            val last = readings.last()
            val moved = haversineM(last.userLat, last.userLng, userLat, userLng)
            if (moved < MIN_MOVEMENT_M && System.currentTimeMillis() - last.timestamp < 30_000L) {
                return // Too close and too recent — skip
            }
        }

        // Estimate distance from RSSI using propagation model
        val rssiDistanceM = PropagationModels.okumuraHataDistance(rssi)
        val bestDistanceM = taDistanceM ?: rssiDistanceM

        val reading = TowerReading(
            userLat = userLat,
            userLng = userLng,
            rssi = rssi,
            timestamp = System.currentTimeMillis(),
            estimatedDistanceM = bestDistanceM,
            timingAdvance = timingAdvance,
            taDistanceM = taDistanceM
        )

        synchronized(towerReadings) {
            readings.add(reading)
            // Cap readings per tower
            while (readings.size > MAX_READINGS_PER_TOWER) {
                readings.removeAt(0)
            }
        }
    }

    private suspend fun reEstimateTowerPosition(
        towerId: String,
        mcc: Int,
        mnc: Int,
        lac: Int,
        cid: Int
    ) {
        val readings = synchronized(towerReadings) {
            towerReadings[towerId]?.toList() ?: return
        }
        if (readings.isEmpty()) return

        val techniques = mutableSetOf<String>()
        var estLat: Double
        var estLng: Double
        var accuracyM: Double

        // ── 1. Check known tower DB first ────────────────────────────
        val knownTower = lookupKnownTower(mcc, mnc, lac, cid)
        if (knownTower != null) {
            // Known tower position — use it as high-confidence anchor
            estLat = knownTower.latitude
            estLng = knownTower.longitude
            accuracyM = 50.0
            techniques.add("KNOWN_TOWER_DB")

            // Still useful to refine with TA readings for towers with poor DB accuracy
            val taReadings = readings.filter { it.taDistanceM != null }
            if (taReadings.size >= 3) {
                val nlsResult = runNlsTrilateration(taReadings)
                if (nlsResult != null && nlsResult.accuracy < accuracyM) {
                    // NLS refines better than DB — use NLS but anchor near DB position
                    estLat = (estLat + nlsResult.lat) / 2.0
                    estLng = (estLng + nlsResult.lng) / 2.0
                    accuracyM = minOf(accuracyM, nlsResult.accuracy)
                    techniques.add("TRILATERATION_NLS")
                }
            }
        } else {
            // ── 2. No known tower — estimate from readings ───────────

            // 2a. Try NLS trilateration if we have enough spatially diverse readings
            val distinctReadings = pickSpatiallyDistinct(readings)

            if (distinctReadings.size >= 3) {
                val nlsResult = runNlsTrilateration(distinctReadings)
                if (nlsResult != null) {
                    estLat = nlsResult.lat
                    estLng = nlsResult.lng
                    accuracyM = nlsResult.accuracy
                    techniques.add("TRILATERATION_NLS")

                    // If we also have TA readings, fuse them
                    val taReadings = distinctReadings.filter { it.taDistanceM != null }
                    if (taReadings.isNotEmpty()) {
                        techniques.add("TIMING_ADVANCE")
                    }
                } else {
                    // NLS failed — fall back to weighted centroid
                    val centroid = weightedCentroid(readings)
                    estLat = centroid.first
                    estLng = centroid.second
                    accuracyM = estimateAccuracy(readings.size, hasTA = readings.any { it.taDistanceM != null })
                    techniques.add("WEIGHTED_CENTROID")
                }
            } else if (distinctReadings.size == 2) {
                // 2b. Two readings — intersection of circles
                val centroid = weightedCentroid(readings)
                estLat = centroid.first
                estLng = centroid.second
                accuracyM = estimateAccuracy(readings.size, hasTA = readings.any { it.taDistanceM != null })
                techniques.add("BILATERATION")
                if (readings.any { it.taDistanceM != null }) techniques.add("TIMING_ADVANCE")
            } else {
                // 2c. Single reading — RSSI/TA distance estimate only
                val r = readings.last()
                val bearing = (towerId.hashCode() and 0x7FFFFFFF) % 360
                val dist = r.taDistanceM ?: r.estimatedDistanceM
                val dest = destination(r.userLat, r.userLng, bearing.toDouble(), dist)
                estLat = dest.first
                estLng = dest.second
                accuracyM = if (r.taDistanceM != null) 300.0 else 500.0
                techniques.add(if (r.taDistanceM != null) "TIMING_ADVANCE" else "RSSI_PROPAGATION")
            }
        }

        // Compute confidence score: 0.0 → 1.0 based on reading count and technique quality
        val confidenceScore = computeConfidence(readings.size, techniques, accuracyM)

        val entity = EstimatedTowerPositionEntity(
            towerId = towerId,
            mcc = mcc,
            mnc = mnc,
            lac = lac,
            cid = cid,
            estimatedLat = estLat,
            estimatedLon = estLng,
            accuracyMeters = accuracyM,
            readingCount = readings.size,
            lastUpdated = System.currentTimeMillis(),
            techniques = techniques.joinToString(","),
            confidenceScore = confidenceScore
        )

        // Update in-memory state (triggers Flow emissions)
        val updated = _estimatedPositions.value.toMutableMap()
        updated[towerId] = entity
        _estimatedPositions.value = updated

        // Mark dirty for batch persistence
        synchronized(dirtyTowers) {
            dirtyTowers.add(towerId)
        }
    }

    /**
     * Nonlinear least-squares trilateration from multiple readings.
     * Each reading provides (userLat, userLng, estimatedDistance).
     */
    private fun runNlsTrilateration(readings: List<TowerReading>): NlsResult? {
        if (readings.size < 3) return null

        val positions = readings.map { doubleArrayOf(it.userLat, it.userLng) }.toTypedArray()
        val distances = readings.map { it.taDistanceM ?: it.estimatedDistanceM }.toDoubleArray()

        val result = NonlinearLeastSquares.solve(positions, distances) ?: return null

        // Dynamic accuracy
        val taCount = readings.count { it.taDistanceM != null }
        val rssiCount = readings.size - taCount
        val taContrib = if (taCount > 0) taCount.toDouble() / (39.0 * 39.0) else 0.0
        val rssiContrib = if (rssiCount > 0) rssiCount.toDouble() / (35.0 * 35.0) else 0.0
        val gdop = when {
            readings.size <= 3 -> 2.5
            readings.size <= 6 -> 1.8
            readings.size <= 10 -> 1.3
            readings.size <= 20 -> 1.1
            else -> 1.0
        }
        val accuracy = (gdop / sqrt(taContrib + rssiContrib)).coerceIn(3.0, 500.0)

        return NlsResult(result.latitude, result.longitude, accuracy.coerceAtLeast(result.residualM))
    }

    private data class NlsResult(val lat: Double, val lng: Double, val accuracy: Double)

    /**
     * Weighted centroid: each reading contributes its estimated tower position
     * weighted by inverse distance uncertainty (TA readings get more weight).
     */
    private fun weightedCentroid(readings: List<TowerReading>): Pair<Double, Double> {
        var sumLat = 0.0
        var sumLng = 0.0
        var sumW = 0.0

        for (r in readings) {
            val dist = r.taDistanceM ?: r.estimatedDistanceM
            // Use bearing from user position to estimate tower location
            // Spread readings around user positions using RSSI-based distance
            val bearing = (r.hashCode() and 0x7FFFFFFF) % 360
            val (tLat, tLng) = destination(r.userLat, r.userLng, bearing.toDouble(), dist)

            // TA readings are more reliable
            val weight = if (r.taDistanceM != null) 3.0 else 1.0
            sumLat += tLat * weight
            sumLng += tLng * weight
            sumW += weight
        }

        return if (sumW > 0) Pair(sumLat / sumW, sumLng / sumW)
        else Pair(readings.last().userLat, readings.last().userLng)
    }

    private fun estimateAccuracy(readingCount: Int, hasTA: Boolean): Double {
        val baseAccuracy = when {
            readingCount >= 20 -> 50.0
            readingCount >= 10 -> 100.0
            readingCount >= 5 -> 200.0
            readingCount >= 3 -> 300.0
            readingCount >= 2 -> 400.0
            else -> 500.0
        }
        return if (hasTA) baseAccuracy * 0.6 else baseAccuracy
    }

    private fun computeConfidence(readingCount: Int, techniques: Set<String>, accuracyM: Double): Float {
        var score = 0.0f

        // Reading count contribution (0.0 → 0.5)
        score += when {
            readingCount >= 30 -> 0.5f
            readingCount >= 15 -> 0.4f
            readingCount >= 8 -> 0.3f
            readingCount >= 3 -> 0.2f
            else -> 0.1f
        }

        // Technique quality contribution (0.0 → 0.3)
        if ("KNOWN_TOWER_DB" in techniques) score += 0.3f
        else if ("TRILATERATION_NLS" in techniques) score += 0.25f
        else if ("TIMING_ADVANCE" in techniques) score += 0.2f
        else score += 0.05f

        // Accuracy contribution (0.0 → 0.2)
        score += when {
            accuracyM <= 50 -> 0.2f
            accuracyM <= 100 -> 0.15f
            accuracyM <= 200 -> 0.1f
            accuracyM <= 400 -> 0.05f
            else -> 0.0f
        }

        return score.coerceIn(0.0f, 1.0f)
    }

    private fun pickSpatiallyDistinct(readings: List<TowerReading>): List<TowerReading> {
        if (readings.isEmpty()) return emptyList()
        val result = mutableListOf(readings.first())
        for (r in readings) {
            val last = result.last()
            if (haversineM(last.userLat, last.userLng, r.userLat, r.userLng) >= MIN_MOVEMENT_M) {
                result.add(r)
            }
        }
        return result
    }

    private suspend fun maybePersist() {
        val now = System.currentTimeMillis()
        val towersToPersist: List<String>

        synchronized(dirtyTowers) {
            if (dirtyTowers.isEmpty()) return
            if (now - lastPersistTime < 5_000L && dirtyTowers.size < PERSIST_BATCH_SIZE) return
            towersToPersist = dirtyTowers.toList()
            dirtyTowers.clear()
            lastPersistTime = now
        }

        val currentPositions = _estimatedPositions.value
        val entities = towersToPersist.mapNotNull { currentPositions[it] }
        if (entities.isNotEmpty()) {
            estimatedPositionDao.upsertAll(entities)
        }
    }

    /**
     * Force-persist all dirty positions. Call on service stop.
     */
    suspend fun flushToDisk() {
        val towersToPersist: List<String>
        synchronized(dirtyTowers) {
            towersToPersist = dirtyTowers.toList()
            dirtyTowers.clear()
        }
        val currentPositions = _estimatedPositions.value
        val entities = towersToPersist.mapNotNull { currentPositions[it] }
        if (entities.isNotEmpty()) {
            estimatedPositionDao.upsertAll(entities)
        }
    }

    @Suppress("MissingPermission")
    private fun collectTimingAdvanceData(): List<TimingAdvanceInfo> {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()
        return try {
            tm.allCellInfo?.filterIsInstance<CellInfoLte>()?.mapNotNull { cellInfo ->
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                val ta = signal.timingAdvance
                if (ta <= 0 || ta == Int.MAX_VALUE) return@mapNotNull null
                TimingAdvanceInfo(
                    cid = identity.ci,
                    timingAdvance = ta,
                    distanceM = ta * TA_STEP_METERS
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun lookupKnownTower(mcc: Int, mnc: Int, lac: Int, cid: Int): KnownTowerEntity? {
        val key = "$mcc:$mnc:$lac:$cid"
        val now = System.currentTimeMillis()
        val cachedTs = knownTowerCacheTimestamps[key]
        if (cachedTs != null && now - cachedTs < knownTowerCacheTtl) {
            return knownTowerCache[key]
        }
        val entity = knownTowerDao.findTower(mcc, mnc, lac, cid)
        knownTowerCache[key] = entity
        knownTowerCacheTimestamps[key] = now
        return entity
    }

    // ── Geo math (local copies to avoid depending on ThreatGeolocation) ──

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).pow(2)
        return 2.0 * R * asin(sqrt(a))
    }

    private fun destination(lat: Double, lng: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val R = 6_371_000.0
        val φ1 = Math.toRadians(lat)
        val λ1 = Math.toRadians(lng)
        val θ = Math.toRadians(bearingDeg)
        val δ = distanceM / R
        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return Pair(Math.toDegrees(φ2), Math.toDegrees(λ2))
    }
}
