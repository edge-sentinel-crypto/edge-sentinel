/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.fusion

import com.bp22intel.edgesentinel.domain.model.ContributingSignal
import com.bp22intel.edgesentinel.domain.model.FusedThreatAssessment
import com.bp22intel.edgesentinel.domain.model.FusedThreatLevel
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import com.bp22intel.edgesentinel.domain.model.SensorCategoryScore
import com.bp22intel.edgesentinel.domain.model.ThreatTrend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorFusionEngine @Inject constructor(
    private val threatNarrator: ThreatNarrator
) {
    private val _currentAssessment = MutableStateFlow(FusedThreatAssessment.clear())
    val currentAssessment: StateFlow<FusedThreatAssessment> = _currentAssessment.asStateFlow()

    private val activeDetections = mutableListOf<ActiveDetection>()
    private val assessmentHistory = mutableListOf<FusedThreatAssessment>()

    var correlationWindowMs: Long = 5 * 60 * 1000L
    private var baselineAnomalyScore: Double = 0.0
    private var peerCorroborationCount: Int = 0

    @Synchronized
    fun ingestDetection(detection: ActiveDetection) {
        pruneExpiredDetections()
        activeDetections.add(detection)
        recomputeAssessment()
    }

    @Synchronized
    fun ingestDetections(detections: List<ActiveDetection>) {
        pruneExpiredDetections()
        activeDetections.addAll(detections)
        recomputeAssessment()
    }

    @Synchronized
    fun updateBaselineAnomalyScore(score: Double) {
        baselineAnomalyScore = score.coerceIn(0.0, 1.0)
        recomputeAssessment()
    }

    @Synchronized
    fun updatePeerCorroboration(peerCount: Int) {
        peerCorroborationCount = peerCount.coerceAtLeast(0)
        recomputeAssessment()
    }

    /**
     * Dismiss (remove) active detections matching [detectionType].
     * Called when a user marks an alert as known-device or false-positive.
     */
    @Synchronized
    fun dismissDetection(detectionType: String) {
        activeDetections.removeAll { it.detectionType == detectionType }
        recomputeAssessment()
    }

    /**
     * Force a full recalculation of the current assessment.
     * Useful after external state changes (e.g., alert feedback).
     */
    @Synchronized
    fun recalculate() {
        pruneExpiredDetections()
        recomputeAssessment()
    }

    /**
     * Full reset: clears all active detections and recomputes.
     * Used after trust actions (Known Device/Network) to ensure
     * stale detections from before the trust don't linger.
     * The next scan cycle will re-populate with only untrusted detections.
     */
    @Synchronized
    fun recalculateClean() {
        activeDetections.clear()
        recomputeAssessment()
    }

    @Synchronized
    fun clearAll() {
        activeDetections.clear()
        baselineAnomalyScore = 0.0
        peerCorroborationCount = 0
        _currentAssessment.value = FusedThreatAssessment.clear()
    }

    private fun pruneExpiredDetections() {
        val cutoff = System.currentTimeMillis() - correlationWindowMs
        activeDetections.removeAll { it.timestamp < cutoff }
    }

    private fun recomputeAssessment() {
        val now = System.currentTimeMillis()
        pruneExpiredDetections()

        if (activeDetections.isEmpty() && baselineAnomalyScore <= 0.3) {
            val assessment = FusedThreatAssessment.clear()
            recordAndEmit(assessment)
            return
        }

        // Temporal correlation: group detections within the correlation window
        val temporallyCorrelated = activeDetections.filter {
            now - it.timestamp <= correlationWindowMs
        }

        // Spatial correlation: cluster detections at same location
        val spatialGroups = groupByLocation(temporallyCorrelated)

        // Run rule engine
        val rules = FusionRuleSet.getAllRules()
        val matchedRules = mutableListOf<FusionRule>()

        for (rule in rules) {
            val detectionsToCheck = if (rule.requireSameLocation) {
                // Check each spatial group separately
                spatialGroups.values.any { group -> rule.matches(group) }
            } else {
                rule.matches(temporallyCorrelated)
            }

            if (detectionsToCheck is Boolean && detectionsToCheck) {
                matchedRules.add(rule)
            } else if (detectionsToCheck is Boolean) {
                // Not matched, skip
            }
        }

        // Compute overall threat level from matched rules
        var overallLevel = matchedRules
            .maxByOrNull { it.resultingThreatLevel.ordinal_rank }
            ?.resultingThreatLevel
            ?: if (temporallyCorrelated.isNotEmpty()) FusedThreatLevel.ELEVATED
            else FusedThreatLevel.CLEAR

        // Compute base confidence from matched rules and signal quality
        var confidence = computeBaseConfidence(matchedRules, temporallyCorrelated)

        // Baseline anomaly boost: if > 0.7 and any detection exists, boost confidence by 50%
        if (baselineAnomalyScore > 0.7 && temporallyCorrelated.isNotEmpty()) {
            confidence = (confidence * 1.5).coerceAtMost(1.0)
        }

        // Mesh peer corroboration boost: confidence * (peer_count * 0.3)
        if (peerCorroborationCount > 0) {
            val peerBoost = 1.0 + (peerCorroborationCount * 0.3)
            confidence = (confidence * peerBoost).coerceAtMost(1.0)
        }

        // If high baseline anomaly with no specific detections, at least ELEVATED
        if (baselineAnomalyScore > 0.7 && overallLevel == FusedThreatLevel.CLEAR) {
            overallLevel = FusedThreatLevel.ELEVATED
        }

        // Build contributing signals
        val contributingSignals = temporallyCorrelated.map { detection ->
            ContributingSignal(
                category = detection.sensorCategory,
                detectionType = detection.detectionType,
                description = detection.description,
                score = detection.score,
                timestamp = detection.timestamp
            )
        }

        // Compute per-category scores
        val categoryScores = computeCategoryScores(temporallyCorrelated)

        // Compute trend from history
        val trend = computeTrend()

        // Generate narrative
        val narrative = threatNarrator.generateNarrative(
            matchedRules = matchedRules,
            signals = contributingSignals,
            overallLevel = overallLevel,
            baselineAnomalyScore = baselineAnomalyScore,
            peerCorroborationCount = peerCorroborationCount
        )

        // Time since last significant detection
        val lastSignificant = temporallyCorrelated
            .filter { it.score > 0.5 }
            .maxByOrNull { it.timestamp }
            ?.timestamp

        val assessment = FusedThreatAssessment(
            overallLevel = overallLevel,
            contributingSignals = contributingSignals,
            confidence = confidence,
            narrative = narrative,
            timestamp = now,
            trend = trend,
            categoryScores = categoryScores,
            activeThreatCount = temporallyCorrelated.size,
            timeSinceLastSignificantDetection = lastSignificant?.let { now - it }
        )

        recordAndEmit(assessment)
    }

    private fun computeBaseConfidence(
        matchedRules: List<FusionRule>,
        detections: List<ActiveDetection>
    ): Double {
        if (matchedRules.isEmpty() && detections.isEmpty()) return 0.0

        // Start with average detection score
        val avgScore = if (detections.isNotEmpty()) {
            detections.map { it.score }.average()
        } else 0.3

        // Add confidence boosts from matched rules
        val ruleBoost = matchedRules.sumOf { it.confidenceBoost }

        // Multi-sensor correlation increases confidence
        val sensorDiversity = detections.map { it.sensorCategory }.distinct().size
        val diversityBoost = (sensorDiversity - 1) * 0.1

        return (avgScore + ruleBoost + diversityBoost).coerceIn(0.0, 1.0)
    }

    private fun computeCategoryScores(
        detections: List<ActiveDetection>
    ): List<SensorCategoryScore> {
        return SensorCategory.entries.map { category ->
            val categoryDetections = detections.filter { it.sensorCategory == category }
            SensorCategoryScore(
                category = category,
                score = if (categoryDetections.isNotEmpty()) {
                    categoryDetections.maxOf { it.score }
                } else if (category == SensorCategory.BASELINE) {
                    baselineAnomalyScore
                } else 0.0,
                activeThreatCount = categoryDetections.size,
                latestDetection = categoryDetections
                    .maxByOrNull { it.timestamp }
                    ?.description
            )
        }
    }

    private fun computeTrend(): ThreatTrend {
        if (assessmentHistory.size < 2) return ThreatTrend.STABLE

        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000L
        val recentHistory = assessmentHistory.filter { it.timestamp > oneHourAgo }
        if (recentHistory.size < 2) return ThreatTrend.STABLE

        val halfPoint = recentHistory.size / 2
        val olderHalf = recentHistory.subList(0, halfPoint)
        val newerHalf = recentHistory.subList(halfPoint, recentHistory.size)

        val olderAvg = olderHalf.map { it.overallLevel.ordinal_rank }.average()
        val newerAvg = newerHalf.map { it.overallLevel.ordinal_rank }.average()

        return when {
            newerAvg > olderAvg + 0.3 -> ThreatTrend.WORSENING
            newerAvg < olderAvg - 0.3 -> ThreatTrend.IMPROVING
            else -> ThreatTrend.STABLE
        }
    }

    private fun groupByLocation(
        detections: List<ActiveDetection>
    ): Map<String, List<ActiveDetection>> {
        val locationPrecision = 0.001 // ~111m at equator
        return detections.groupBy { detection ->
            val lat = detection.latitude
            val lon = detection.longitude
            if (lat != null && lon != null) {
                val roundedLat = (lat / locationPrecision).toLong()
                val roundedLon = (lon / locationPrecision).toLong()
                "$roundedLat,$roundedLon"
            } else {
                "unknown"
            }
        }
    }

    private fun recordAndEmit(assessment: FusedThreatAssessment) {
        assessmentHistory.add(assessment)
        // Keep last hour of history
        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000L
        assessmentHistory.removeAll { it.timestamp < oneHourAgo }
        _currentAssessment.value = assessment
    }
}
