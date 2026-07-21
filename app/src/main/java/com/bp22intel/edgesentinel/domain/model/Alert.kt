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

data class Alert(
    val id: Long = 0,
    val timestamp: Long,
    val threatType: ThreatType,
    val severity: ThreatLevel,
    val confidence: Confidence,
    val summary: String,
    val detailsJson: String,
    val cellId: Long?,
    val acknowledged: Boolean = false
)
