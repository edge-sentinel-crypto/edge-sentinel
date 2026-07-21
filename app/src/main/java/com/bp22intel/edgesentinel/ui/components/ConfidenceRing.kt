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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.ui.theme.EdgeSentinelTheme
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextSecondary

/**
 * Animated confidence ring that visually communicates detection confidence.
 *
 * Shows a circular progress arc with the confidence percentage in the center.
 * Color transitions from green (low concern) through yellow to red (high concern)
 * based on the threat score, not just confidence level.
 */
@Composable
fun ConfidenceRing(
    confidence: Confidence,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    strokeWidth: Dp = 4.dp,
    /** Optional raw score (0.0–1.0) for more precise display. Falls back to confidence tier. */
    rawScore: Float? = null,
    /** Color override. If null, derived from confidence level. */
    ringColor: Color? = null,
    showLabel: Boolean = true
) {
    val fraction = rawScore ?: when (confidence) {
        Confidence.LOW -> 0.33f
        Confidence.MEDIUM -> 0.66f
        Confidence.HIGH -> 0.95f
    }

    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 600),
        label = "confidence_ring"
    )

    val color = ringColor ?: when (confidence) {
        Confidence.LOW -> StatusSuspicious.copy(alpha = 0.7f)
        Confidence.MEDIUM -> StatusSuspicious
        Confidence.HIGH -> StatusThreat
    }

    val percentage = (animatedFraction * 100).toInt()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)

            // Background track
            drawArc(
                color = Surface,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Confidence arc
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animatedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }

        if (showLabel) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$percentage",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    fontSize = if (size >= 56.dp) 14.sp else 10.sp
                )
                if (size >= 56.dp) {
                    Text(
                        text = "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

/**
 * Compact confidence badge for list items — shows confidence as a colored bar segment.
 */
@Composable
fun ConfidenceBadge(
    confidence: Confidence,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (confidence) {
        Confidence.LOW -> "LOW" to StatusClear
        Confidence.MEDIUM -> "MED" to StatusSuspicious
        Confidence.HIGH -> "HIGH" to StatusThreat
    }

    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ConfidenceRingHighPreview() {
    EdgeSentinelTheme {
        ConfidenceRing(confidence = Confidence.HIGH)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ConfidenceRingMediumPreview() {
    EdgeSentinelTheme {
        ConfidenceRing(confidence = Confidence.MEDIUM, size = 40.dp)
    }
}
