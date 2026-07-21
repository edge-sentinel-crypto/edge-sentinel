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
import com.bp22intel.edgesentinel.domain.model.FusedThreatLevel
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThreatNarrator @Inject constructor() {

    fun generateNarrative(
        matchedRules: List<FusionRule>,
        signals: List<ContributingSignal>,
        overallLevel: FusedThreatLevel,
        baselineAnomalyScore: Double,
        peerCorroborationCount: Int
    ): String {
        if (matchedRules.isEmpty() && signals.isEmpty()) {
            return "**All Clear** — All sensors are nominal. No threats detected across cellular, WiFi, Bluetooth, or network layers."
        }

        val sections = mutableListOf<String>()

        // Primary threat narrative from the highest-severity matched rule
        val primaryRule = matchedRules.maxByOrNull { it.resultingThreatLevel.ordinal_rank }
        if (primaryRule != null) {
            val signalSummary = signals
                .filter { signal ->
                    primaryRule.triggerConditions.any { it.sensorCategory == signal.category }
                }
                .joinToString(", ") { it.description }
            val narrative = primaryRule.narrativeTemplate.replace("{signals}", signalSummary)
            sections.add("**${primaryRule.name}**\n\n$narrative")
        }

        // Additional context from other matched rules
        matchedRules
            .filter { it != primaryRule }
            .sortedByDescending { it.resultingThreatLevel.ordinal_rank }
            .forEach { rule ->
                val signalSummary = signals
                    .filter { signal ->
                        rule.triggerConditions.any { it.sensorCategory == signal.category }
                    }
                    .joinToString(", ") { it.description }
                val narrative = rule.narrativeTemplate.replace("{signals}", signalSummary)
                sections.add("**${rule.name}**\n\n$narrative")
            }

        // Baseline anomaly context
        if (baselineAnomalyScore > 0.7) {
            sections.add(
                "**Baseline Anomaly Alert**\n\n" +
                    "Environmental baseline deviation is ${(baselineAnomalyScore * 100).toInt()}%. " +
                    "Your current RF environment significantly deviates from established patterns. " +
                    "This increases confidence in the above detections."
            )
        }

        // Mesh peer corroboration
        if (peerCorroborationCount > 0) {
            val peerText = if (peerCorroborationCount == 1) "1 mesh peer" else "$peerCorroborationCount mesh peers"
            sections.add(
                "**Mesh Corroboration**\n\n" +
                    "$peerText independently detected similar threats in your area, " +
                    "increasing confidence in this assessment."
            )
        }

        // Actionable recommendations based on level
        sections.add(generateRecommendations(overallLevel, signals))

        return sections.joinToString("\n\n---\n\n")
    }

    fun generateBriefSummary(
        matchedRules: List<FusionRule>,
        signals: List<ContributingSignal>,
        overallLevel: FusedThreatLevel
    ): String {
        if (matchedRules.isEmpty()) {
            return when {
                signals.isEmpty() -> "All sensors clear"
                signals.size == 1 -> signals.first().description
                else -> "${signals.size} detections across ${
                    signals.map { it.category }.distinct().size
                } sensors"
            }
        }

        val primary = matchedRules.maxByOrNull { it.resultingThreatLevel.ordinal_rank }
            ?: return "${signals.size} active detections"

        return primary.name
    }

    private fun generateRecommendations(
        level: FusedThreatLevel,
        signals: List<ContributingSignal>
    ): String {
        val categories = signals.map { it.category }.distinct()
        val recs = mutableListOf<String>()

        recs.add("**Recommended Actions**\n")

        when (level) {
            FusedThreatLevel.CRITICAL -> {
                recs.add("- **Immediately** cease sensitive communications on this device")
                recs.add("- Switch to a known-secure device if available")
                if (SensorCategory.NETWORK in categories || SensorCategory.WIFI in categories) {
                    recs.add("- Disconnect from WiFi and use cellular data only")
                }
                if (SensorCategory.CELLULAR in categories) {
                    recs.add("- Enable airplane mode if not actively needed")
                    recs.add("- Move to a different physical location")
                }
                recs.add("- Document the alert for security review")
            }
            FusedThreatLevel.DANGEROUS -> {
                recs.add("- Avoid transmitting sensitive information")
                if (SensorCategory.WIFI in categories) {
                    recs.add("- Disconnect from WiFi networks")
                }
                if (SensorCategory.NETWORK in categories) {
                    recs.add("- Verify VPN connection and switch to cellular data")
                }
                recs.add("- Increase monitoring frequency")
                recs.add("- Consider changing your physical location")
            }
            FusedThreatLevel.ELEVATED -> {
                recs.add("- Monitor for escalation — additional detections will raise severity")
                if (SensorCategory.BLUETOOTH in categories) {
                    recs.add("- Check surroundings for unknown tracking devices")
                }
                if (SensorCategory.CELLULAR in categories) {
                    recs.add("- Verify your network connection is as expected")
                }
                recs.add("- Keep Edge Sentinel monitoring active")
            }
            FusedThreatLevel.CLEAR -> {
                recs.add("- Continue normal operations")
                recs.add("- Keep monitoring active for early threat detection")
            }
        }

        return recs.joinToString("\n")
    }
}
