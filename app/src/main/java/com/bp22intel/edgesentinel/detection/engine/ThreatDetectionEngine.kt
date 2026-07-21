/*
 * Edge Sentinel — Threat Detection Engine with Fusion Layer
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 * Proprietary and confidential.
 *
 * Clean-room implementation. Detection-to-category mapping designed from
 * published IMSI-catcher detection research (see ThreatScorer for citations).
 * No third-party code.
 */

package com.bp22intel.edgesentinel.detection.engine

import com.bp22intel.edgesentinel.baseline.BaselineManager
import com.bp22intel.edgesentinel.detection.detectors.ThreatDetector
import com.bp22intel.edgesentinel.detection.scoring.ThreatScorer
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.ThreatType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central detection engine that orchestrates all threat detectors, scoring,
 * and a fusion layer for compound attack pattern detection.
 *
 * Runs all registered [ThreatDetector] implementations in parallel, collects
 * non-null results, maps them to the six scoring categories defined by
 * [ThreatScorer], applies compound pattern detection, and returns results
 * sorted by severity (highest first).
 *
 * ## Fusion Layer
 *
 * Real IMSI catchers trigger multiple detectors simultaneously. The fusion
 * layer recognises four compound attack patterns derived from real-world
 * case studies (DC/Oslo government-quarter deployments, EFF Rayhunter
 * validated captures, LEER-3 military EW system observations):
 *
 * 1. **Classic Stingray** — Unknown CID + Strong signal + Network downgrade + Missing neighbors
 * 2. **Silent Interception** — Known CID spoofing + Signal slightly strong + Cipher downgrade
 * 3. **Tracking-Only** — Repeated registration failures + No cipher downgrade
 * 4. **Mobile IMSI Catcher** — Transient tower + Signal movement over time
 */
