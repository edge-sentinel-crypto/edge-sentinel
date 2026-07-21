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

import com.bp22intel.edgesentinel.domain.model.CellTower
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calculates anomaly scores by comparing current RF environment against a stored baseline.
 *
 * Returns per-category scores (0.0–1.0) and a composite score:
 * - Missing expected towers: weighted by how consistently they appeared before
 * - New unknown towers: weighted by signal strength (close unknown = more suspicious)
 * - Signal strength deviation: weighted by magnitude
 * - Network type change: 5G→2G at a known 5G location = high anomaly
 * - WiFi AP changes: new high-power AP at familiar location
 */
@Singleton
class BaselineScorer @Inject constructor() {

    companion object {
        private const val WEIGHT_MISSING_TOWERS = 0.30
        private const val WEIGHT_UNKNOWN_TOWERS = 0.25
        private const val WEIGHT_SIGNAL_DEVIATION = 0.15
        private const val WEIGHT_NETWORK_TYPE = 0.20
        private const val WEIGHT_WIFI_CHANGE = 0.10

        private const val STRONG_SIGNAL_THRESHOLD = -60
        private const val SIGNAL_DEVIATION_SEVERE = 20.0
    }

    fun score(
        baseline: LocationBaseline,
        currentTowers: List<CellTower>,
        currentWifiAps: List<ObservedWifiAp>? = null
    ): BaselineAnomalyResult {
        val profile = selectProfile(baseline)
        val expectedTowers = profile?.towers ?: baseline.expectedTowers

        val missingScore = scoreMissingTowers(expectedTowers, currentTowers)
        val unknownScore = scoreUnknownTowers(expectedTowers, currentTowers)
        val signalScore = scoreSignalDeviation(expectedTowers, currentTowers)
        val networkScore = scoreNetworkTypeChange(
            profile?.networkTypeDistribution ?: baseline.networkTypeDistribution,
            currentTowers
        )
        val wifiScore = scoreWifiChanges(baseline.expectedWifiAps, currentWifiAps)

        val composite = (missingScore * WEIGHT_MISSING_TOWERS +
                unknownScore * WEIGHT_UNKNOWN_TOWERS +
                signalScore * WEIGHT_SIGNAL_DEVIATION +
                networkScore * WEIGHT_NETWORK_TYPE +
                wifiScore * WEIGHT_WIFI_CHANGE).coerceIn(0.0, 1.0)

        val details = mutableMapOf<String, String>()
        if (missingScore > 0.3) {
            val missing = findMissingTowers(expectedTowers, currentTowers)
            details["missing_towers"] = missing.joinToString { "CID:${it.cid}" }
        }
        if (unknownScore > 0.3) {
            val unknown = findUnknownTowers(expectedTowers, currentTowers)
            details["unknown_towers"] = unknown.joinToString { "CID:${it.cid} (${it.signalStrength}dBm)" }
        }
        if (networkScore > 0.3) {
            details["network_anomaly"] = "Unexpected network type distribution"
        }

        return BaselineAnomalyResult(
            locationId = baseline.id,
            compositeScore = composite,
            missingTowerScore = missingScore,
            unknownTowerScore = unknownScore,
            signalDeviationScore = signalScore,
            networkTypeScore = networkScore,
            wifiChangeScore = wifiScore,
            isNewLocation = false,
            confidence = baseline.confidence,
            details = details
        )
    }

