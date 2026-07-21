/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.model

enum class FusedThreatLevel(val label: String, val ordinal_rank: Int) {
    CLEAR("Clear", 0),
    ELEVATED("Elevated", 1),
    DANGEROUS("Dangerous", 2),
    CRITICAL("Critical", 3)
}

enum class ThreatTrend(val label: String, val arrow: String) {
    IMPROVING("Improving", "↓"),
    STABLE("Stable", "→"),
    WORSENING("Worsening", "↑")
}

data class ContributingSignal(
    val category: SensorCategory,
    val detectionType: String,
    val description: String,
    val score: Double,
    val timestamp: Long
)

data class SensorCategoryScore(
    val category: SensorCategory,
    val score: Double,
    val activeThreatCount: Int,
    val latestDetection: String?
)

data class FusedThreatAssessment(
    val overallLevel: FusedThreatLevel,
    val contributingSignals: List<ContributingSignal>,
    val confidence: Double,
    val narrative: String,
    val timestamp: Long,
    val trend: ThreatTrend,
    val categoryScores: List<SensorCategoryScore>,
    val activeThreatCount: Int,
    val timeSinceLastSignificantDetection: Long?
) {
    companion object {
        fun clear() = FusedThreatAssessment(
            overallLevel = FusedThreatLevel.CLEAR,
            contributingSignals = emptyList(),
            confidence = 1.0,
            narrative = "All sensors nominal. No threats detected.",
            timestamp = System.currentTimeMillis(),
            trend = ThreatTrend.STABLE,
            categoryScores = SensorCategory.entries.map {
                SensorCategoryScore(it, 0.0, 0, null)
            },
            activeThreatCount = 0,
            timeSinceLastSignificantDetection = null
        )
    }
}
