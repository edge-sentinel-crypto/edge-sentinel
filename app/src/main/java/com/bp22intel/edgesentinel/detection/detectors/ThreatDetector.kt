/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.detectors

import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.ThreatType

/**
 * Common interface for all threat detection modules.
 *
 * Each detector focuses on a specific class of cellular threat and produces
 * a [DetectionResult] when indicators are found, or null when nothing is detected.
 */
interface ThreatDetector {
    /** The category of threat this detector looks for. */
    val type: ThreatType

    /**
     * Analyze current cell towers against historical data for threat indicators.
     *
     * @param cells Currently visible cell towers.
     * @param history Previously observed cell towers for baseline comparison.
     * @return A [DetectionResult] if indicators are found, or null if clean.
     */
    suspend fun analyze(cells: List<CellTower>, history: List<CellTower>): DetectionResult?
}
