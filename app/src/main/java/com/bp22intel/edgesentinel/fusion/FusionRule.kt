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

import com.bp22intel.edgesentinel.domain.model.FusedThreatLevel
import com.bp22intel.edgesentinel.domain.model.SensorCategory

data class TriggerCondition(
    val sensorCategory: SensorCategory,
    val detectionType: String
)

data class FusionRule(
    val id: String,
    val name: String,
    val triggerConditions: List<TriggerCondition>,
    val timeWindowMs: Long = 5 * 60 * 1000L,
    val requireSameLocation: Boolean = false,
    val resultingThreatLevel: FusedThreatLevel,
    val confidenceBoost: Double = 0.0,
    val narrativeTemplate: String
) {
    fun matches(activeDetections: List<ActiveDetection>): Boolean {
        return triggerConditions.all { condition ->
            activeDetections.any { detection ->
                detection.sensorCategory == condition.sensorCategory &&
                    (condition.detectionType == "*" ||
                        detection.detectionType == condition.detectionType)
            }
        }
    }
}

data class ActiveDetection(
    val sensorCategory: SensorCategory,
    val detectionType: String,
    val description: String,
    val score: Double,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null
)