    private fun selectProfile(baseline: LocationBaseline): TimeProfile? {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour in 7..21) baseline.dayProfile else baseline.nightProfile
    }

    /**
     * Missing expected towers: weighted by their historical appearance rate.
     * A tower that appeared 95% of the time being gone is more suspicious
     * than one that appeared 30% of the time.
     */
    private fun scoreMissingTowers(
        expected: List<ExpectedTower>,
        current: List<CellTower>
    ): Double {
        if (expected.isEmpty()) return 0.0
        val currentCids = current.map { it.cid }.toSet()
        var weightedMissing = 0.0
        var totalWeight = 0.0

        for (tower in expected) {
            totalWeight += tower.appearanceRate
            if (tower.cid !in currentCids) {
                weightedMissing += tower.appearanceRate
            }
        }

        return if (totalWeight > 0) (weightedMissing / totalWeight).coerceIn(0.0, 1.0) else 0.0
    }

    /**
     * New unknown towers: weighted by signal strength.
     * A strong unknown tower nearby is more suspicious than a weak distant one.
     */
    private fun scoreUnknownTowers(
        expected: List<ExpectedTower>,
        current: List<CellTower>
    ): Double {
        if (current.isEmpty()) return 0.0
        val knownCids = expected.map { it.cid }.toSet()
        val unknown = current.filter { it.cid !in knownCids }

        if (unknown.isEmpty()) return 0.0

        var score = 0.0
        for (tower in unknown) {
            val signalWeight = if (tower.signalStrength > STRONG_SIGNAL_THRESHOLD) 1.0
            else 0.3 + 0.7 * ((tower.signalStrength + 110).toDouble() / 50.0).coerceIn(0.0, 1.0)
            score += signalWeight
        }

        return (score / current.size.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * Signal strength deviation from expected ranges.
     */
    private fun scoreSignalDeviation(
        expected: List<ExpectedTower>,
        current: List<CellTower>
    ): Double {
        val expectedMap = expected.associateBy { it.cid }
        var totalDeviation = 0.0
        var matchedCount = 0

        for (tower in current) {
            val exp = expectedMap[tower.cid] ?: continue
            matchedCount++
            val deviation = when {
                tower.signalStrength > exp.signalMax ->
                    (tower.signalStrength - exp.signalMax).toDouble()
                tower.signalStrength < exp.signalMin ->
                    (exp.signalMin - tower.signalStrength).toDouble()
                else -> 0.0
            }
            totalDeviation += (deviation / SIGNAL_DEVIATION_SEVERE).coerceIn(0.0, 1.0)
        }

        return if (matchedCount > 0) (totalDeviation / matchedCount).coerceIn(0.0, 1.0) else 0.0
    }

    /**
     * Network type distribution anomaly.
     * 5G→2G at a known 5G-dominant location is highly suspicious.
     */
    private fun scoreNetworkTypeChange(
        expectedDist: Map<String, Double>,
        current: List<CellTower>
    ): Double {
        if (expectedDist.isEmpty() || current.isEmpty()) return 0.0

        val currentDist = current.groupBy { it.networkType.name }
            .mapValues { it.value.size.toDouble() / current.size }

        // Check for network generation downgrade
        val expectedPrimaryGen = expectedDist.maxByOrNull { it.value }?.key ?: return 0.0
        val currentPrimaryGen = currentDist.maxByOrNull { it.value }?.key ?: return 0.0

        val genOrder = mapOf("NR" to 5, "LTE" to 4, "WCDMA" to 3, "CDMA" to 2, "GSM" to 1)
        val expectedRank = genOrder[expectedPrimaryGen] ?: 0
        val currentRank = genOrder[currentPrimaryGen] ?: 0

        // Downgrade is scored proportional to how many generations we dropped
        val downgradeScore = if (currentRank < expectedRank) {
            ((expectedRank - currentRank).toDouble() / 4.0).coerceIn(0.0, 1.0)
        } else 0.0

        // Also measure distribution divergence (Jensen-Shannon style, simplified)
        val allTypes = expectedDist.keys + currentDist.keys
        var divergence = 0.0
        for (t in allTypes) {
            val p = expectedDist[t] ?: 0.0
            val q = currentDist[t] ?: 0.0
            divergence += kotlin.math.abs(p - q)
        }
        val distScore = (divergence / 2.0).coerceIn(0.0, 1.0)

        return maxOf(downgradeScore, distScore)
    }

    /**
     * WiFi AP changes: new high-power APs at a familiar location.
     */
    private fun scoreWifiChanges(
        expected: List<ExpectedWifiAp>,
        current: List<ObservedWifiAp>?
    ): Double {
        if (current == null || expected.isEmpty()) return 0.0

        val knownBssids = expected.map { it.bssid }.toSet()
        val newAps = current.filter { it.bssid !in knownBssids }

        if (newAps.isEmpty()) return 0.0

        var score = 0.0
        for (ap in newAps) {
            val signalWeight = if (ap.signalStrength > STRONG_SIGNAL_THRESHOLD) 1.0 else 0.3
            score += signalWeight
        }

        return (score / maxOf(current.size, expected.size).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun findMissingTowers(expected: List<ExpectedTower>, current: List<CellTower>): List<ExpectedTower> {
        val currentCids = current.map { it.cid }.toSet()
        return expected.filter { it.cid !in currentCids && it.appearanceRate > 0.5 }
    }

    private fun findUnknownTowers(expected: List<ExpectedTower>, current: List<CellTower>): List<CellTower> {
        val knownCids = expected.map { it.cid }.toSet()
        return current.filter { it.cid !in knownCids }
    }
}

/**
 * Observed WiFi AP during a scan (not persisted — used for live comparison).
 */
data class ObservedWifiAp(
    val bssid: String,
    val ssid: String,
    val signalStrength: Int
)
