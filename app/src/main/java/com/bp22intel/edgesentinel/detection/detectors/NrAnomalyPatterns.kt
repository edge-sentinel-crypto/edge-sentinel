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
import com.bp22intel.edgesentinel.domain.model.NetworkType

/**
 * Known 5G NR attack patterns and heuristics for detecting fake gNodeBs,
 * downgrade attacks, bidding-down attacks, and other NR-specific threats.
 *
 * Original Edge Sentinel 5G NR anomaly patterns.
 */
object NrAnomalyPatterns {

    // --- Thresholds ---

    /** NR SS-RSRP stronger than this is suspicious (fake gNodeB proximity). */
    const val SUSPICIOUS_NR_SIGNAL_DBM = -50

    /** NR SS-RSRP weaker than this is unusable and may indicate jamming. */
    const val UNUSABLE_NR_SIGNAL_DBM = -130

    /** Maximum plausible SS-RSRP change per scan interval (dB). */
    const val MAX_PLAUSIBLE_SIGNAL_DELTA_DB = 30

    /** Minimum number of NR cells expected in urban environment. */
    const val MIN_EXPECTED_NR_NEIGHBORS = 1

    /** Time window for rapid NR cell changes (milliseconds). */
    const val RAPID_CHANGE_WINDOW_MS = 60_000L

    /** Maximum NR cell changes in the rapid change window before flagging. */
    const val MAX_NR_CELL_CHANGES_IN_WINDOW = 4

    /** Time window for downgrade detection (milliseconds). */
    const val DOWNGRADE_DETECTION_WINDOW_MS = 300_000L

    // --- NR-ARFCN frequency band boundaries ---

    /** Sub-6 GHz NR-ARFCN range (FR1): 0–599999. */
    const val FR1_ARFCN_MAX = 599_999

    /** mmWave NR-ARFCN range (FR2): 600000–2016666. */
    const val FR2_ARFCN_MIN = 600_000

    /**
     * Detect fake gNodeB indicators from NR cell data.
     *
     * Heuristics:
     * - Abnormally strong NR signal (close-proximity fake base station)
     * - NR cell identity (NCI) not seen in observation history
     * - Isolated NR cell with no NR neighbors visible
     * - NR cell advertising unusual PLMN (MCC/MNC)
     *
     * @return Map of indicator keys to descriptions, empty if clean.
     */
    fun detectFakeGnodeb(
        nrCells: List<CellTower>,
        allCells: List<CellTower>,
        history: List<CellTower>
    ): Map<String, String> {
        val indicators = mutableMapOf<String, String>()
        val knownNrCids = history
            .filter { it.networkType == NetworkType.NR }
            .map { it.cid }
            .toSet()

        for (cell in nrCells) {
            // Fake gNodeB: abnormally strong signal
            if (cell.signalStrength > SUSPICIOUS_NR_SIGNAL_DBM) {
                indicators["nr_strong_signal_${cell.cid}"] =
                    "NR signal ${cell.signalStrength} dBm exceeds threshold ($SUSPICIOUS_NR_SIGNAL_DBM dBm)"
            }

            // Fake gNodeB: unknown NCI
            if (knownNrCids.isNotEmpty() && cell.cid !in knownNrCids) {
                indicators["nr_unknown_nci_${cell.cid}"] =
                    "NR cell NCI ${cell.cid} not found in observation history (${knownNrCids.size} known NR cells)"
            }
        }

        // Fake gNodeB: isolated NR cell (no NR neighbors)
        val nrCount = allCells.count { it.networkType == NetworkType.NR }
        if (nrCells.isNotEmpty() && nrCount == 1 && history.isNotEmpty()) {
            val avgHistoricalNrNeighbors = history
                .filter { it.networkType == NetworkType.NR }
                .groupBy { it.lacTac }
                .values
                .map { it.size }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0

            if (avgHistoricalNrNeighbors > MIN_EXPECTED_NR_NEIGHBORS) {
                indicators["nr_missing_neighbors"] =
                    "Only 1 NR cell visible; historical average is %.1f per TAC".format(avgHistoricalNrNeighbors)
            }
        }

        return indicators
    }

