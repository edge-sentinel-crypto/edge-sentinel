/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bp22intel.edgesentinel.MainActivity
import com.bp22intel.edgesentinel.domain.model.ThreatLevel

class ThreatStatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            ThreatStatusContent(
                threatLevel = ThreatLevel.CLEAR,
                isRunning = true
            )
        }
    }
}

@Composable
private fun ThreatStatusContent(
    threatLevel: ThreatLevel,
    isRunning: Boolean
) {
    val statusColor = when (threatLevel) {
        ThreatLevel.CLEAR -> ColorProvider(android.graphics.Color.parseColor("#10B981"))
        ThreatLevel.SUSPICIOUS -> ColorProvider(android.graphics.Color.parseColor("#F59E0B"))
        ThreatLevel.THREAT -> ColorProvider(android.graphics.Color.parseColor("#EF4444"))
    }

    val statusLabel = when (threatLevel) {
        ThreatLevel.CLEAR -> "CLEAR"
        ThreatLevel.SUSPICIOUS -> "SUSPICIOUS"
        ThreatLevel.THREAT -> "THREAT"
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Edge Sentinel",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        Text(
            text = statusLabel,
            style = TextStyle(
                color = statusColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        Text(
            text = if (isRunning) "Monitoring active" else "Monitoring inactive",
            style = TextStyle(fontSize = 10.sp)
        )
    }
}

class ThreatStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ThreatStatusWidget()
}
