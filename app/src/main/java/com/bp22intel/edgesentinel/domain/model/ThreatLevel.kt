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

enum class ThreatLevel(val color: Long, val label: String) {
    CLEAR(0xFF4CAF50, "Clear"),
    SUSPICIOUS(0xFFFF9800, "Suspicious"),
    THREAT(0xFFF44336, "Threat")
}
