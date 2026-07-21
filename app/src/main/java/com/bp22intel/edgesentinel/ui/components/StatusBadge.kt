/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.EdgeSentinelTheme
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat

@Composable
fun StatusBadge(
    text: String,
    threatLevel: ThreatLevel,
    modifier: Modifier = Modifier
) {
    val bgColor = when (threatLevel) {
        ThreatLevel.CLEAR -> StatusClear
        ThreatLevel.SUSPICIOUS -> StatusSuspicious
        ThreatLevel.THREAT -> StatusThreat
    }

    Text(
        text = text,
        color = BackgroundPrimary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun StatusBadgePreview() {
    EdgeSentinelTheme {
        StatusBadge(text = "THREAT", threatLevel = ThreatLevel.THREAT)
    }
}
