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

import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.ThreatType
import javax.inject.Inject

/**
 * Detects tracking patterns based on LAC/TAC change analysis.
 *
 * IMSI catchers can cause abnormal Location Area Code changes as they force
 * devices to register. This detector looks for:
 * - Rapid cell reselection (abnormal for a stationary device)
 * - LAC/TAC values not previously seen at the device's location
 * - Unusual LAC change patterns that suggest forced re-registration
 */
class TrackingPatternDetector @Inject constructor() : ThreatDetector {

    override val type: ThreatType = ThreatType.TRACKING_PATTERN

    companion object {
        /** Number of distinct LACs in short window that is suspicious. */
        private const val RAPID_LAC_CHANGE_THRESHOLD = 3

        /** Time window for rapid change detection (5 minutes). */
        private const val RAPID_CHANGE_WINDOW_MS = 5 * 60 * 1000L

        /** Time window for LAC history comparison (1 hour). */
        private const val HISTORY_WINDOW_MS = 60 * 60 * 1000L
    }

    override suspend fun analyze(
        cells: List<CellTower>,
        history: List<CellTower>
    ): DetectionResult? {
        if (cells.isEmpty() || history.isEmpty()) return null

        val indicators = mutableMapOf<String, String>()
        var score = 0.0

        // Build set of historically known LAC/TAC values
        val knownLacs = history.map { it.lacTac }.toSet()

        // Check 1: LAC/TAC values not previously seen
        val currentLacs = cells.map { it.lacTac }.toSet()
        val unknownLacs = currentLacs - knownLacs

        if (unknownLacs.isNotEmpty() && knownLacs.isNotEmpty()) {
            val contribution = (unknownLacs.size * 0.75).coerceAtMost(1.5)
            score += contribution
            indicators["unknown_lac"] =
                "LAC/TAC values not seen before: ${unknownLacs.joinToString(", ")} " +
                    "(${knownLacs.size} known LACs in history)"
        }

        // Check 2: Rapid cell reselection (many distinct LACs in short window)
        val now = System.currentTimeMillis()
        val recentCells = history.filter { now - it.lastSeen < RAPID_CHANGE_WINDOW_MS }
        val recentLacs = (recentCells.map { it.lacTac } + currentLacs).toSet()

        if (recentLacs.size >= RAPID_LAC_CHANGE_THRESHOLD) {
            val contribution = ((recentLacs.size - RAPID_LAC_CHANGE_THRESHOLD + 1) * 1.0)
                .coerceAtMost(2.0)
            score += contribution
            indicators["rapid_reselection"] =
                "${recentLacs.size} distinct LAC/TAC values in last " +
                    "${RAPID_CHANGE_WINDOW_MS / 60000} minutes (threshold: $RAPID_LAC_CHANGE_THRESHOLD)"
        }

        // Check 3: Short cell duration — cells seen very briefly then disappearing
        val shortLivedCells = cells.filter { cell ->
            val duration = cell.lastSeen - cell.firstSeen
            duration in 1..30_000 && cell.timesSeen <= 2
        }

        if (shortLivedCells.isNotEmpty()) {
            val contribution = (shortLivedCells.size * 0.5).coerceAtMost(1.0)
            score += contribution
            indicators["short_duration"] =
                "${shortLivedCells.size} cell(s) with very short observation duration (<30s)"
        }

        // Check 4: LAC change pattern — oscillating between LACs
        val recentLacSequence = recentCells
            .sortedBy { it.lastSeen }
            .map { it.lacTac }

        if (recentLacSequence.size >= 4) {
            val oscillations = countOscillations(recentLacSequence)
            if (oscillations >= 2) {
                val contribution = (oscillations * 0.5).coerceAtMost(1.0)
                score += contribution
                indicators["lac_oscillation"] =
                    "LAC oscillation detected: $oscillations back-and-forth changes " +
                        "in recent history (suggests forced re-registration)"
            }
        }

        if (score <= 0.0) return null

        val confidence = when {
            score >= 3.0 -> Confidence.HIGH
            score >= 1.5 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return DetectionResult(
            threatType = type,
            score = score,
            confidence = confidence,
            summary = buildSummary(indicators),
            details = indicators
        )
    }

    /**
     * Count the number of times the LAC sequence oscillates (A->B->A pattern).
     */
    private fun countOscillations(sequence: List<Int>): Int {
        if (sequence.size < 3) return 0
        var count = 0
        for (i in 2 until sequence.size) {
            if (sequence[i] == sequence[i - 2] && sequence[i] != sequence[i - 1]) {
                count++
            }
        }
        return count
    }

    private fun buildSummary(indicators: Map<String, String>): String {
        return when {
            indicators.containsKey("rapid_reselection") && indicators.containsKey("unknown_lac") ->
                "Rapid cell reselection with unknown LAC values — possible tracking or forced re-registration"
            indicators.containsKey("rapid_reselection") ->
                "Rapid cell reselection detected — abnormal for stationary device"
            indicators.containsKey("unknown_lac") ->
                "Unknown LAC/TAC values detected at current location"
            indicators.containsKey("lac_oscillation") ->
                "LAC oscillation pattern detected — possible forced re-registration attack"
            else ->
                "Tracking pattern indicators detected (${indicators.size} anomalies)"
        }
    }
}