@Singleton
class ThreatDetectionEngine @Inject constructor(
    private val detectors: Set<@JvmSuppressWildcards ThreatDetector>,
    private val scorer: ThreatScorer,
    private val baselineManager: BaselineManager
) {

    /** Last computed scoring result, available for callers that need it. */
    @Volatile
    var lastScoringResult: ThreatScorer.ScoringResult? = null
        private set

    /**
     * Run all detectors against the current cell environment.
     *
     * @param cells      Currently visible cell towers.
     * @param history    Previously observed cell towers for baseline comparison.
     * @param sensitivity Detection sensitivity level that adjusts scoring thresholds.
     * @param latitude   Current GPS latitude (optional; enables baseline comparison).
     * @param longitude  Current GPS longitude (optional; enables baseline comparison).
     * @param isMoving   Whether the device is currently in motion (optional; enables environmental context).
     * @param speed      Current speed in m/s (optional; enables environmental context).
     * @return List of [DetectionResult] sorted by score descending (highest threat first).
     */
    suspend fun runScan(
        cells: List<CellTower>,
        history: List<CellTower>,
        sensitivity: DetectionSensitivity = DetectionSensitivity.MEDIUM,
        latitude: Double? = null,
        longitude: Double? = null,
        isMoving: Boolean? = null,
        speed: Double? = null
    ): List<DetectionResult> = coroutineScope {
        // Run all detectors in parallel
        val detectorResults = detectors.map { detector ->
            async {
                try {
                    detector.analyze(cells, history)
                } catch (_: Exception) {
                    // Individual detector failure must not crash the scan
                    null
                }
            }
        }

        // Run baseline comparison concurrently with detectors
        val baselineResult = if (latitude != null && longitude != null) {
            async {
                try {
                    baselineManager.processObservation(latitude, longitude, cells)
                } catch (_: Exception) {
                    null
                }
            }
        } else null

        val results = detectorResults.awaitAll().filterNotNull().toMutableList()
        val anomaly = baselineResult?.await()

        // Convert a significant baseline anomaly into a DetectionResult
        if (anomaly != null && !anomaly.isNewLocation && anomaly.compositeScore > 0.3) {
            results.add(
                DetectionResult(
                    threatType = ThreatType.SIGNAL_ANOMALY,
                    score = anomaly.compositeScore * 5.0,
                    confidence = when {
                        anomaly.confidence.minObservations >= 20 -> Confidence.HIGH
                        anomaly.confidence.minObservations >= 10 -> Confidence.MEDIUM
                        else -> Confidence.LOW
                    },
                    summary = "RF environment deviates from learned baseline (%.0f%% anomaly)".format(
                        anomaly.compositeScore * 100
                    ),
                    details = anomaly.details
                )
            )
        }

        if (results.isEmpty()) {
            lastScoringResult = null
            return@coroutineScope emptyList()
        }

        // ---- Build category indicators from detection results ----
        // Track per-category contributor counts for weighted accumulation
        val indicators = mutableMapOf<String, Double>()
        val categoryCounts = mutableMapOf<String, Int>()

        for (result in results) {
            mapToCategories(result, indicators, categoryCounts)
        }

        // Fold baseline anomaly into signal-anomaly category
        if (anomaly != null && !anomaly.isNewLocation) {
            accumulateWeightedMax(
                indicators,
                categoryCounts,
                ThreatScorer.KEY_SIGNAL_ANOMALY,
                anomaly.compositeScore
            )
        }

        // ---- Environmental context ----
        computeEnvironmentalContext(cells, isMoving, speed, indicators, categoryCounts)

        // ---- Fusion: Compound attack pattern detection ----
        val compoundPatterns = detectCompoundPatterns(results, cells, indicators)

        // Inject compound patterns as synthetic high-priority DetectionResults
        for (pattern in compoundPatterns) {
            results.add(
                DetectionResult(
                    threatType = ThreatType.COMPOUND_PATTERN,
                    score = pattern.confidence * 10.0,
                    confidence = if (pattern.confidence >= 0.85) Confidence.HIGH else Confidence.MEDIUM,
                    summary = "${pattern.name}: ${pattern.description}",
                    details = mapOf(
                        "compound_pattern" to pattern.name,
                        "compound_confidence" to "%.2f".format(pattern.confidence),
                        "matched_indicators" to pattern.matchedIndicators.joinToString(", ")
                    )
                )
            )
        }

        // Compute composite score (determines overall threat level)
        val scoringResult = scorer.calculateScore(indicators, sensitivity, compoundPatterns)
        lastScoringResult = scoringResult

        // Return individual results ordered by severity
        results.sortedByDescending { it.score }
    }

    // -----------------------------------------------------------------
    // Category mapping
    // -----------------------------------------------------------------

    /**
     * Map a single [DetectionResult] into the six-category scoring model.
     *
     * Each [ThreatType] contributes to one or more categories based on the
     * nature of the detection. Values are accumulated via weighted-max so
     * that multiple independent detectors confirming the same category
     * produce a meaningful bonus beyond the single strongest signal.
     */
    private fun mapToCategories(
        result: DetectionResult,
        indicators: MutableMap<String, Double>,
        counts: MutableMap<String, Int>
    ) {
        when (result.threatType) {

            ThreatType.FAKE_BTS -> {
                // Unknown cell / strong-signal anomaly → signal + tower categories
                if (result.details.keys.any { it.startsWith("unknown_cid") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.9)
                }
                if (result.details.containsKey("missing_neighbors")) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.8)
                }
                if (result.details.keys.any { it.startsWith("strong_signal") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_SIGNAL_ANOMALY, 0.85)
                }
            }

            ThreatType.NETWORK_DOWNGRADE -> {
                // RAT / cipher downgrade → protocol violation + network integrity
                accumulateWeightedMax(
                    indicators, counts,
                    ThreatScorer.KEY_PROTOCOL_VIOLATION,
                    (result.score / 5.0).coerceAtMost(1.0)
                )
                accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_NETWORK_INTEGRITY, 0.7)
            }

            ThreatType.TRACKING_PATTERN -> {
                // LAC oscillation, rapid reselection → tower + temporal categories
                if (result.details.containsKey("unknown_lac")) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.8)
                }
                if (result.details.containsKey("rapid_reselection")) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TEMPORAL_PATTERN, 0.9)
                }
                if (result.details.containsKey("lac_oscillation")) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TEMPORAL_PATTERN, 0.85)
                }
                if (result.details.containsKey("short_duration")) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TEMPORAL_PATTERN, 0.7)
                }
            }

            ThreatType.SILENT_SMS -> {
                // Silent SMS / Type-0 → protocol violation
                accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_PROTOCOL_VIOLATION, 0.6)
            }

            ThreatType.CIPHER_ANOMALY -> {
                // Cipher mode issues → protocol violation + network integrity
                accumulateWeightedMax(
                    indicators, counts,
                    ThreatScorer.KEY_PROTOCOL_VIOLATION,
                    (result.score / 5.0).coerceAtMost(1.0)
                )
                accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_NETWORK_INTEGRITY, 0.8)
            }

            ThreatType.SIGNAL_ANOMALY -> {
                // RF fingerprint mismatch → signal anomaly
                accumulateWeightedMax(
                    indicators, counts,
                    ThreatScorer.KEY_SIGNAL_ANOMALY,
                    (result.score / 5.0).coerceAtMost(1.0)
                )
            }

            ThreatType.NR_ANOMALY -> {
                // 5G / NR-specific anomalies distribute across multiple categories
                if (result.details.keys.any { it.startsWith("nr_unknown_nci") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.9)
                }
                if (result.details.keys.any { it.startsWith("nr_strong_signal") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_SIGNAL_ANOMALY, 0.85)
                }
                if (result.details.containsKey("nr_missing_neighbors")) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.8)
                }
                if (result.details.keys.any { it.contains("downgrade") || it.contains("fallback") }) {
                    accumulateWeightedMax(
                        indicators, counts,
                        ThreatScorer.KEY_NETWORK_INTEGRITY,
                        (result.score / 5.0).coerceAtMost(1.0)
                    )
                }
                if (result.details.keys.any { it.contains("oscillation") || it.contains("rapid_reselection") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TEMPORAL_PATTERN, 0.8)
                }
                if (result.details.keys.any { it.startsWith("nr_signal_jump") || it.startsWith("nr_uniform_signals") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_SIGNAL_ANOMALY, 0.75)
                }
            }

            ThreatType.REGISTRATION_FAILURE -> {
                // Registration / authentication failures from RegistrationFailureDetector.
                // Synch failure (authentication rejection) is near-certain fake tower.
                if (result.details.containsKey("synch_failure") ||
                    result.details.containsKey("auth_reject")) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_PROTOCOL_VIOLATION, 0.95)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_NETWORK_INTEGRITY, 0.9)
                } else {
                    // Generic registration failures (TAU reject, attach reject, etc.)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_PROTOCOL_VIOLATION, 0.6)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_NETWORK_INTEGRITY, 0.5)
                }
            }

            ThreatType.TEMPORAL_ANOMALY -> {
                // Temporal anomalies from TemporalAnomalyDetector.
                if (result.details.containsKey("transient_tower") ||
                    result.details.containsKey("transient_towers")) {
                    // Tower appeared and disappeared — consistent with portable IMSI catcher
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.85)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TEMPORAL_PATTERN, 0.9)
                }
                if (result.details.containsKey("cell_cycling_stationary") ||
                    result.details.containsKey("cell_cycling")) {
                    // Rapid cell changes while user is stationary
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TEMPORAL_PATTERN, 0.9)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_SIGNAL_ANOMALY, 0.5)
                }
                if (result.details.containsKey("signal_instability")) {
                    // SDR-quality oscillator instability
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_SIGNAL_ANOMALY, 0.8)
                }
            }

            ThreatType.KNOWN_TOWER_ANOMALY -> {
                // Behavioral anomaly on a KNOWN tower — the most sophisticated attack.
                // Band / frequency change → protocol violation (towers don't change bands)
                if (result.details.keys.any { it.startsWith("band_change_") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_PROTOCOL_VIOLATION, 0.9)
                }
                // Signal anomaly on known tower
                if (result.details.keys.any { it.startsWith("signal_anomaly_") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_SIGNAL_ANOMALY, 0.8)
                }
                // Geographic displacement
                if (result.details.keys.any { it.startsWith("geo_displacement_") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_SIGNAL_ANOMALY, 0.85)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.8)
                }
                // Duplicate CID with different PCI — definite clone (very high confidence)
                if (result.details.keys.any { it.startsWith("duplicate_cid_pci_") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.95)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_PROTOCOL_VIOLATION, 0.9)
                }
                // Duplicate CID (same PCI) — suspicious clone
                if (result.details.keys.any { it.startsWith("duplicate_cid_") && !it.startsWith("duplicate_cid_pci_") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.85)
                }
                // Neighbour list inconsistency
                if (result.details.keys.any { it.startsWith("neighbor_mismatch_") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.75)
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TEMPORAL_PATTERN, 0.6)
                }
                // TAC change — very high confidence (real towers don't change TAC)
                if (result.details.keys.any { it.startsWith("tac_change_") }) {
                    accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_TOWER_BEHAVIOR, 0.9)
                }
            }

            ThreatType.COMPOUND_PATTERN -> {
                // Compound patterns are scored at the fusion level and do not
                // feed back into per-category indicators to avoid circular boosting.
            }
        }
    }

    // -----------------------------------------------------------------
    // Weighted-Max accumulation
    // -----------------------------------------------------------------

    /**
     * Accumulate a category value using weighted-max:
     * - Base = 80% of the highest value seen for this category
     * - Bonus = 20% of the new value for each additional confirming detector
     * - Capped at 1.0
     *
     * This means: 1 detector at 0.8 = 0.8,
     * but 3 detectors at 0.8 = max(0.8) + 2 × (0.2 × 0.8) = 0.8 + 0.32 = 1.0 (capped)
     *
     * More precisely: result = max_value + (count - 1) * CONFIRMATION_BONUS * new_value
     * where CONFIRMATION_BONUS = 0.20.
     */
    private fun accumulateWeightedMax(
        map: MutableMap<String, Double>,
        counts: MutableMap<String, Int>,
        key: String,
        value: Double
    ) {
        val currentMax = map[key] ?: 0.0
        val currentCount = counts[key] ?: 0
        val newCount = currentCount + 1
        counts[key] = newCount

        if (value >= currentMax) {
            // New value is the new max; previous contributors become bonus
            val bonus = currentCount * CONFIRMATION_BONUS * currentMax
            map[key] = (value + bonus).coerceAtMost(1.0)
        } else {
            // Existing max stays; this is an additional confirming detector
            val bonus = CONFIRMATION_BONUS * value
            map[key] = (currentMax + bonus).coerceAtMost(1.0)
        }
    }

    // -----------------------------------------------------------------
    // Environmental Context
    // -----------------------------------------------------------------

    /**
     * Populate the environmental context category based on observable factors.
     *
     * - Urban area (many cells visible) + 2G serving = very suspicious
     * - Rural area (1-2 cells) + 2G serving = more normal
     * - Moving + cell changes = expected (reduce temporal concern)
     * - Stationary + cell changes = suspicious (boost temporal concern)
     */
    private fun computeEnvironmentalContext(
        cells: List<CellTower>,
        isMoving: Boolean?,
        speed: Double?,
        indicators: MutableMap<String, Double>,
        counts: MutableMap<String, Int>
    ) {
        if (cells.isEmpty()) return

        var envScore = 0.0

        // --- Urban density vs RAT analysis ---
        val visibleCellCount = cells.size
        val servingCell = cells.firstOrNull() // Typically the strongest / serving cell
        val isServing2G = servingCell?.networkType in setOf(NetworkType.GSM, NetworkType.CDMA)
        val isUrban = visibleCellCount >= 5 // 5+ visible cells suggests dense urban area

        if (isServing2G) {
            if (isUrban) {
                // Urban area with 2G serving — very suspicious. In dense areas carriers
                // maintain strong 4G/5G coverage; 2G serving likely means forced downgrade.
                envScore = maxOf(envScore, 0.8)
            } else if (visibleCellCount <= 2) {
                // Rural area with 2G — could be legitimate sparse coverage.
                // Slight negative adjustment: reduce temporal/tower scores if present.
                envScore = maxOf(envScore, 0.15)
            } else {
                // Suburban: moderate suspicion for 2G
                envScore = maxOf(envScore, 0.45)
            }
        }

        // --- Motion state adjustments ---
        if (isMoving != null) {
            val hasTemporalIndicators = (indicators[ThreatScorer.KEY_TEMPORAL_PATTERN] ?: 0.0) > 0.0
            val hasTowerChanges = (indicators[ThreatScorer.KEY_TOWER_BEHAVIOR] ?: 0.0) > 0.0

            if (isMoving) {
                // Moving: cell changes and tower behavior shifts are expected.
                // Reduce temporal pattern and tower behavior indicators.
                val movingReduction = when {
                    speed != null && speed > 10.0 -> 0.4  // Driving speed — significant reduction
                    speed != null && speed > 2.0  -> 0.2  // Walking speed — moderate reduction
                    else -> 0.15                           // Moving but unknown speed
                }
                if (hasTemporalIndicators) {
                    val current = indicators[ThreatScorer.KEY_TEMPORAL_PATTERN] ?: 0.0
                    indicators[ThreatScorer.KEY_TEMPORAL_PATTERN] =
                        (current - movingReduction).coerceAtLeast(0.0)
                }
                // Don't boost environmental score for motion — motion is normal
            } else {
                // Stationary: cell changes are suspicious.
                if (hasTemporalIndicators || hasTowerChanges) {
                    envScore = maxOf(envScore, 0.6)
                }
            }
        }

        if (envScore > 0.0) {
            accumulateWeightedMax(indicators, counts, ThreatScorer.KEY_ENVIRONMENTAL, envScore)
        }
    }

    // -----------------------------------------------------------------
    // Fusion: Compound Attack Pattern Detection
    // -----------------------------------------------------------------

    /**
     * Detect compound attack patterns from the combination of individual
     * detector results. Returns a list of matched patterns.
     *
     * These patterns are derived from documented real-world IMSI catcher
     * deployments and published detection research:
     *
     * - Classic Stingray: textbook IMSI catcher from FBI/DHS case studies
     * - Silent Interception: evil-twin tower spoofing a real cell
     * - Tracking-Only: identity harvesting without call interception
     * - Mobile IMSI Catcher: vehicle/drone-mounted transient device
     */
    private fun detectCompoundPatterns(
        results: List<DetectionResult>,
        cells: List<CellTower>,
        indicators: Map<String, Double>
    ): List<ThreatScorer.CompoundPattern> {
        val patterns = mutableListOf<ThreatScorer.CompoundPattern>()
        val resultsByType = results.groupBy { it.threatType }

        // --- Pattern 1: Classic Stingray ---
        // Unknown Cell ID + Strong signal + Network downgrade + Missing neighbors
        // This is the textbook signature from every documented case study.
        run classicStingray@{
            val matchedIndicators = mutableListOf<String>()
            var matchCount = 0

            val fakeBts = resultsByType[ThreatType.FAKE_BTS]?.firstOrNull()
            if (fakeBts != null) {
                if (fakeBts.details.keys.any { it.startsWith("unknown_cid") }) {
                    matchedIndicators.add("unknown_cell_id")
                    matchCount++
                }
                if (fakeBts.details.keys.any { it.startsWith("strong_signal") }) {
                    matchedIndicators.add("strong_signal")
                    matchCount++
                }
                if (fakeBts.details.containsKey("missing_neighbors")) {
                    matchedIndicators.add("missing_neighbors")
                    matchCount++
                }
            }

            val downgrade = resultsByType[ThreatType.NETWORK_DOWNGRADE]?.firstOrNull()
            if (downgrade != null) {
                matchedIndicators.add("network_downgrade")
                matchCount++
            }

            // Need at least 3 of 4 indicators for high confidence, all 4 for near-certainty
            if (matchCount >= 3) {
                val confidence = if (matchCount == 4) 0.95 else 0.80
                patterns.add(
                    ThreatScorer.CompoundPattern(
                        name = "Classic Stingray",
                        confidence = confidence,
                        description = "Textbook IMSI catcher signature: " +
                            "fake tower with strong signal forcing network downgrade. " +
                            "Matches documented FBI/DHS Stingray deployments.",
                        matchedIndicators = matchedIndicators
                    )
                )
            }
        }

        // --- Pattern 2: Silent Interception ---
        // Known Cell ID (spoofing a real tower) + Signal slightly stronger than expected + Cipher downgrade
        // The attacker mimics a real tower — harder to detect than classic Stingray.
        run silentInterception@{
            val matchedIndicators = mutableListOf<String>()
            var matchCount = 0

            val fakeBts = resultsByType[ThreatType.FAKE_BTS]?.firstOrNull()
            // Silent interception uses a KNOWN cell ID (evil twin) — so no unknown_cid,
            // but there may be signal anomalies or other FakeBts indicators
            val hasSignalAnomaly = (indicators[ThreatScorer.KEY_SIGNAL_ANOMALY] ?: 0.0) > 0.4
            if (hasSignalAnomaly) {
                matchedIndicators.add("signal_anomaly")
                matchCount++
            }

            val cipher = resultsByType[ThreatType.CIPHER_ANOMALY]?.firstOrNull()
            if (cipher != null) {
                matchedIndicators.add("cipher_downgrade")
                matchCount++
            }

            // Check for registration failures (auth issues expected with evil twin)
            val regFailure = resultsByType[ThreatType.REGISTRATION_FAILURE]?.firstOrNull()
            if (regFailure != null) {
                matchedIndicators.add("registration_failure")
                matchCount++
            }

            // Check for network integrity issues without a full RAT downgrade
            val hasNetworkIssues = (indicators[ThreatScorer.KEY_NETWORK_INTEGRITY] ?: 0.0) > 0.5
            val noRatDowngrade = resultsByType[ThreatType.NETWORK_DOWNGRADE] == null
            if (hasNetworkIssues && noRatDowngrade) {
                matchedIndicators.add("network_integrity_degraded")
                matchCount++
            }

            // Need cipher anomaly + at least one other for this pattern
            if (cipher != null && matchCount >= 2) {
                val confidence = when {
                    matchCount >= 4 -> 0.90
                    matchCount >= 3 -> 0.75
                    else -> 0.60
                }
                patterns.add(
                    ThreatScorer.CompoundPattern(
                        name = "Silent Interception",
                        confidence = confidence,
                        description = "Evil-twin attack: tower spoofing a real cell with " +
                            "cipher downgrade for silent interception. More subtle than " +
                            "classic Stingray — attacker mimics legitimate infrastructure.",
                        matchedIndicators = matchedIndicators
                    )
                )
            }
        }

        // --- Pattern 3: Tracking-Only ---
        // Repeated registration failures (IMSI requests) + No cipher downgrade
        // The attacker just wants to know who is nearby, not intercept calls.
        run trackingOnly@{
            val matchedIndicators = mutableListOf<String>()
            var matchCount = 0

            val regFailure = resultsByType[ThreatType.REGISTRATION_FAILURE]?.firstOrNull()
            if (regFailure != null) {
                matchedIndicators.add("registration_failures")
                matchCount++
            }

            val tracking = resultsByType[ThreatType.TRACKING_PATTERN]?.firstOrNull()
            if (tracking != null) {
                matchedIndicators.add("tracking_pattern")
                matchCount++
            }

            // Key characteristic: NO cipher anomaly (not intercepting, just harvesting identities)
            val noCipherIssue = resultsByType[ThreatType.CIPHER_ANOMALY] == null
            val noNetworkDowngrade = resultsByType[ThreatType.NETWORK_DOWNGRADE] == null

            if (matchCount >= 1 && noCipherIssue && noNetworkDowngrade) {
                // Must have either reg failure or tracking pattern
                val hasProtocolIndicator = (indicators[ThreatScorer.KEY_PROTOCOL_VIOLATION] ?: 0.0) > 0.3
                if (hasProtocolIndicator || matchCount >= 2) {
                    val confidence = when {
                        matchCount >= 2 -> 0.75
                        regFailure != null && regFailure.confidence == Confidence.HIGH -> 0.70
                        else -> 0.55
                    }
                    patterns.add(
                        ThreatScorer.CompoundPattern(
                            name = "Tracking-Only",
                            confidence = confidence,
                            description = "Identity harvesting without call interception. " +
                                "Repeated IMSI requests via forced registration failures " +
                                "but no encryption downgrade. Consistent with location " +
                                "tracking / mass surveillance deployment.",
                            matchedIndicators = matchedIndicators
                        )
                    )
                }
            }
        }

        // --- Pattern 4: Mobile IMSI Catcher ---
        // Tower appears and disappears + Signal moves (strength changes at fixed location)
        // Consistent with a vehicle-mounted or drone-based device.
        run mobileImsiCatcher@{
            val matchedIndicators = mutableListOf<String>()
            var matchCount = 0

            // Check for transient tower indicators
            val temporal = resultsByType[ThreatType.TEMPORAL_ANOMALY]?.firstOrNull()
            if (temporal != null &&
                (temporal.details.containsKey("transient_tower") ||
                 temporal.details.containsKey("transient_towers"))) {
                matchedIndicators.add("transient_tower")
                matchCount++
            }

            // Tracking pattern with short-duration cells
            val tracking = resultsByType[ThreatType.TRACKING_PATTERN]?.firstOrNull()
            if (tracking != null && tracking.details.containsKey("short_duration")) {
                matchedIndicators.add("short_duration_cell")
                matchCount++
            }

            // Signal anomaly (strength changes over time at same location)
            val signalAnomaly = resultsByType[ThreatType.SIGNAL_ANOMALY]?.firstOrNull()
            if (signalAnomaly != null) {
                matchedIndicators.add("signal_deviation")
                matchCount++
            }

            // FakeBTS with unknown CID appearing recently
            val fakeBts = resultsByType[ThreatType.FAKE_BTS]?.firstOrNull()
            if (fakeBts != null) {
                // New unknown tower is suspicious for mobile device
                if (fakeBts.details.keys.any { it.startsWith("unknown_cid") }) {
                    matchedIndicators.add("unknown_tower")
                    matchCount++
                }
            }

            if (matchCount >= 2 && matchedIndicators.contains("transient_tower")) {
                val confidence = when {
                    matchCount >= 4 -> 0.90
                    matchCount >= 3 -> 0.80
                    else -> 0.65
                }
                patterns.add(
                    ThreatScorer.CompoundPattern(
                        name = "Mobile IMSI Catcher",
                        confidence = confidence,
                        description = "Transient cell tower with shifting signal characteristics. " +
                            "Consistent with a vehicle-mounted (Stingray van) or " +
                            "drone-based (LEER-3 / Dirtbox) IMSI catcher moving " +
                            "through the area.",
                        matchedIndicators = matchedIndicators
                    )
                )
            }
        }

        return patterns
    }

    // -----------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------

    companion object {
        /**
         * Bonus multiplier for each additional confirming detector per category.
         * With CONFIRMATION_BONUS = 0.20:
         *   1 detector at 0.8 → score = 0.80
         *   2 detectors at 0.8 → score = 0.80 + 0.20 × 0.80 = 0.96
         *   3 detectors at 0.8 → capped at 1.0
         */
        private const val CONFIRMATION_BONUS = 0.20
    }
}