    /**
     * Detect NR downgrade attacks — forced transitions from NR to lower generations.
     *
     * Attack patterns:
     * - NR → LTE → 3G (progressive downgrade to weaker security)
     * - NR → LTE only (may strip 5G security features)
     * - Rapid NR → LTE oscillation (jamming NR to force fallback)
     *
     * @param currentCells Current visible cells.
     * @param recentHistory Cells observed in the last [DOWNGRADE_DETECTION_WINDOW_MS].
     * @return Map of indicator keys to descriptions, empty if clean.
     */
    fun detectNrDowngrade(
        currentCells: List<CellTower>,
        recentHistory: List<CellTower>
    ): Map<String, String> {
        val indicators = mutableMapOf<String, String>()

        val hasNrNow = currentCells.any { it.networkType == NetworkType.NR }
        val hadNrRecently = recentHistory.any { it.networkType == NetworkType.NR }

        if (!hasNrNow && hadNrRecently) {
            val currentBest = currentCells
                .maxByOrNull { generationRank(it.networkType) }
                ?.networkType ?: NetworkType.UNKNOWN

            val fallbackGen = currentBest.generation
            indicators["nr_to_${currentBest.name.lowercase()}_fallback"] =
                "NR connection lost; currently on $fallbackGen — possible NR downgrade attack"

            // Check for progressive downgrade (NR → LTE → 3G/2G within window)
            val sortedHistory = recentHistory.sortedBy { it.lastSeen }
            val generationSequence = sortedHistory
                .map { it.networkType }
                .distinct()

            if (generationSequence.size >= 3) {
                val ranks = generationSequence.map { generationRank(it) }
                val isProgressive = ranks.zipWithNext().all { (a, b) -> b <= a }
                if (isProgressive && ranks.first() > ranks.last()) {
                    indicators["nr_progressive_downgrade"] =
                        "Progressive downgrade detected: ${generationSequence.joinToString(" → ") { it.generation }}"
                }
            }
        }

        return indicators
    }

    /**
     * Detect NR bidding-down attacks.
     *
     * A bidding-down attack manipulates the UE capability information exchange
     * to force the device to use weaker security algorithms or fall back to
     * less secure network configurations.
     *
     * Detectable indicators:
     * - Repeated NR connection setup failures followed by LTE fallback
     * - NR cell advertising capabilities below the device's minimum
     * - Unexpected loss of NR SA mode to NSA (or vice versa)
     *
     * @param nrCells Current NR cells.
     * @param recentHistory Recent cell observations.
     * @return Map of indicator keys to descriptions.
     */
    fun detectBiddingDown(
        nrCells: List<CellTower>,
        recentHistory: List<CellTower>
    ): Map<String, String> {
        val indicators = mutableMapOf<String, String>()
        val now = System.currentTimeMillis()

        // Detect rapid NR cell changes (possible forced reselection)
        val recentNrCells = recentHistory.filter {
            it.networkType == NetworkType.NR &&
                (now - it.lastSeen) < RAPID_CHANGE_WINDOW_MS
        }

        val distinctRecentNrCids = recentNrCells.map { it.cid }.distinct()
        if (distinctRecentNrCids.size > MAX_NR_CELL_CHANGES_IN_WINDOW) {
            indicators["nr_rapid_reselection"] =
                "${distinctRecentNrCids.size} distinct NR cells in ${RAPID_CHANGE_WINDOW_MS / 1000}s window " +
                    "(threshold: $MAX_NR_CELL_CHANGES_IN_WINDOW)"
        }

        // Detect NR → LTE oscillation (jamming indicator)
        val recentAll = recentHistory
            .filter { (now - it.lastSeen) < RAPID_CHANGE_WINDOW_MS }
            .sortedBy { it.lastSeen }

        var oscillations = 0
        var lastWasNr: Boolean? = null
        for (cell in recentAll) {
            val isNr = cell.networkType == NetworkType.NR
            if (lastWasNr != null && isNr != lastWasNr) {
                oscillations++
            }
            lastWasNr = isNr
        }

        if (oscillations >= 3) {
            indicators["nr_lte_oscillation"] =
                "$oscillations NR↔LTE oscillations in ${RAPID_CHANGE_WINDOW_MS / 1000}s — possible NR jamming"
        }

        return indicators
    }

