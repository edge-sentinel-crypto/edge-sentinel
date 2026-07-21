/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.utils

import androidx.compose.ui.graphics.Color
import com.bp22intel.edgesentinel.domain.model.FusedThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusCritical
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.StatusElevated
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat

/**
 * Shared colour mapping for threat severity levels.
 *
 * Replaces private copies in AlertDetailScreen (severityColor) and
 * DashboardScreen (fusedLevelColor).
 */
object SeverityColorUtils {

    /** Colour for the three-tier [ThreatLevel] (CLEAR / SUSPICIOUS / THREAT). */
    fun severityColor(level: ThreatLevel): Color {
        return when (level) {
            ThreatLevel.CLEAR -> StatusClear
            ThreatLevel.SUSPICIOUS -> StatusSuspicious
            ThreatLevel.THREAT -> StatusThreat
        }
    }

    /** Colour for the four-tier [FusedThreatLevel] (CLEAR / ELEVATED / DANGEROUS / CRITICAL). */
    fun fusedLevelColor(level: FusedThreatLevel): Color {
        return when (level) {
            FusedThreatLevel.CLEAR -> StatusClear
            FusedThreatLevel.ELEVATED -> StatusElevated
            FusedThreatLevel.DANGEROUS -> StatusDangerous
            FusedThreatLevel.CRITICAL -> StatusCritical
        }
    }
}
