/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.baseline

import com.bp22intel.edgesentinel.data.local.dao.BaselineDao
import com.bp22intel.edgesentinel.data.local.entity.BaselineEntity
import com.bp22intel.edgesentinel.domain.model.CellTower
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Core baselining engine.
 *
 * Clusters locations using a DBSCAN-like algorithm (groups observations within ~100m),
 * automatically learns baselines from passive observation over time, and compares the
 * current RF environment against the stored baseline for the current location.
 *
 * Baselines are stored ONLY locally. No cloud, no external baseline sharing.
 */
@Singleton
class BaselineManager @Inject constructor(
    private val baselineDao: BaselineDao,
    private val scorer: BaselineScorer,
    private val learner: BaselineLearner
) {

    companion object {
        private const val CLUSTER_RADIUS_METERS = 100.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }

    /**
     * All baselines as a reactive Flow for UI observation.
     */
    fun getAllBaselines(): Flow<List<LocationBaseline>> =
        baselineDao.getAll().map { entities -> entities.map { it.toDomain() } }

    /**
     * Process an observation: find the matching cluster, learn from it, and return anomaly score.
     *
     * @return [BaselineAnomalyResult] with score 0.0 (normal) to 1.0 (abnormal),
     *         or a result with [isNewLocation] = true if we're still learning.
     */
    suspend fun processObservation(
        latitude: Double,
        longitude: Double,
        currentTowers: List<CellTower>,
        currentWifiAps: List<ObservedWifiAp>? = null,
        currentBleCount: Int? = null
    ): BaselineAnomalyResult {
        val allBaselines = baselineDao.getAllSync().map { it.toDomain() }
        val nearest = findNearestCluster(latitude, longitude, allBaselines)

        return if (nearest != null) {
            // Known location — score then learn
            val anomalyResult = scorer.score(nearest, currentTowers, currentWifiAps)

            // Update baseline with new observation
            val updated = learner.learn(nearest, currentTowers, currentWifiAps, currentBleCount)
            baselineDao.update(updated.toEntity())

            anomalyResult
        } else {
            // New location — create baseline and start learning
            val newBaseline = learner.createInitial(
                latitude = latitude,
                longitude = longitude,
                radiusMeters = CLUSTER_RADIUS_METERS,
                currentTowers = currentTowers,
                currentWifiAps = currentWifiAps,
                currentBleCount = currentBleCount
            )
            val id = baselineDao.insert(newBaseline.toEntity())

            BaselineAnomalyResult(
                locationId = id,
                compositeScore = 0.0,
                missingTowerScore = 0.0,
                unknownTowerScore = 0.0,
                signalDeviationScore = 0.0,
                networkTypeScore = 0.0,
                wifiChangeScore = 0.0,
                isNewLocation = true,
                confidence = BaselineConfidence.LEARNING,
                details = mapOf("status" to "New location, learning baseline")
            )
        }
    }

    /**
     * Reset a specific location's baseline (e.g., user moved offices).
     */
    suspend fun resetBaseline(baselineId: Long) {
        baselineDao.deleteById(baselineId)
    }

    /**
     * Reset all baselines.
     */
    suspend fun resetAll() {
        baselineDao.deleteAll()
    }

    /**
     * Get baseline for a specific ID.
     */
    suspend fun getBaseline(id: Long): LocationBaseline? =
        baselineDao.getById(id)?.toDomain()

    /**
     * DBSCAN-like clustering: find the nearest existing baseline within [CLUSTER_RADIUS_METERS].
     */
    private fun findNearestCluster(
        lat: Double,
        lon: Double,
        baselines: List<LocationBaseline>
    ): LocationBaseline? {
        var nearest: LocationBaseline? = null
        var nearestDist = Double.MAX_VALUE

        for (baseline in baselines) {
            val dist = haversineDistance(lat, lon, baseline.latitude, baseline.longitude)
            if (dist <= maxOf(baseline.radiusMeters, CLUSTER_RADIUS_METERS) && dist < nearestDist) {
                nearest = baseline
                nearestDist = dist
            }
        }

        return nearest
    }

    /**
     * Haversine formula for great-circle distance between two GPS coordinates.
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * acos(minOf(1.0, kotlin.math.sqrt(a)).let {
            // Use atan2 for numerical stability
            kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
        })
        return EARTH_RADIUS_METERS * c
    }

    // ---- Entity ↔ Domain mapping ----

    private fun BaselineEntity.toDomain(): LocationBaseline = LocationBaseline(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radius,
        label = label,
        expectedTowers = parseTowers(cellTowersJson),
        expectedWifiAps = parseWifiAps(wifiApsJson),
        bleCountRange = bleCountMin..bleCountMax,
        networkTypeDistribution = parseNetworkDist(networkTypeDistJson),
        dayProfile = dayProfileJson?.let { parseTimeProfile(it) },
        nightProfile = nightProfileJson?.let { parseTimeProfile(it) },
        observationCount = observationCount,
        confidence = BaselineConfidence.fromObservationCount(observationCount),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun LocationBaseline.toEntity(): BaselineEntity = BaselineEntity(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radius = radiusMeters,
        label = label,
        cellTowersJson = towersToJson(expectedTowers),
        wifiApsJson = wifiApsToJson(expectedWifiAps),
        bleCountMin = bleCountRange.first,
        bleCountMax = bleCountRange.last,
        networkTypeDistJson = networkDistToJson(networkTypeDistribution),
        observationCount = observationCount,
        confidence = confidence.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        dayProfileJson = dayProfile?.let { timeProfileToJson(it) },
        nightProfileJson = nightProfile?.let { timeProfileToJson(it) }
    )

    // ---- JSON serialization ----

    private fun towersToJson(towers: List<ExpectedTower>): String {
        val arr = JSONArray()
        for (t in towers) {
            arr.put(JSONObject().apply {
                put("cid", t.cid)
                put("lacTac", t.lacTac)
                put("mcc", t.mcc)
                put("mnc", t.mnc)
                put("networkType", t.networkType)
                put("signalMin", t.signalMin)
                put("signalMax", t.signalMax)
                put("signalAvg", t.signalAvg)
                put("appearanceRate", t.appearanceRate)
            })
        }
        return arr.toString()
    }

    private fun parseTowers(json: String): List<ExpectedTower> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ExpectedTower(
                cid = obj.getInt("cid"),
                lacTac = obj.getInt("lacTac"),
                mcc = obj.getInt("mcc"),
                mnc = obj.getInt("mnc"),
                networkType = obj.getString("networkType"),
                signalMin = obj.getInt("signalMin"),
                signalMax = obj.getInt("signalMax"),
                signalAvg = obj.getDouble("signalAvg"),
                appearanceRate = obj.getDouble("appearanceRate")
            )
        }
    }

    private fun wifiApsToJson(aps: List<ExpectedWifiAp>): String {
        val arr = JSONArray()
        for (ap in aps) {
            arr.put(JSONObject().apply {
                put("bssid", ap.bssid)
                put("ssid", ap.ssid)
                put("signalMin", ap.signalMin)
                put("signalMax", ap.signalMax)
                put("signalAvg", ap.signalAvg)
                put("appearanceRate", ap.appearanceRate)
            })
        }
        return arr.toString()
    }

    private fun parseWifiAps(json: String): List<ExpectedWifiAp> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ExpectedWifiAp(
                bssid = obj.getString("bssid"),
                ssid = obj.getString("ssid"),
                signalMin = obj.getInt("signalMin"),
                signalMax = obj.getInt("signalMax"),
                signalAvg = obj.getDouble("signalAvg"),
                appearanceRate = obj.getDouble("appearanceRate")
            )
        }
    }

    private fun networkDistToJson(dist: Map<String, Double>): String {
        val obj = JSONObject()
        for ((k, v) in dist) obj.put(k, v)
        return obj.toString()
    }

    private fun parseNetworkDist(json: String): Map<String, Double> {
        if (json.isBlank()) return emptyMap()
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.getDouble(it) }
    }

    private fun timeProfileToJson(profile: TimeProfile): String {
        return JSONObject().apply {
            put("towers", JSONArray(towersToJson(profile.towers)))
            put("networkDist", JSONObject(networkDistToJson(profile.networkTypeDistribution)))
            put("avgBleCount", profile.avgBleCount)
        }.toString()
    }

    private fun parseTimeProfile(json: String): TimeProfile {
        val obj = JSONObject(json)
        val towersArr = obj.optJSONArray("towers")
        val towers = if (towersArr != null) {
            (0 until towersArr.length()).map { i ->
                val t = towersArr.getJSONObject(i)
                ExpectedTower(
                    cid = t.getInt("cid"),
                    lacTac = t.getInt("lacTac"),
                    mcc = t.getInt("mcc"),
                    mnc = t.getInt("mnc"),
                    networkType = t.getString("networkType"),
                    signalMin = t.getInt("signalMin"),
                    signalMax = t.getInt("signalMax"),
                    signalAvg = t.getDouble("signalAvg"),
                    appearanceRate = t.getDouble("appearanceRate")
                )
            }
        } else emptyList()

        val distObj = obj.optJSONObject("networkDist")
        val dist = if (distObj != null) {
            distObj.keys().asSequence().associateWith { distObj.getDouble(it) }
        } else emptyMap()

        return TimeProfile(
            towers = towers,
            networkTypeDistribution = dist,
            avgBleCount = obj.optDouble("avgBleCount", 0.0)
        )
    }
}
