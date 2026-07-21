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
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.ThreatType
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects potential fake base transceiver stations (IMSI catchers / Stingrays).
 *
 * Research-backed detection signatures derived from:
 *   - FBS-Radar (Li et al., NDSS 2017)
 *   - CellGuard (Arnold et al., RAID 2024)
 *   - Tucker et al. (NDSS 2025) — detection confidence matrix
 *   - EFF Rayhunter heuristics (2025)
 *
 * Checks performed (all offline, no root required):
 *   1. Signal strength path-loss anomaly (> -50 dBm or model-based)
 *   2. Unknown Cell ID not in observation history
 *   3. MCC/MNC validation against international carrier database
 *   4. Missing neighbor cells (isolation indicator)
 *   5. EARFCN/ARFCN band validation per carrier (when available)
 *   6. TAC/LAC geographic anomaly (learned from history)
 *   7. Cell ID format validation per network type
 *   8. PCI/PSC reuse distance anomaly (when available)
 *   9. Correlation-boosted scoring for co-occurring indicators
 */
class FakeBtsDetector @Inject constructor() : ThreatDetector {

    override val type: ThreatType = ThreatType.FAKE_BTS

    // ════════════════════════════════════════════════════════════════════════════
    //  Constants & Reference Data
    // ════════════════════════════════════════════════════════════════════════════

