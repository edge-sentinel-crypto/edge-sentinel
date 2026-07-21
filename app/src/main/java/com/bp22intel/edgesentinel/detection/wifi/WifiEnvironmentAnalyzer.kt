/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.wifi

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fingerprint of a WiFi environment at a point in time.
 */
data class EnvironmentFingerprint(
    val knownBssids: Set<String>,
    val avgApCount: Double,
    val avgSignalStrength: Double,
    val dominantSecurityType: SecurityType,
    val frequencyDistribution: Map<Int, Int>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of comparing current environment against a baseline fingerprint.
 */
data class EnvironmentAnalysis(
    val noveltyScore: Double,
    val anomalyScore: Double,
    val healthScore: Int,
    val newAps: List<ObservedAp>,
    val missingBssids: Set<String>,
    val summary: String
)

/**
 * Builds WiFi environment fingerprints from scan history and compares
 * the current scan against the baseline to score novelty and anomaly.
 *
 * All processing is LOCAL — no data ever leaves the device.
 */
@Singleton
class WifiEnvironmentAnalyzer @Inject constructor() {

    companion object {
        private const val MIN_SNAPSHOTS_FOR_BASELINE = 5
        private const val HEALTH_SCORE_MAX = 100
    }

    /**
     * Build a baseline fingerprint from historical scan snapshots.
     */
    fun buildBaseline(history: List<WifiScanSnapshot>): EnvironmentFingerprint? {
        if (history.size < MIN_SNAPSHOTS_FOR_BASELINE) return null

        val allAps = history.flatMap { it.accessPoints }
        val knownBssids = allAps.map { it.bssid }.toSet()
        val avgApCount = history.map { it.accessPoints.size.toDouble() }.average()
        val avgSignal = if (allAps.isNotEmpty()) allAps.map { it.signalStrength.toDouble() }.average() else -100.0

        val securityCounts = allAps.groupingBy { it.securityType }.eachCount()
        val dominantSecurity = securityCounts.maxByOrNull { it.value }?.key ?: SecurityType.UNKNOWN

        // Frequency band distribution (2.4GHz vs 5GHz vs 6GHz)
        val freqDist = allAps.groupingBy { frequencyBand(it.frequency) }.eachCount()

        return EnvironmentFingerprint(
            knownBssids = knownBssids,
            avgApCount = avgApCount,
            avgSignalStrength = avgSignal,
            dominantSecurityType = dominantSecurity,
            frequencyDistribution = freqDist
        )
    }

    /**
     * Compare the current scan against a baseline fingerprint and produce
     * novelty/anomaly scores plus a health assessment.
     */
    fun analyze(
        current: WifiScanSnapshot,
        baseline: EnvironmentFingerprint
    ): EnvironmentAnalysis {
        val currentBssids = current.accessPoints.map { it.bssid }.toSet()

        // New APs not in baseline
        val newAps = current.accessPoints.filter { it.bssid !in baseline.knownBssids }
        val missingBssids = baseline.knownBssids - currentBssids

        // Novelty: ratio of new APs to total
        val noveltyScore = if (current.accessPoints.isNotEmpty()) {
            newAps.size.toDouble() / current.accessPoints.size
        } else 0.0

        // Anomaly scoring — composite of multiple factors
        var anomalyScore = 0.0
        val factors = mutableListOf<String>()

        // Factor 1: AP count deviation
        val apCountDelta = abs(current.accessPoints.size - baseline.avgApCount)
        val apCountStdDev = sqrt(baseline.avgApCount.coerceAtLeast(1.0))
        if (apCountDelta > apCountStdDev * 2) {
            anomalyScore += 0.3
            factors.add("AP count deviation: ${current.accessPoints.size} vs baseline avg %.1f".format(baseline.avgApCount))
        }

        // Factor 2: High novelty
        if (noveltyScore > 0.3) {
            anomalyScore += 0.3
            factors.add("${newAps.size} new APs (${(noveltyScore * 100).toInt()}% novel)")
        }

        // Factor 3: Many missing APs
        val missingRatio = if (baseline.knownBssids.isNotEmpty()) {
            missingBssids.size.toDouble() / baseline.knownBssids.size
        } else 0.0
        if (missingRatio > 0.5) {
            anomalyScore += 0.2
            factors.add("${missingBssids.size} previously-known APs missing")
        }

        // Factor 4: Strong new open networks
        val strongOpenNew = newAps.filter {
            it.securityType == SecurityType.OPEN && it.signalStrength > -50
        }
        if (strongOpenNew.isNotEmpty()) {
            anomalyScore += 0.2
            factors.add("${strongOpenNew.size} new strong open network(s)")
        }

        // Health score: 100 = perfectly matching baseline, lower = more anomalous
        val healthScore = (HEALTH_SCORE_MAX * (1.0 - anomalyScore.coerceIn(0.0, 1.0))).toInt()

        val summary = when {
            anomalyScore >= 0.7 -> "WiFi environment significantly differs from baseline — ${factors.size} anomalies"
            anomalyScore >= 0.4 -> "WiFi environment shows moderate changes — review new APs"
            noveltyScore > 0.2 -> "Some new access points detected in this area"
            else -> "WiFi environment matches historical baseline"
        }

        return EnvironmentAnalysis(
            noveltyScore = noveltyScore,
            anomalyScore = anomalyScore,
            healthScore = healthScore,
            newAps = newAps,
            missingBssids = missingBssids,
            summary = summary
        )
    }

    /**
     * Map a frequency in MHz to its band bucket for distribution tracking.
     */
    private fun frequencyBand(freqMhz: Int): Int = when {
        freqMhz < 3000 -> 2400
        freqMhz < 5900 -> 5000
        else -> 6000
    }
}
