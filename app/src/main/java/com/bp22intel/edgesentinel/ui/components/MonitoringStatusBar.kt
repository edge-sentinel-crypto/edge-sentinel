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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bp22intel.edgesentinel.ui.theme.EdgeSentinelTheme
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import com.bp22intel.edgesentinel.ui.theme.TextTertiary
import kotlinx.coroutines.delay

private fun formatElapsedTime(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
fun MonitoringStatusBar(
    isActive: Boolean,
    startTime: Long,
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isActive) {
        while (isActive) {
            currentTime = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val elapsed = if (isActive) currentTime - startTime else 0L

    // Breathing pulse animation for the active dot
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status dot with breathing pulse
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(if (isActive) breathingAlpha else 1f)
                .clip(CircleShape)
                .background(if (isActive) StatusClear else TextTertiary)
        )

        Text(
            text = if (isActive) "Monitoring Active" else "Monitoring Inactive",
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )

        if (isActive) {
            Text(
                text = formatElapsedTime(elapsed),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun MonitoringStatusBarActivePreview() {
    EdgeSentinelTheme {
        MonitoringStatusBar(
            isActive = true,
            startTime = System.currentTimeMillis() - 3_723_000
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun MonitoringStatusBarInactivePreview() {
    EdgeSentinelTheme {
        MonitoringStatusBar(
            isActive = false,
            startTime = 0L
        )
    }
}