    companion object {

        // ── Sentinel for unavailable fields ─────────────────────────────────────
        private const val UNAVAILABLE = Int.MAX_VALUE

        // ── Signal thresholds ───────────────────────────────────────────────────
        /** Hard threshold — signals this strong are always suspicious. */
        private const val STRONG_SIGNAL_THRESHOLD = -50
        /** Path-loss deviation threshold in dB before flagging. */
        private const val PATH_LOSS_DEVIATION_THRESHOLD_DB = 20.0
        /** Default macro tower EIRP in dBm (used when we don't know actual). */
        private const val DEFAULT_TX_POWER_DBM = 46.0

        // ── PCI reuse ───────────────────────────────────────────────────────────
        /** Minimum expected separation (km) for PCI reuse in real networks. */
        private const val PCI_REUSE_MIN_DISTANCE_KM = 3.0

        // ── TAC geographic validation ───────────────────────────────────────────
        /** Max radius (km) a single TAC should span. Larger = likely different area. */
        private const val TAC_MAX_RADIUS_KM = 50.0

        // ── Cell ID limits by network type ──────────────────────────────────────
        /** GSM CID: 16-bit (0–65535). */
        private const val GSM_CID_MAX = 65535
        /** WCDMA UTRAN CID: 28-bit. */
        private const val WCDMA_CID_MAX = 268435455 // 2^28 - 1
        /** LTE ECI: 28-bit = eNB_ID (20 bits) + sector (8 bits). */
        private const val LTE_CID_MAX = 268435455 // 2^28 - 1
        /** NR NCI: 36-bit (stored as Int so capped at Int.MAX_VALUE in model). */
        private const val NR_CID_MAX = Int.MAX_VALUE // true limit is 2^36 - 1

        // ══════════════════════════════════════════════════════════════════════
        //  International Carrier Database
        //  MCC → Set of valid MNCs
        // ══════════════════════════════════════════════════════════════════════

        /**
         * Known valid MCC/MNC pairs for major carriers worldwide.
         * Used for basic sanity: "does this MCC/MNC pair actually exist?"
         *
         * Coverage: US (comprehensive), UK, Germany, France, Spain,
         * Japan, Australia, Canada, plus placeholders for common MCCs.
         */
        private val KNOWN_CARRIERS: Map<Int, Set<Int>> = buildMap {
            // ── United States (MCC 310–316) ─────────────────────────────────
            put(310, setOf(
                12, 13, 16, 20, 26, 30, 32, 34, 40, 53, 54, 66,
                70, 80, 90, 100, 110, 120, 150, 160, 170, 180, 190,
                200, 210, 220, 230, 240, 250, 260, 270, 300, 310, 311,
                320, 330, 340, 350, 370, 380, 390, 400, 410, 420, 430,
                440, 450, 460, 470, 480, 490, 500, 510, 520, 530, 540,
                560, 570, 580, 590, 600, 610, 620, 630, 640, 650, 660,
                670, 680, 690, 700, 710, 720, 730, 740, 750, 760, 770,
                780, 790, 800, 830, 840, 850, 870, 880, 890, 900, 910,
                920, 930, 940, 950, 960, 970, 980, 990
            ))
            put(311, setOf(
                10, 12, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110,
                120, 130, 140, 150, 160, 170, 180, 190, 200, 210, 220,
                230, 240, 250, 260, 270, 280, 290, 300, 310, 320, 330,
                340, 350, 360, 370, 380, 390, 400, 410, 420, 430, 440,
                450, 460, 470, 480, 481, 482, 483, 484, 485, 486, 487,
                488, 489, 490, 500, 510, 520, 530, 540, 550, 560, 570,
                580, 590, 600, 610, 620, 630, 640, 650, 660, 670, 680,
                690, 700, 710, 720, 730, 740, 750, 760, 770, 780, 790,
                800, 810, 820, 830, 840, 850, 860, 870, 880, 890, 900,
                910, 920, 930, 940, 950, 960, 970, 980, 990
            ))
            put(312, setOf(
                10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120,
                130, 140, 150, 160, 170, 180, 190, 200, 210, 220, 230,
                240, 250, 260, 270, 280, 290, 300, 310, 320, 330, 340,
                350, 360, 370, 380, 390, 400, 410, 420, 430, 440, 450,
                460, 470, 480, 490, 500, 510, 520, 530, 540, 550, 560,
                570, 580, 590, 600, 610, 620, 630, 640, 650, 660, 670,
                680, 690, 700, 710, 720, 730, 740, 750, 760, 770
            ))
            put(313, setOf(100, 200))
            put(316, setOf(10, 11))

            // ── Canada (MCC 302) ────────────────────────────────────────────
            put(302, setOf(
                220, 221, 270, 290, 320, 350, 360, 361, 370, 380, 390,
                490, 500, 510, 530, 540, 560, 590, 610, 620, 640, 651,
                652, 653, 654, 655, 656, 657, 660, 670, 680, 690, 700,
                710, 720, 730, 740, 750, 760, 770, 780, 790, 860, 880,
                940
            ))

            // ── United Kingdom (MCC 234/235) ────────────────────────────────
            put(234, setOf(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 22, 25, 26, 27, 28, 30, 31, 32, 33, 34,
                35, 36, 37, 38, 39, 50, 51, 55, 58, 86
            ))
            put(235, setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 77, 91, 92, 94))

            // ── Germany (MCC 262) ───────────────────────────────────────────
            put(262, setOf(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 33, 41, 42, 43, 60, 72, 73,
                74, 77, 78, 79, 92, 98
            ))

            // ── France (MCC 208) ────────────────────────────────────────────
            put(208, setOf(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16, 17,
                18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
                86, 87, 88, 89, 90, 91, 92, 93, 94, 95
            ))

            // ── Spain (MCC 214) ─────────────────────────────────────────────
            put(214, setOf(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 15, 16, 17, 18, 19,
                20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33,
                34, 35, 36, 37
            ))

            // ── Japan (MCC 440/441) ─────────────────────────────────────────
            put(440, setOf(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33,
                34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47,
                48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 60, 61, 62,
                63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76,
                77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
                91, 92, 93, 94, 95, 96, 97, 98, 99
            ))
            put(441, setOf(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                40, 41, 42, 43, 44, 45, 50, 51, 61, 62, 63, 64, 65, 70,
                71, 72, 73, 74, 90, 91, 92, 93
            ))

            // ── Australia (MCC 505) ─────────────────────────────────────────
            put(505, setOf(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 30, 31,
                32, 33, 34, 35, 36, 37, 38, 39, 62, 68, 71, 72, 88, 90,
                99
            ))
        }

        // ══════════════════════════════════════════════════════════════════════
        //  US Carrier → Expected LTE Band Allocations
        //  Used for EARFCN band validation.
        // ══════════════════════════════════════════════════════════════════════

