/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary

// Heat map signal strength colors (matching HeatMapOverlay)
val HeatMapStrong = Color(0xFFFF1744)   // Bright red
val HeatMapMedium = Color(0xFFFF9100)   // Orange
val HeatMapWeak = Color(0xFF10B981)     // Teal/green
val HeatMapFaint = Color(0xFF3B82F6)    // Blue

// Peer colors
val HeatMapPeerStrong = Color(0xFF06B6D4)  // Cyan
val HeatMapPeerMedium = Color(0xFF3B82F6)  // Blue
val HeatMapPeerWeak = Color(0xFF6366F1)    // Indigo
val HeatMapPeerFaint = Color(0xFF8B5CF6)   // Purple

/**
 * Small legend overlay showing heat map color coding.
 * Displayed when the heat map layer is active.
 */
@Composable
fun HeatMapLegend(
    showPeerLegend: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "SIGNAL HEAT MAP",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        HeatMapLegendRow(color = HeatMapStrong, label = "Strong", range = "> -60 dBm")
        HeatMapLegendRow(color = HeatMapMedium, label = "Medium", range = "-60 to -80")
        HeatMapLegendRow(color = HeatMapWeak, label = "Weak", range = "-80 to -100")
        HeatMapLegendRow(color = HeatMapFaint, label = "Faint", range = "< -100 dBm")

        if (showPeerLegend) {
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = "PEER COVERAGE",
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
            HeatMapLegendRow(color = HeatMapPeerStrong, label = "Peer Strong", range = "> -60")
            HeatMapLegendRow(color = HeatMapPeerFaint, label = "Peer Weak", range = "< -100")
        }
    }
}

@Composable
private fun HeatMapLegendRow(
    color: Color,
    label: String,
    range: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = range,
            color = TextSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
