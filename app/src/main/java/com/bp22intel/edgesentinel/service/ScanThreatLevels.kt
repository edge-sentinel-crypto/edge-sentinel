/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bp22intel.edgesentinel.detection.scoring.ThreatScorer
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import kotlinx.coroutines.flow.first

/**
 * Shared threat-level mapping for [MonitoringService] and [ScanWorker].
 *
 * Detector scores are on the 0-10 scale. Levels use the [ThreatScorer]
 * thresholds (SUSPICIOUS 3.0, THREAT 6.0) scaled by the user's
 * [DetectionSensitivity] multiplier. The old 0.4/0.7 cutoffs are gone.
 */
object ScanThreatLevels {

    private val KEY_DETECTION_SENSITIVITY = stringPreferencesKey("detection_sensitivity")

    /** Reads `detection_sensitivity` from the settings DataStore. Defaults to MEDIUM. */
    suspend fun readSensitivity(dataStore: DataStore<Preferences>): DetectionSensitivity {
        return try {
            val name = dataStore.data.first()[KEY_DETECTION_SENSITIVITY]
            if (name == null) DetectionSensitivity.MEDIUM else DetectionSensitivity.valueOf(name)
        } catch (_: Exception) {
            DetectionSensitivity.MEDIUM
        }
    }

    /**
     * N7: overall level for a scan. Single alerts and the overall level use ONE
     * scale: ThreatScorer's 0-10 thresholds (SUSPICIOUS 3.0, THREAT 6.0) x the
     * sensitivity multiplier ([alertSeverity]). The overall level is the higher
     * of the composite scorer level and the most severe single alert, so an
     * alert at THREAT can never coexist with an overall CLEAR.
     */
    fun overallLevel(
        result: ThreatScorer.ScoringResult?,
        detections: List<com.bp22intel.edgesentinel.domain.model.DetectionResult>,
        sensitivity: DetectionSensitivity
    ): ThreatLevel {
        val maxAlert = detections.map { alertSeverity(it.score, sensitivity) }
            .maxByOrNull { it.ordinal } ?: ThreatLevel.CLEAR
        val composite = overallLevel(result)
        return if (maxAlert.ordinal > composite.ordinal) maxAlert else composite
    }

    /** Composite level from a ThreatScorer result (null = nothing detected = CLEAR). */
    fun overallLevel(result: ThreatScorer.ScoringResult?): ThreatLevel =
        result?.threatLevel ?: ThreatLevel.CLEAR

    /** Per-alert severity for a single detector score on the 0-10 scale. */
    fun alertSeverity(score: Double, sensitivity: DetectionSensitivity): ThreatLevel {
        val m = sensitivity.thresholdMultiplier
        return when {
            score >= ThreatScorer.THREAT_THRESHOLD * m -> ThreatLevel.THREAT
            score >= ThreatScorer.SUSPICIOUS_THRESHOLD * m -> ThreatLevel.SUSPICIOUS
            else -> ThreatLevel.CLEAR
        }
    }
}
