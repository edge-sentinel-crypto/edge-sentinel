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

import com.bp22intel.edgesentinel.domain.model.FusedThreatAssessment
import com.bp22intel.edgesentinel.domain.model.FusedThreatLevel
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import com.bp22intel.edgesentinel.domain.model.SensorCategoryScore
import com.bp22intel.edgesentinel.domain.model.ThreatTrend
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class DashboardPosture(
    val level: FusedThreatLevel,
    val levelLabel: String,
    val categoryBreakdown: List<SensorCategoryScore>,
    val trend: ThreatTrend,
    val trendDescription: String,
    val timeSinceLastDetection: String,
    val activeThreatCount: Int,
    val briefSummary: String,
    val score: Double = 0.0,
    val scoreExplanation: String = ""
)

@Singleton
class OverallThreatDashboard @Inject constructor(
    private val fusionEngine: SensorFusionEngine,
    private val threatNarrator: ThreatNarrator
) {
    val assessmentFlow: StateFlow<FusedThreatAssessment>
        get() = fusionEngine.currentAssessment

    fun computePosture(assessment: FusedThreatAssessment): DashboardPosture {
        val trendDescription = when (assessment.trend) {
            ThreatTrend.IMPROVING -> "Threat level decreasing over the last hour"
            ThreatTrend.STABLE -> "Threat level stable"
            ThreatTrend.WORSENING -> "Threat level increasing over the last hour"
        }

        val timeSinceStr = assessment.timeSinceLastSignificantDetection?.let {
            formatDuration(it)
        } ?: "No recent detections"

        val briefSummary = threatNarrator.generateBriefSummary(
            matchedRules = emptyList(),
            signals = assessment.contributingSignals,
            overallLevel = assessment.overallLevel
        )

        // Compute 0–10 threat score from category scores and active threat count
        val maxCatScore = assessment.categoryScores.maxOfOrNull { it.score } ?: 0.0
        val avgCatScore = assessment.categoryScores
            .filter { it.score > 0 }
            .takeIf { it.isNotEmpty() }
            ?.map { it.score }?.average() ?: 0.0
        val threatCountFactor = (assessment.activeThreatCount.coerceAtMost(10) / 10.0) * 0.2
        // Blend: 50% max category + 30% avg active categories + 20% threat count
        val score = ((maxCatScore * 0.5 + avgCatScore * 0.3 + threatCountFactor) * 10.0)
            .coerceIn(0.0, 10.0)

        // Derive level FROM the score so they're always consistent
        val derivedLevel = when {
            score >= 7.0 -> FusedThreatLevel.CRITICAL
            score >= 4.5 -> FusedThreatLevel.DANGEROUS
            score >= 2.0 -> FusedThreatLevel.ELEVATED
            else -> FusedThreatLevel.CLEAR
        }

        val scoreExplanation = when {
            score < 1.0 -> "All sensors nominal. No threats detected."
            score < 3.0 -> "Minor anomalies detected. Monitoring."
            score < 5.0 -> "Suspicious signals from one or more sensors."
            score < 7.0 -> "Multiple threat indicators active across sensors."
            else -> "Critical threat indicators. Immediate attention recommended."
        }

        return DashboardPosture(
            level = derivedLevel,
            levelLabel = derivedLevel.label,
            categoryBreakdown = assessment.categoryScores,
            trend = assessment.trend,
            trendDescription = trendDescription,
            timeSinceLastDetection = timeSinceStr,
            activeThreatCount = assessment.activeThreatCount,
            briefSummary = briefSummary,
            score = score,
            scoreExplanation = scoreExplanation
        )
    }

    fun getCategoryStatus(
        category: SensorCategory,
        assessment: FusedThreatAssessment
    ): SensorCategoryScore {
        return assessment.categoryScores.find { it.category == category }
            ?: SensorCategoryScore(category, 0.0, 0, null)
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }
}
