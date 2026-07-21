/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.detectors

import android.os.Build
import androidx.annotation.RequiresApi
import com.bp22intel.edgesentinel.data.sensor.NrConnectionState
import com.bp22intel.edgesentinel.data.sensor.NrMonitor
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.ThreatType
import javax.inject.Inject

/**
 * 5G NR threat detector using Android 10+ CellInfoNr / CellIdentityNr /
 * CellSignalStrengthNr APIs and TelephonyDisplayInfo for override network
 * type detection.
 *
 * Detection capabilities:
 * - Fake gNodeB detection (signal anomalies, unknown NCI, isolated cells)
 * - NR downgrade attacks (NR → LTE → 3G forced transitions)
 * - NR bidding-down attacks (forced capability reduction)
 * - Suspicious NR cell reselection patterns
 * - NR signal anomalies (rapid changes, unusable levels, uniform spoofing)
 * - NSA ↔ SA mode transition anomalies
 * - NR-ARFCN frequency change monitoring
 * - NR null cipher indicators (where API allows)
 *
 * Original Edge Sentinel implementation for 5G NR anomaly detection.
 */
class NrDetector @Inject constructor(
    private val nrMonitor: NrMonitor
) : ThreatDetector {

    override val type: ThreatType = ThreatType.NR_ANOMALY

    override suspend fun analyze(
        cells: List<CellTower>,
        history: List<CellTower>
    ): DetectionResult? {
        // NR APIs require Android Q (API 29) minimum
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val nrCells = cells.filter { it.networkType == NetworkType.NR }
        val nrHistory = history.filter { it.networkType == NetworkType.NR }

        // If no NR cells visible and no NR history, nothing to analyze
        if (nrCells.isEmpty() && nrHistory.isEmpty()) return null

        val allIndicators = mutableMapOf<String, String>()
        var totalScore = 0.0

        // --- 1. Fake gNodeB detection ---
        val fakeGnodebIndicators = NrAnomalyPatterns.detectFakeGnodeb(nrCells, cells, history)
        allIndicators.putAll(fakeGnodebIndicators)
        totalScore += scoreIndicators(fakeGnodebIndicators)

        // --- 2. NR downgrade attack detection ---
        val recentHistory = filterRecentHistory(
            history,
            NrAnomalyPatterns.DOWNGRADE_DETECTION_WINDOW_MS
        )
        val downgradeIndicators = NrAnomalyPatterns.detectNrDowngrade(cells, recentHistory)
        allIndicators.putAll(downgradeIndicators)
        totalScore += scoreIndicators(downgradeIndicators) * 1.5 // Downgrade attacks weighted higher

        // --- 3. Bidding-down attack detection ---
        val biddingDownIndicators = NrAnomalyPatterns.detectBiddingDown(nrCells, history)
        allIndicators.putAll(biddingDownIndicators)
        totalScore += scoreIndicators(biddingDownIndicators)

        // --- 4. Suspicious NR cell reselection ---
        val reselectionIndicators = NrAnomalyPatterns.detectSuspiciousReselection(nrCells, nrHistory)
        allIndicators.putAll(reselectionIndicators)
        totalScore += scoreIndicators(reselectionIndicators)

        // --- 5. NR signal anomalies ---
        val signalIndicators = NrAnomalyPatterns.detectSignalAnomalies(nrCells, nrHistory)
        allIndicators.putAll(signalIndicators)
        totalScore += scoreIndicators(signalIndicators)

        // --- 6. NSA/SA mode transition anomalies ---
        val modeIndicators = detectModeTransitionAnomalies(nrCells)
        allIndicators.putAll(modeIndicators)
        totalScore += scoreIndicators(modeIndicators) * 1.2

        // --- 7. NR null cipher indicators ---
        val cipherIndicators = NrAnomalyPatterns.detectNullCipherIndicators(nrCells)
        allIndicators.putAll(cipherIndicators)
        totalScore += scoreIndicators(cipherIndicators) * 2.0

        if (totalScore <= 0.0) return null

        val confidence = when {
            totalScore >= 4.0 -> Confidence.HIGH
            totalScore >= 2.0 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return DetectionResult(
            threatType = type,
            score = totalScore,
            confidence = confidence,
            summary = buildSummary(allIndicators),
            details = allIndicators
        )
    }

    /**
     * Detect anomalies in NSA ↔ SA mode transitions.
     *
     * Unexpected transitions can indicate:
     * - Forced fallback from SA to NSA (reducing 5G security)
     * - Unexpected loss of NR entirely from EN-DC mode
     * - Rapid mode oscillation
     */
    private fun detectModeTransitionAnomalies(
        nrCells: List<CellTower>
    ): Map<String, String> {
        val indicators = mutableMapOf<String, String>()
        val currentState = nrMonitor.connectionState.value

        // If we see NR cells but NrMonitor says NOT_ON_NR, there's a mismatch
        if (nrCells.isNotEmpty() && currentState == NrConnectionState.NOT_ON_NR) {
            indicators["nr_state_mismatch"] =
                "NR cells visible (${nrCells.size}) but connection state reports NOT_ON_NR — " +
                    "possible display info spoofing or measurement gap"
        }

        // If NrMonitor reported recent rapid state changes, flag it
        val recentDistinctCells = nrMonitor.getDistinctNrCellCount(
            NrAnomalyPatterns.RAPID_CHANGE_WINDOW_MS
        )
        if (recentDistinctCells > NrAnomalyPatterns.MAX_NR_CELL_CHANGES_IN_WINDOW) {
            indicators["nr_excessive_cell_churn"] =
                "$recentDistinctCells distinct NR cells observed in last " +
                    "${NrAnomalyPatterns.RAPID_CHANGE_WINDOW_MS / 1000}s"
        }

        return indicators
    }

    /**
     * Filter history to only include entries within the given time window.
     */
    private fun filterRecentHistory(history: List<CellTower>, windowMs: Long): List<CellTower> {
        val cutoff = System.currentTimeMillis() - windowMs
        return history.filter { it.lastSeen >= cutoff }
    }

    /**
     * Score a set of indicators. Each indicator contributes a base score.
     */
    private fun scoreIndicators(indicators: Map<String, String>): Double {
        if (indicators.isEmpty()) return 0.0

        var score = 0.0
        for ((key, _) in indicators) {
            score += when {
                // High-severity indicators
                key.startsWith("nr_progressive_downgrade") -> 2.5
                key.startsWith("nr_lte_oscillation") -> 2.0
                key.startsWith("nr_strong_signal") -> 1.5
                key.startsWith("nr_uniform_signals") -> 1.5

                // Medium-severity indicators
                key.startsWith("nr_to_") && key.endsWith("_fallback") -> 1.5
                key.startsWith("nr_unknown_nci") -> 1.0
                key.startsWith("nr_signal_jump") -> 1.0
                key.startsWith("nr_rapid_reselection") -> 1.0
                key.startsWith("nr_state_mismatch") -> 1.0
                key.startsWith("nr_excessive_cell_churn") -> 1.0

                // Lower-severity indicators
                key.startsWith("nr_missing_neighbors") -> 0.8
                key.startsWith("nr_weak_cell_selection") -> 0.7
                key.startsWith("nr_short_duration") -> 0.5
                key.startsWith("nr_unusable_signal") -> 0.5

                // Default
                else -> 0.5
            }
        }
        return score
    }

    private fun buildSummary(indicators: Map<String, String>): String {
        val categories = mutableListOf<String>()

        if (indicators.keys.any { it.startsWith("nr_strong_signal") || it.startsWith("nr_unknown_nci") }) {
            categories.add("fake gNodeB indicators")
        }
        if (indicators.keys.any { it.contains("downgrade") || it.contains("fallback") }) {
            categories.add("NR downgrade attack")
        }
        if (indicators.keys.any { it.contains("oscillation") || it.contains("rapid_reselection") }) {
            categories.add("NR bidding-down/jamming")
        }
        if (indicators.keys.any { it.startsWith("nr_signal_jump") || it.startsWith("nr_uniform_signals") }) {
            categories.add("NR signal anomalies")
        }
        if (indicators.keys.any { it.startsWith("nr_state_mismatch") || it.startsWith("nr_excessive_cell_churn") }) {
            categories.add("NR mode anomalies")
        }
        if (indicators.keys.any { it.startsWith("nr_weak_cell_selection") || it.startsWith("nr_short_duration") }) {
            categories.add("suspicious NR reselection")
        }

        return when {
            categories.size >= 3 ->
                "Multiple 5G NR threats detected: ${categories.joinToString(", ")}"
            categories.size == 1 ->
                "5G NR anomaly: ${categories.first()}"
            categories.isNotEmpty() ->
                "5G NR anomalies: ${categories.joinToString(" and ")}"
            else ->
                "5G NR anomaly indicators detected (${indicators.size} findings)"
        }
    }
}