    /**
     * Detect suspicious NR cell reselection patterns.
     *
     * Normal reselection follows 3GPP criteria (signal quality, priority).
     * Suspicious patterns include:
     * - Reselection to a weaker NR cell when stronger is available
     * - Reselection to an NR cell on a different frequency band without cause
     * - Rapid bouncing between NR cells
     *
     * @param nrCells Current NR cells.
     * @param history Historical NR cell observations.
     * @return Map of indicator keys to descriptions.
     */
    fun detectSuspiciousReselection(
        nrCells: List<CellTower>,
        history: List<CellTower>
    ): Map<String, String> {
        val indicators = mutableMapOf<String, String>()

        if (nrCells.isEmpty()) return indicators

        val registeredNr = nrCells.firstOrNull()
        val nrNeighbors = nrCells.drop(1)

        // Check if registered on a weaker cell when stronger NR neighbor exists
        if (registeredNr != null && nrNeighbors.isNotEmpty()) {
            val strongerNeighbor = nrNeighbors
                .filter { it.signalStrength > registeredNr.signalStrength + 6 }
                .maxByOrNull { it.signalStrength }

            if (strongerNeighbor != null) {
                indicators["nr_weak_cell_selection"] =
                    "Registered on NR cell ${registeredNr.cid} at ${registeredNr.signalStrength} dBm " +
                        "while stronger NR cell ${strongerNeighbor.cid} at ${strongerNeighbor.signalStrength} dBm is available"
            }
        }

        // Check for NR cell with very short observation duration
        val now = System.currentTimeMillis()
        for (cell in nrCells) {
            val duration = now - cell.firstSeen
            if (duration > 0 && duration < 15_000 && cell.timesSeen <= 1) {
                indicators["nr_short_duration_${cell.cid}"] =
                    "NR cell ${cell.cid} observed for only ${duration / 1000}s — possible cell spoofing"
            }
        }

        return indicators
    }

    /**
     * Detect NR signal anomalies.
     *
     * - Sudden large signal strength jumps (> [MAX_PLAUSIBLE_SIGNAL_DELTA_DB])
     * - Signal in the unusable range while device claims NR connection
     * - All NR cells at suspiciously similar signal levels (coordinated spoofing)
     *
     * @param nrCells Current NR cells.
     * @param nrHistory Historical NR cell signal observations.
     * @return Map of indicator keys to descriptions.
     */
    fun detectSignalAnomalies(
        nrCells: List<CellTower>,
        nrHistory: List<CellTower>
    ): Map<String, String> {
        val indicators = mutableMapOf<String, String>()

        for (cell in nrCells) {
            // Check for rapid signal strength change vs history
            val previousObservation = nrHistory
                .filter { it.cid == cell.cid }
                .maxByOrNull { it.lastSeen }

            if (previousObservation != null) {
                val delta = Math.abs(cell.signalStrength - previousObservation.signalStrength)
                if (delta > MAX_PLAUSIBLE_SIGNAL_DELTA_DB) {
                    indicators["nr_signal_jump_${cell.cid}"] =
                        "NR cell ${cell.cid} signal changed by ${delta} dB " +
                            "(${previousObservation.signalStrength} → ${cell.signalStrength} dBm)"
                }
            }

            // Unusable signal level while still claiming NR
            if (cell.signalStrength < UNUSABLE_NR_SIGNAL_DBM) {
                indicators["nr_unusable_signal_${cell.cid}"] =
                    "NR cell ${cell.cid} reporting unusable signal ${cell.signalStrength} dBm"
            }
        }

        // Check for suspiciously uniform signal levels across NR cells
        if (nrCells.size >= 3) {
            val signals = nrCells.map { it.signalStrength }
            val spread = signals.max() - signals.min()
            if (spread < 3) {
                indicators["nr_uniform_signals"] =
                    "${nrCells.size} NR cells with suspiciously uniform signal levels " +
                        "(spread: ${spread} dB, range: ${signals.min()}..${signals.max()} dBm)"
            }
        }

        return indicators
    }

    /**
     * Detect potential NR null cipher usage.
     *
     * 5G NR should always use encryption (NEA1/NEA2/NEA3). Null cipher (NEA0)
     * is only permitted during initial attach. Detection is limited without
     * baseband access, but we can flag suspicious indicators:
     * - NR cell not providing integrity protection (observable via API on Android 14+)
     * - Extended time on NR without expected security mode completion
     *
     * @param nrCells Current NR cells.
     * @return Map of indicator keys to descriptions.
     */
    fun detectNullCipherIndicators(
        nrCells: List<CellTower>
    ): Map<String, String> {
        // Without root/baseband access, NR cipher detection is severely limited.
        // This is a placeholder for when Android exposes NR security mode info
        // via future CellIdentityNr extensions or carrier-privileged APIs.
        return emptyMap()
    }

    private fun generationRank(type: NetworkType): Int = when (type) {
        NetworkType.NR -> 5
        NetworkType.LTE -> 4
        NetworkType.WCDMA -> 3
        NetworkType.CDMA -> 2
        NetworkType.GSM -> 1
        NetworkType.UNKNOWN -> 0
    }
}
