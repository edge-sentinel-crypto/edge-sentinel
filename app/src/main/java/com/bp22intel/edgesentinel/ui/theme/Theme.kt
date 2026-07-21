/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val EdgeSentinelColorScheme = darkColorScheme(
    primary = StatusClear,
    secondary = AccentBlue,
    tertiary = StatusSuspicious,
    error = StatusThreat,
    background = BackgroundPrimary,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onPrimary = BackgroundPrimary,
    onSecondary = BackgroundPrimary,
    onTertiary = BackgroundPrimary,
    onError = BackgroundPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary
)

@Composable
fun EdgeSentinelTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EdgeSentinelColorScheme,
        typography = EdgeSentinelTypography,
        shapes = EdgeSentinelShapes,
        content = content
    )
}