        /**
         * Maps major US carrier MCC/MNC pairs to the set of LTE bands
         * they are licensed to operate in the US. If a tower claims a
         * carrier identity but broadcasts on a band not in this set, it
         * is almost certainly fake (<1% FP).
         */
        private val US_CARRIER_BANDS: Map<Pair<Int, Int>, Set<Int>> = mapOf(
            // T-Mobile family
            (310 to 260) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 200) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 210) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 220) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 230) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 240) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 250) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 270) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 300) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 310) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (311 to 490) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (312 to 530) to setOf(2, 4, 5, 12, 25, 41, 66, 71),
            (310 to 120) to setOf(2, 4, 5, 12, 25, 41, 66, 71), // Sprint → T-Mobile

            // AT&T family
            (310 to 410) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),
            (310 to 150) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),
            (310 to 170) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),
            (310 to 380) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),
            (310 to 560) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),
            (310 to 680) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),
            (311 to 180) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),
            (311 to 280) to setOf(2, 4, 5, 12, 14, 17, 29, 30, 40, 66),

            // Verizon family
            (311 to 480) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 481) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 482) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 483) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 484) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 485) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 486) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 487) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 488) to setOf(2, 4, 5, 13, 46, 48, 66),
            (311 to 489) to setOf(2, 4, 5, 13, 46, 48, 66),
            (312 to 280) to setOf(2, 4, 5, 13, 46, 48, 66), // Visible

            // Dish Network
            (311 to 880) to setOf(29, 66, 70),
            (312 to 680) to setOf(29, 66, 70),

            // US Cellular
            (311 to 580) to setOf(2, 4, 5, 12),
            (311 to 220) to setOf(2, 4, 5, 12),
            (311 to 221) to setOf(2, 4, 5, 12),

            // C Spire
            (311 to 230) to setOf(2, 4, 5, 12, 26),
        )

        // ══════════════════════════════════════════════════════════════════════
        //  EARFCN → LTE Band Mapping
        //  Based on 3GPP TS 36.101 Table 5.7.3-1
        // ══════════════════════════════════════════════════════════════════════

        /** EARFCN downlink ranges: Pair(lowEarfcn, highEarfcn) → Band number. */
        private val EARFCN_BAND_TABLE: List<Triple<Int, Int, Int>> = listOf(
            Triple(0, 599, 1),
            Triple(600, 1199, 2),
            Triple(1200, 1949, 3),
            Triple(1950, 2399, 4),
            Triple(2400, 2649, 5),
            Triple(2650, 2749, 6),
            Triple(2750, 3449, 7),
            Triple(3450, 3799, 8),
            Triple(3800, 4149, 9),
            Triple(4150, 4749, 10),
            Triple(4750, 4949, 11),
            Triple(5010, 5179, 12),
            Triple(5180, 5279, 13),
            Triple(5280, 5379, 14),
            Triple(5730, 5849, 17),
            Triple(5850, 5999, 18),
            Triple(6000, 6149, 19),
            Triple(6150, 6449, 20),
            Triple(6450, 6599, 21),
            Triple(6600, 7399, 22),
            Triple(7500, 7699, 23),
            Triple(7700, 8039, 24),
            Triple(8040, 8689, 25),
            Triple(8690, 9039, 26),
            Triple(9040, 9209, 27),
            Triple(9210, 9659, 28),
            Triple(9660, 9769, 29),
            Triple(9770, 9869, 30),
            Triple(9870, 9919, 31),
            Triple(9920, 10359, 32),
            Triple(36000, 36199, 33),
            Triple(36200, 36349, 34),
            Triple(36350, 36949, 35),
            Triple(36950, 37549, 36),
            Triple(37550, 37749, 37),
            Triple(37750, 38249, 38),
            Triple(38250, 38649, 39),
            Triple(38650, 39649, 40),
            Triple(39650, 41589, 41),
            Triple(41590, 43589, 42),
            Triple(43590, 45589, 43),
            Triple(45590, 46589, 44),
            Triple(46590, 46789, 45),
            Triple(46790, 54539, 46),
            Triple(54540, 55239, 47),
            Triple(55240, 56739, 48),
            Triple(56740, 58239, 49),
            Triple(58240, 59089, 50),
            Triple(59090, 59139, 51),
            Triple(59140, 60139, 52),
            Triple(60140, 60254, 53),
            Triple(65536, 66435, 65),
            Triple(66436, 67335, 66),
            Triple(67336, 67535, 67),
            Triple(67536, 67835, 68),
            Triple(67836, 68335, 69),
            Triple(68336, 68585, 70),
            Triple(68586, 68935, 71),
            Triple(68936, 68985, 72),
            Triple(68986, 69035, 73),
            Triple(69036, 69465, 74),
            Triple(69466, 70315, 75),
            Triple(70316, 70365, 76),
            Triple(70366, 70545, 85),
        )

        /**
         * Maps an EARFCN (downlink) to its LTE band number.
         * Returns -1 if the EARFCN doesn't map to any known band.
         */
        fun earfcnToBand(earfcn: Int): Int {
            for ((low, high, band) in EARFCN_BAND_TABLE) {
                if (earfcn in low..high) return band
            }
            return -1
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Main Analysis
    // ════════════════════════════════════════════════════════════════════════════

    override suspend fun analyze(
        cells: List<CellTower>,
        history: List<CellTower>
    ): DetectionResult? {
        if (cells.isEmpty()) return null

        val knownCids = history.map { it.cid }.toSet()
        val indicators = mutableMapOf<String, String>()

        // Per-cell indicator flags for correlation scoring
        data class CellFlags(
            var strongSignal: Boolean = false,
            var unknownCid: Boolean = false,
            var badCarrier: Boolean = false,
            var badBand: Boolean = false,
            var badTac: Boolean = false,
            var badCidFormat: Boolean = false,
            var pciReuse: Boolean = false,
            var pathLossAnomaly: Boolean = false
        )

        val cellFlags = mutableMapOf<Int, CellFlags>()
        var baseScore = 0.0

        for (cell in cells) {
            val flags = CellFlags()
            cellFlags[cell.cid] = flags

            // ── Check 1: Signal Strength / Path-Loss Anomaly ────────────────
            val signalResult = checkSignalStrength(cell, history)
            if (signalResult != null) {
                baseScore += signalResult.first
                indicators[signalResult.second] = signalResult.third
                flags.strongSignal = true
                if (signalResult.second.contains("path_loss")) {
                    flags.pathLossAnomaly = true
                }
            }

            // ── Check 2: Unknown Cell ID ────────────────────────────────────
            if (cell.cid !in knownCids && history.isNotEmpty()) {
                baseScore += 1.0
                indicators["unknown_cid_${cell.cid}"] =
                    "Cell ID ${cell.cid} not found in observation history " +
                    "(${knownCids.size} known cells)"
                flags.unknownCid = true
            }

            // ── Check 3: MCC/MNC Validation (International) ────────────────
            val carrierResult = checkCarrier(cell)
            if (carrierResult != null) {
                baseScore += carrierResult.first
                indicators[carrierResult.second] = carrierResult.third
                flags.badCarrier = true
            }

            // ── Check 5: EARFCN / Band Validation ──────────────────────────
            val bandResult = checkBand(cell)
            if (bandResult != null) {
                baseScore += bandResult.first
                indicators[bandResult.second] = bandResult.third
                flags.badBand = true
            }

            // ── Check 6: TAC/LAC Geographic Anomaly ─────────────────────────
            val tacResult = checkTacGeography(cell, history)
            if (tacResult != null) {
                baseScore += tacResult.first
                indicators[tacResult.second] = tacResult.third
                flags.badTac = true
            }

            // ── Check 7: Cell ID Format Validation ──────────────────────────
            val cidFormatResult = checkCidFormat(cell)
            if (cidFormatResult != null) {
                baseScore += cidFormatResult.first
                indicators[cidFormatResult.second] = cidFormatResult.third
                flags.badCidFormat = true
            }
        }

        // ── Check 4: Missing Neighbor Cells ─────────────────────────────────
        if (cells.size == 1 && history.isNotEmpty()) {
            val avgHistoricalNeighbors = history.groupBy { it.lacTac }
                .values
                .map { it.size }
                .average()

            if (avgHistoricalNeighbors > 2.0) {
                baseScore += 1.0
                indicators["missing_neighbors"] =
                    "Only 1 cell visible; historical average is %.1f per LAC/TAC"
                        .format(avgHistoricalNeighbors)
            }
        }

        // ── Check 8: PCI Reuse Distance Anomaly ─────────────────────────────
        val pciResult = checkPciReuse(cells, history)
        for (result in pciResult) {
            baseScore += result.first
            indicators[result.second] = result.third
            // Mark the relevant cell
            cellFlags.values.forEach { it.pciReuse = true }
        }

        // ════════════════════════════════════════════════════════════════════
        //  Correlation-Boosted Scoring
        //
        //  When multiple independent indicators fire on the SAME cell,
        //  that's much more suspicious than the sum of parts.
        //  Apply multiplicative boost for correlated indicators.
        // ════════════════════════════════════════════════════════════════════

        var correlationBonus = 0.0
        for ((cid, flags) in cellFlags) {
            val flagCount = listOf(
                flags.strongSignal,
                flags.unknownCid,
                flags.badCarrier,
                flags.badBand,
                flags.badTac,
                flags.badCidFormat,
                flags.pciReuse,
                flags.pathLossAnomaly
            ).count { it }

            if (flagCount >= 3) {
                // Three or more indicators on one cell = very high confidence
                correlationBonus += 2.0
                indicators["correlated_indicators_$cid"] =
                    "$flagCount independent anomalies on Cell ID $cid — " +
                    "high confidence fake BTS"
            } else if (flagCount == 2) {
                // Two indicators = notable correlation bonus
                correlationBonus += 0.8

                // Specific high-value pairs
                if (flags.unknownCid && flags.strongSignal) {
                    correlationBonus += 0.5
                    indicators["correlation_cid_signal_$cid"] =
                        "Unknown cell AND abnormally strong signal on Cell ID $cid"
                }
                if (flags.badBand && flags.badCarrier) {
                    correlationBonus += 0.7
                    indicators["correlation_band_carrier_$cid"] =
                        "Invalid band AND carrier anomaly on Cell ID $cid"
                }
            }
        }

        val totalScore = baseScore + correlationBonus

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
            summary = buildSummary(indicators, totalScore),
            details = indicators
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Individual Check Implementations
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Check 1: Signal strength anomaly.
     *
     * Two-tier approach:
     *   a) Hard threshold: > -50 dBm is always suspicious.
     *   b) Path-loss model: If we know where this CID was previously seen,
     *      compare actual signal to predicted signal at current distance.
     *      Flag if actual is significantly stronger than predicted.
     *
     * Returns Triple(score, indicatorKey, description) or null.
     */
    private fun checkSignalStrength(
        cell: CellTower,
        history: List<CellTower>
    ): Triple<Double, String, String>? {

        // Tier A: Hard threshold (always applies)
        if (cell.signalStrength > STRONG_SIGNAL_THRESHOLD) {
            return Triple(
                1.5,
                "strong_signal_${cell.cid}",
                "Signal ${cell.signalStrength} dBm exceeds threshold " +
                "($STRONG_SIGNAL_THRESHOLD dBm)"
            )
        }

        // Tier B: Path-loss model (requires location data)
        if (cell.latitude != null && cell.longitude != null) {
            // Find historical sightings of this same CID with location data
            val historicalSightings = history.filter {
                it.cid == cell.cid && it.latitude != null && it.longitude != null
            }

            if (historicalSightings.isNotEmpty()) {
                // Use median position of historical sightings as expected tower location
                val towerLat = historicalSightings.mapNotNull { it.latitude }.median()
                val towerLon = historicalSightings.mapNotNull { it.longitude }.median()

                val distanceKm = haversineKm(
                    cell.latitude, cell.longitude, towerLat, towerLon
                )

                if (distanceKm > 0.05) { // Only meaningful above 50m
                    // Free-space path loss: RSRP = Tx - 20*log10(d_m) - 20*log10(f_MHz) + 27.55
                    // Use representative frequency for the network type
                    val freqMhz = estimateFrequencyMhz(cell)
                    val distanceM = distanceKm * 1000.0
                    val expectedRsrp = DEFAULT_TX_POWER_DBM -
                        20.0 * log10(distanceM) -
                        20.0 * log10(freqMhz) +
                        27.55

                    val deviation = cell.signalStrength - expectedRsrp

                    if (deviation > PATH_LOSS_DEVIATION_THRESHOLD_DB) {
                        return Triple(
                            1.2,
                            "path_loss_anomaly_${cell.cid}",
                            "Signal ${cell.signalStrength} dBm is %.1f dB stronger than "
                                .format(deviation) +
                            "path-loss model predicts at %.1f km from known position"
                                .format(distanceKm)
                        )
                    }
                }
            }
        }

        return null
    }

    /**
     * Check 3: MCC/MNC carrier validation.
     *
     * Two levels:
     *   a) For countries in our database, verify the MNC is a known carrier.
     *   b) Basic MCC sanity: MCC must be in valid ITU range (200-799).
     */
    private fun checkCarrier(cell: CellTower): Triple<Double, String, String>? {
        val mcc = cell.mcc
        val mnc = cell.mnc

        // Skip cells with missing MCC/MNC
        if (mcc == 0 || mnc == 0) return null

        // Level 1: MCC range sanity (ITU assigns 200-799)
        if (mcc < 200 || mcc > 799) {
            return Triple(
                1.5,
                "invalid_mcc_${cell.cid}",
                "MCC $mcc is outside valid ITU range (200-799)"
            )
        }

        // Level 2: If we have the MCC in our database, check MNC
        val validMncs = KNOWN_CARRIERS[mcc]
        if (validMncs != null && mnc !in validMncs) {
            return Triple(
                0.5,
                "unusual_carrier_${cell.cid}",
                "MCC/MNC $mcc/$mnc not in known carrier database for " +
                "country code $mcc"
            )
        }

        return null
    }

    /**
     * Check 5: EARFCN / Band validation.
     *
     * If the cell reports an EARFCN, map it to an LTE band and verify
     * the claimed carrier (MCC/MNC) is licensed to operate on that band.
     * This is a HIGH reliability indicator with <1% false positive rate.
     */
    private fun checkBand(cell: CellTower): Triple<Double, String, String>? {
        // Only works if EARFCN is available
        if (cell.earfcn == UNAVAILABLE || cell.earfcn < 0) return null

        // Currently only LTE band validation (most comprehensive data)
        if (cell.networkType != NetworkType.LTE) return null

        val band = earfcnToBand(cell.earfcn)
        if (band == -1) {
            return Triple(
                1.0,
                "invalid_earfcn_${cell.cid}",
                "EARFCN ${cell.earfcn} does not map to any known LTE band"
            )
        }

        // Check against carrier's licensed bands
        val carrierKey = cell.mcc to cell.mnc
        val allowedBands = US_CARRIER_BANDS[carrierKey]

        if (allowedBands != null && band !in allowedBands) {
            return Triple(
                2.0,  // High weight — very reliable indicator
                "wrong_band_${cell.cid}",
                "Cell claims MCC/MNC ${cell.mcc}/${cell.mnc} but broadcasts " +
                "on Band $band (EARFCN ${cell.earfcn}); carrier only uses " +
                "bands ${allowedBands.sorted()}"
            )
        }

        return null
    }

    /**
     * Check 6: TAC/LAC geographic validation.
     *
     * Learns what TACs are normal for each geographic area from history.
     * If a new TAC appears in an area where it's never been seen before
     * and the device hasn't moved, that's suspicious.
     */
    private fun checkTacGeography(
        cell: CellTower,
        history: List<CellTower>
    ): Triple<Double, String, String>? {
        // Need location on both the current cell observation and history
        if (cell.latitude == null || cell.longitude == null) return null
        if (history.isEmpty()) return null

        // Build a map of TAC → geographic centroid from history
        val tacLocations = history
            .filter { it.latitude != null && it.longitude != null }
            .groupBy { it.lacTac }

        if (tacLocations.isEmpty()) return null

        // Check if this TAC has been seen before
        val thisLacTac = cell.lacTac
        val historicalForTac = tacLocations[thisLacTac]

        if (historicalForTac != null) {
            // TAC is known — verify it's within expected geography
            val centroidLat = historicalForTac.mapNotNull { it.latitude }.average()
            val centroidLon = historicalForTac.mapNotNull { it.longitude }.average()
            val distFromCentroid = haversineKm(
                cell.latitude, cell.longitude, centroidLat, centroidLon
            )

            if (distFromCentroid > TAC_MAX_RADIUS_KM) {
                return Triple(
                    1.5,
                    "tac_geo_mismatch_${cell.cid}",
                    "TAC ${cell.lacTac} observed %.1f km from its known centroid "
                        .format(distFromCentroid) +
                    "(max expected: ${TAC_MAX_RADIUS_KM} km)"
                )
            }
        } else {
            // TAC never seen before — check if OTHER TACs are expected here
            // Find TACs that have been seen near our current position
            val nearbyTacs = tacLocations.filter { (_, towers) ->
                val lat = towers.mapNotNull { it.latitude }.average()
                val lon = towers.mapNotNull { it.longitude }.average()
                haversineKm(cell.latitude, cell.longitude, lat, lon) < 10.0
            }

            if (nearbyTacs.isNotEmpty()) {
                // We know what TACs should be here and this isn't one of them
                return Triple(
                    1.0,
                    "unknown_tac_${cell.cid}",
                    "TAC ${cell.lacTac} never observed in this area; " +
                    "expected TACs: ${nearbyTacs.keys.sorted()}"
                )
            }
        }

        return null
    }

    /**
     * Check 7: Cell ID format validation per network type.
     *
     * Real Cell IDs follow specific bit-width constraints:
     *   - GSM:  16-bit CID (0–65535)
     *   - WCDMA: 28-bit UTRAN CID (0–268435455)
     *   - LTE:  28-bit ECI = eNB_ID (20 bits) + sector (8 bits)
     *   - NR:   36-bit NCI (model stores as Int, so check Int range)
     *
     * Random or out-of-range CIDs indicate a fabricated identity.
     */
    private fun checkCidFormat(cell: CellTower): Triple<Double, String, String>? {
        val cid = cell.cid

        // CID of 0 or negative is always invalid for any real tower
        if (cid <= 0) {
            return Triple(
                0.8,
                "invalid_cid_zero_${cell.cid}",
                "Cell ID $cid is invalid (zero or negative)"
            )
        }

        return when (cell.networkType) {
            NetworkType.GSM -> {
                if (cid > GSM_CID_MAX) {
                    Triple(
                        1.5,
                        "invalid_cid_gsm_${cell.cid}",
                        "GSM Cell ID $cid exceeds 16-bit max ($GSM_CID_MAX)"
                    )
                } else null
            }
            NetworkType.WCDMA -> {
                if (cid > WCDMA_CID_MAX) {
                    Triple(
                        1.5,
                        "invalid_cid_wcdma_${cell.cid}",
                        "WCDMA Cell ID $cid exceeds 28-bit max ($WCDMA_CID_MAX)"
                    )
                } else null
            }
            NetworkType.LTE -> {
                if (cid > LTE_CID_MAX) {
                    Triple(
                        1.5,
                        "invalid_cid_lte_${cell.cid}",
                        "LTE ECI $cid exceeds 28-bit max ($LTE_CID_MAX)"
                    )
                } else {
                    // Additional LTE check: sector ID should be 0-255 (bottom 8 bits)
                    val sectorId = cid and 0xFF
                    val enbId = cid shr 8
                    // Sector > 5 is unusual (most sites have 1-3 sectors)
                    // but not impossible. Only flag egregiously high sectors.
                    if (sectorId > 32 && enbId > 0) {
                        Triple(
                            0.3,
                            "unusual_sector_lte_${cell.cid}",
                            "LTE sector ID $sectorId (eNB $enbId) is unusually " +
                            "high — most sites use sectors 0-5"
                        )
                    } else null
                }
            }
            // NR CIDs are 36-bit but stored as Int — we can't fully validate
            // the upper range, but we can still catch zero/negative
            NetworkType.NR -> null
            else -> null
        }
    }

    /**
     * Check 8: PCI/PSC reuse distance anomaly.
     *
     * Physical Cell IDs are reused with geographic separation (typically
     * 3-5 km minimum). If two cells with the same PCI are seen in close
     * proximity (from the current scan + neighbor history), one is likely fake.
     */
    private fun checkPciReuse(
        cells: List<CellTower>,
        history: List<CellTower>
    ): List<Triple<Double, String, String>> {
        val results = mutableListOf<Triple<Double, String, String>>()

        // Only works if PCI data is available
        val cellsWithPci = cells.filter { it.pci != UNAVAILABLE && it.pci >= 0 }
        if (cellsWithPci.isEmpty()) return results

        val historyWithPci = history.filter {
            it.pci != UNAVAILABLE && it.pci >= 0 &&
            it.latitude != null && it.longitude != null
        }

        for (cell in cellsWithPci) {
            if (cell.latitude == null || cell.longitude == null) continue

            // Find all history cells with the same PCI but different CID
            val samePciDifferentCid = historyWithPci.filter {
                it.pci == cell.pci && it.cid != cell.cid
            }

            for (other in samePciDifferentCid) {
                val distance = haversineKm(
                    cell.latitude, cell.longitude,
                    other.latitude!!, other.longitude!!
                )

                if (distance < PCI_REUSE_MIN_DISTANCE_KM) {
                    results.add(Triple(
                        1.5,
                        "pci_reuse_${cell.cid}_${other.cid}",
                        "PCI ${cell.pci} reused at only %.2f km separation "
                            .format(distance) +
                        "(Cell ${cell.cid} vs Cell ${other.cid}); " +
                        "minimum expected: ${PCI_REUSE_MIN_DISTANCE_KM} km"
                    ))
                    break // One PCI collision per cell is enough
                }
            }
        }

        return results
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Utilities
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Estimate center frequency (MHz) for path-loss model based on network type
     * and EARFCN (if available).
     */
    private fun estimateFrequencyMhz(cell: CellTower): Double {
        // If EARFCN is available, derive a more accurate frequency
        if (cell.earfcn != UNAVAILABLE && cell.networkType == NetworkType.LTE) {
            val band = earfcnToBand(cell.earfcn)
            return when (band) {
                2 -> 1900.0; 4 -> 2100.0; 5 -> 850.0
                12 -> 700.0; 13 -> 746.0; 17 -> 734.0
                25 -> 1900.0; 26 -> 850.0; 29 -> 717.0
                30 -> 2305.0; 41 -> 2500.0; 46 -> 5200.0
                48 -> 3600.0; 66 -> 2100.0; 71 -> 617.0
                else -> 1800.0 // reasonable LTE default
            }
        }

        // Fallback estimates by generation
        return when (cell.networkType) {
            NetworkType.GSM -> 900.0
            NetworkType.CDMA -> 850.0
            NetworkType.WCDMA -> 2100.0
            NetworkType.LTE -> 1800.0
            NetworkType.NR -> 3500.0
            NetworkType.UNKNOWN -> 1800.0
        }
    }

    /**
     * Haversine distance between two lat/lon points in kilometers.
     */
    private fun haversineKm(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val r = 6371.0 // Earth's radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** Median of a non-empty list of Doubles. */
    private fun List<Double>.median(): Double {
        val sorted = this.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    /**
     * Build a human-readable summary prioritizing the most severe indicators.
     */
    private fun buildSummary(indicators: Map<String, String>, score: Double): String {
        val count = indicators.size
        val hasCorrelation = indicators.keys.any { it.startsWith("correlated_") }
        val hasWrongBand = indicators.keys.any { it.startsWith("wrong_band_") }
        val hasStrongSignal = indicators.keys.any { it.startsWith("strong_signal_") }
        val hasPathLoss = indicators.keys.any { it.startsWith("path_loss_") }
        val hasUnknownCid = indicators.keys.any { it.startsWith("unknown_cid_") }
        val hasBadCarrier = indicators.keys.any {
            it.startsWith("unusual_carrier_") || it.startsWith("invalid_mcc_")
        }
        val hasPciReuse = indicators.keys.any { it.startsWith("pci_reuse_") }
        val hasBadCid = indicators.keys.any {
            it.startsWith("invalid_cid_") || it.startsWith("unusual_sector_")
        }
        val hasTacAnomaly = indicators.keys.any {
            it.startsWith("tac_geo_") || it.startsWith("unknown_tac_")
        }

        return when {
            hasCorrelation ->
                "Multiple correlated fake BTS indicators detected — " +
                "high confidence IMSI catcher ($count anomalies, score %.1f)".format(score)
            hasWrongBand ->
                "Tower broadcasting on wrong frequency band for claimed carrier — " +
                "likely fake base station"
            hasStrongSignal && hasUnknownCid ->
                "Unknown tower with abnormally strong signal — " +
                "possible nearby IMSI catcher"
            hasPciReuse ->
                "Physical Cell ID collision at close range — " +
                "possible cell identity spoofing"
            hasPathLoss ->
                "Signal strength inconsistent with expected path loss — " +
                "possible nearby fake transmitter"
            hasTacAnomaly && hasUnknownCid ->
                "Unknown tower with geographic TAC anomaly — " +
                "tower identity doesn't match location"
            hasStrongSignal ->
                "Abnormally strong cell signal detected — " +
                "possible nearby IMSI catcher"
            hasUnknownCid ->
                "Unknown cell tower not in observation history"
            hasBadCid ->
                "Cell ID format invalid for reported network type"
            hasBadCarrier ->
                "Unusual carrier identity detected"
            hasTacAnomaly ->
                "Tracking area anomaly — TAC doesn't match geographic expectations"
            else ->
                "Potential fake base station indicators detected " +
                "($count anomalies, score %.1f)".format(score)
        }
    }
}
