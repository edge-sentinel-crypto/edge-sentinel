/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.analysis

/**
 * Risk levels for analyst assessments, independent of the raw detection [ThreatLevel].
 *
 * Raw detectors flag CLEAR / SUSPICIOUS / THREAT based on signal patterns.
 * The analyst engine re-evaluates those flags with additional context (motion,
 * signal strength, network type, co-occurring alerts) and assigns a four-tier
 * risk level that is easier for end-users to act on.
 */
enum class RiskLevel(
    val label: String,
    val color: Long
) {
    /** Routine — no action required. */
    LOW("Low", 0xFF4CAF50),

    /** Worth watching — not yet actionable but stay alert. */
    MEDIUM("Medium", 0xFFFF9800),

    /** Significant concern — take precautions now. */
    HIGH("High", 0xFFF44336),

    /** Active threat — immediate protective action recommended. */
    CRITICAL("Critical", 0xFF9C27B0)
}

/**
 * Analysis of a single [Alert] produced by the [ThreatAnalyst].
 *
 * @property plainEnglish  1-2 sentence explanation a non-technical person can understand.
 * @property riskLevel     Contextual risk assessment (may differ from the raw severity).
 * @property recommendation Concrete, actionable next step.
 * @property confidence    Analyst confidence in this assessment (0.0 – 1.0).
 * @property possibleCauses Ranked list of likely explanations, most probable first.
 * @property shouldWorry   Bottom-line gut-check: should the user actually be concerned?
 */
data class AlertAnalysis(
    val plainEnglish: String,
    val riskLevel: RiskLevel,
    val recommendation: String,
    val confidence: Float,
    val possibleCauses: List<String>,
    val shouldWorry: Boolean
)

/**
 * Holistic situation brief synthesising multiple alerts and environmental context.
 *
 * @property summary         Executive-style overview of the current threat picture.
 * @property overallRisk     Highest contextual risk across all active alerts.
 * @property topConcerns     The most important issues, plain English, ordered by severity.
 * @property recommendations Prioritised list of practical steps the user should take.
 * @property allClear        True when no alerts warrant concern — safe to carry on normally.
 */
data class SituationBrief(
    val summary: String,
    val overallRisk: RiskLevel,
    val topConcerns: List<String>,
    val recommendations: List<String>,
    val allClear: Boolean,
    /** Cooperative localization context added when mesh peers contribute. */
    val cooperativeContext: String? = null
)
