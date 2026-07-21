/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.FusedThreatLevel
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import com.bp22intel.edgesentinel.domain.model.SensorCategoryScore
import com.bp22intel.edgesentinel.domain.model.ThreatTrend
import com.bp22intel.edgesentinel.fusion.DashboardPosture
import com.bp22intel.edgesentinel.ui.components.AlertCard
import com.bp22intel.edgesentinel.ui.components.CellInfoCard
import com.bp22intel.edgesentinel.ui.components.CellInfoCardWithVerification
import com.bp22intel.edgesentinel.detection.tower.TowerVerifier
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.components.ThreatIndicator
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.SensorBaseline
import com.bp22intel.edgesentinel.ui.theme.SensorBluetooth
import com.bp22intel.edgesentinel.ui.theme.SensorCellular
import com.bp22intel.edgesentinel.ui.theme.SensorNetwork
import com.bp22intel.edgesentinel.ui.theme.SensorWifi
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bp22intel.edgesentinel.ui.components.EmptyStateCard
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusCritical
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.StatusElevated
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onAlertClick: (Alert) -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val threatLevel by viewModel.currentThreatLevel.collectAsState()
    val currentCell by viewModel.currentCell.collectAsState()
    val recentAlerts by viewModel.recentAlerts.collectAsState()
    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val monitoringStartTime by viewModel.monitoringStartTime.collectAsState()
    val posture by viewModel.dashboardPosture.collectAsState()
    val fusedAssessment by viewModel.fusedAssessment.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val situationBrief by viewModel.situationBrief.collectAsState()

    // Haptic feedback on threat level changes
    val hapticFeedback = LocalHapticFeedback.current
    LaunchedEffect(posture.level) {
        if (posture.level != com.bp22intel.edgesentinel.domain.model.FusedThreatLevel.CLEAR) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Scaffold(
        containerColor = BackgroundPrimary,
        floatingActionButton = { }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.forceScan() }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // ── 1. Fused Threat Header (hero) with integrated monitoring status ──
            item {
                FusedThreatHeader(
                    posture = posture,
                    isMonitoring = isMonitoring,
                    monitoringStartTime = monitoringStartTime
                )
            }

            // ── 2. AI Situation Analysis (collapsible) ──
            item {
                SituationAnalysisCard(
                    brief = situationBrief,
                    posture = posture
                )
            }

            // ── 3. Active Alerts (max 3, with "See all" link) ──
            if (recentAlerts.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Cellular Alerts (${recentAlerts.size})",
                        actionText = if (recentAlerts.size > 3) "View All" else null,
                        onActionClick = { onNavigate("alerts") }
                    )
                }

                items(recentAlerts.take(3), key = { it.id }) { alert ->
                    AlertCard(
                        alert = alert,
                        onClick = { onAlertClick(alert) }
                    )
                }

                if (recentAlerts.size > 3) {
                    item {
                        TextButton(
                            onClick = { onNavigate("alerts") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "See all ${recentAlerts.size} alerts \u2192",
                                color = AccentBlue
                            )
                        }
                    }
                }
            }

            // ── 4. Detection Layers ──
            item {
                SectionHeader(
                    title = "Detection Layers",
                    actionText = null,
                    onActionClick = {}
                )
            }

            item {
                DetectionLayerCards(
                    categoryScores = posture.categoryBreakdown,
                    onLayerClick = { category ->
                        // Each sensor has its own detail screen where threats are shown.
                        // Cellular alerts go to the alerts tab (they have Alert records).
                        // WiFi/BLE/Network/Baseline go to their dedicated screens
                        // (threats live there, not in the unified alert list).
                        val hasAlerts = posture.categoryBreakdown
                            .find { it.category == category }
                            ?.activeThreatCount ?: 0 > 0
                        val route = when (category) {
                            SensorCategory.CELLULAR -> if (hasAlerts) "alerts" else "cell_info"
                            SensorCategory.WIFI -> "wifi"
                            SensorCategory.BLUETOOTH -> "bluetooth"
                            SensorCategory.NETWORK -> "network"
                            SensorCategory.BASELINE -> "baseline"
                        }
                        onNavigate(route)
                    }
                )
            }

            // ── 5. Quick Actions (scan, radar, mesh, export) ──
            item {
                QuickActionsRow(
                    onScanNow = { viewModel.forceScan() },
                    onRadar = { onNavigate("threat_map") },
                    onMesh = { onNavigate("mesh") },
                    onExport = {
                        if (recentAlerts.isNotEmpty()) {
                            val intent = viewModel.alertExporter.shareTextReport(
                                alerts = recentAlerts,
                                situationBrief = situationBrief
                            )
                            context.startActivity(
                                Intent.createChooser(intent, "Share Report")
                            )
                        }
                    }
                )
            }

            // ── 6. Details (collapsible — connected tower) ──
            item {
                var detailsExpanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { detailsExpanded = !detailsExpanded }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CellTower,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connected Tower",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (detailsExpanded)
                                    Icons.Filled.KeyboardArrowUp
                                else
                                    Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (detailsExpanded) "Collapse" else "Expand",
                                tint = TextSecondary
                            )
                        }
                        AnimatedVisibility(
                            visible = detailsExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 16.dp
                                )
                            ) {
                                val cell = currentCell
                                if (cell != null) {
                                    CellInfoCardWithVerification(cellTower = cell)
                                } else {
                                    EmptyStateCard(
                                        icon = Icons.Filled.CellTower,
                                        title = "No Cell Data",
                                        subtitle = "Waiting for cell tower information"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FusedThreatHeader(
    posture: DashboardPosture,
    isMonitoring: Boolean = true,
    monitoringStartTime: Long = 0L
) {
    val color = fusedLevelColor(posture.level)
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Interactive threat indicator (replaces raw Box/Text)
            ThreatIndicator(
                fusedLevel = posture.level,
                size = 120.dp,
                score = posture.score
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Fused Threat Posture",
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary
            )

            // Integrated monitoring status (replaces separate MonitoringStatusBar)
            Spacer(modifier = Modifier.height(4.dp))
            val monitoringText = if (isMonitoring) {
                val elapsed = if (monitoringStartTime > 0L) {
                    val seconds = (System.currentTimeMillis() - monitoringStartTime) / 1000
                    when {
                        seconds < 60 -> "${seconds}s"
                        seconds < 3600 -> "${seconds / 60}m"
                        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
                    }
                } else null
                if (elapsed != null) "Monitoring active \u00B7 Running for $elapsed"
                else "Monitoring active"
            } else {
                "Monitoring inactive"
            }
            Text(
                text = monitoringText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMonitoring) StatusClear else TextSecondary
            )

            if (posture.activeThreatCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${posture.activeThreatCount} active signal${if (posture.activeThreatCount != 1) "s" else ""} across sensors",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }

            // ── Expandable detail section ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    HorizontalDivider(color = color.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Score explanation
                    Text(
                        text = "Score: ${"%,.1f".format(posture.score)} / 10",
                        style = MaterialTheme.typography.titleMedium,
                        color = color,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = posture.scoreExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Trend row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val trendIcon = when (posture.trend) {
                            ThreatTrend.IMPROVING -> Icons.Filled.TrendingDown
                            ThreatTrend.STABLE -> Icons.Filled.TrendingFlat
                            ThreatTrend.WORSENING -> Icons.Filled.TrendingUp
                        }
                        val trendColor = when (posture.trend) {
                            ThreatTrend.IMPROVING -> StatusClear
                            ThreatTrend.STABLE -> TextSecondary
                            ThreatTrend.WORSENING -> StatusDangerous
                        }
                        Icon(
                            imageVector = trendIcon,
                            contentDescription = posture.trend.label,
                            tint = trendColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = posture.trendDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = trendColor
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Per-category mini breakdown
                    Text(
                        text = "Sensor Breakdown",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    posture.categoryBreakdown.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cat.category.name.lowercase()
                                    .replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.width(80.dp)
                            )
                            LinearProgressIndicator(
                                progress = { cat.score.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = when {
                                    cat.score > 0.7 -> StatusDangerous
                                    cat.score > 0.3 -> StatusElevated
                                    else -> StatusClear
                                },
                                trackColor = color.copy(alpha = 0.1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (cat.activeThreatCount > 0)
                                    "${cat.activeThreatCount}" else "—",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (cat.activeThreatCount > 0) color else TextSecondary,
                                modifier = Modifier.width(20.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }

                    // Brief summary
                    if (posture.briefSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = posture.briefSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorCategoryBreakdown(
    categoryScores: List<SensorCategoryScore>,
    onCategoryClick: (SensorCategory) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            categoryScores.forEach { score ->
                SensorMiniIndicator(
                    score = score,
                    onClick = { onCategoryClick(score.category) }
                )
            }
        }
    }
}

@Composable
private fun SensorMiniIndicator(score: SensorCategoryScore, onClick: () -> Unit = {}) {
    val categoryColor = when (score.category) {
        SensorCategory.CELLULAR -> SensorCellular
        SensorCategory.WIFI -> SensorWifi
        SensorCategory.BLUETOOTH -> SensorBluetooth
        SensorCategory.NETWORK -> SensorNetwork
        SensorCategory.BASELINE -> SensorBaseline
    }

    val indicatorColor = when {
        score.score > 0.7 -> StatusDangerous
        score.score > 0.3 -> StatusElevated
        score.activeThreatCount > 0 -> StatusElevated
        else -> StatusClear
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(indicatorColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = score.category.icon,
                contentDescription = score.category.label,
                tint = indicatorColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = score.category.label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontSize = 10.sp
        )
        if (score.activeThreatCount > 0) {
            Text(
                text = "${score.activeThreatCount}",
                style = MaterialTheme.typography.labelSmall,
                color = indicatorColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun SituationAnalysisCard(
    brief: com.bp22intel.edgesentinel.analysis.SituationBrief,
    posture: DashboardPosture
) {
    var expanded by remember { mutableStateOf(false) }

    val color = when (brief.overallRisk) {
        com.bp22intel.edgesentinel.analysis.RiskLevel.CRITICAL -> StatusDangerous
        com.bp22intel.edgesentinel.analysis.RiskLevel.HIGH -> StatusElevated
        com.bp22intel.edgesentinel.analysis.RiskLevel.MEDIUM -> StatusElevated
        com.bp22intel.edgesentinel.analysis.RiskLevel.LOW -> StatusClear
    }

    val headline = when (brief.overallRisk) {
        com.bp22intel.edgesentinel.analysis.RiskLevel.CRITICAL -> "Critical Threat"
        com.bp22intel.edgesentinel.analysis.RiskLevel.HIGH -> "High Concern"
        com.bp22intel.edgesentinel.analysis.RiskLevel.MEDIUM -> "Elevated Activity"
        com.bp22intel.edgesentinel.analysis.RiskLevel.LOW -> if (brief.allClear) "All Clear" else "Monitoring"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header — always visible
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI THREAT ANALYSIS",
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Icon(
                    imageVector = if (expanded)
                        Icons.Filled.KeyboardArrowUp
                    else
                        Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = TextSecondary
                )
            }

            // Summary — collapsed: 2 lines, expanded: full
            Text(
                text = brief.summary,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!expanded) {
                Text(
                    text = "Read more \u25BE",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBlue,
                    fontWeight = FontWeight.Medium
                )
            }

            // ── Expanded details ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top concerns — each one is a specific finding
                    if (brief.topConcerns.isNotEmpty()) {
                        brief.topConcerns.forEachIndexed { index, concern ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "\u2022",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (index == 0) color else TextSecondary,
                                    modifier = Modifier.padding(end = 6.dp, top = 1.dp)
                                )
                                Text(
                                    text = concern,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (index == 0) TextPrimary else TextSecondary,
                                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                                )
                            }
                        }
                    }

                    // Recommendations
                    if (brief.recommendations.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Surface
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "RECOMMENDED ACTIONS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentBlue,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                                brief.recommendations.forEach { rec ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = Icons.Filled.Shield,
                                            contentDescription = null,
                                            tint = AccentBlue,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .padding(top = 2.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = rec,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Cooperative localization context
                    brief.cooperativeContext?.let { coopCtx ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF59E0B).copy(alpha = 0.08f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "\u2B50",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(end = 6.dp, top = 1.dp)
                                )
                                Text(
                                    text = coopCtx,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Text(
                        text = "Show less \u25B4",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    onScanNow: () -> Unit,
    onRadar: () -> Unit,
    onMesh: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButton(
            icon = Icons.Filled.Refresh,
            label = "Scan",
            tint = AccentBlue,
            modifier = Modifier.weight(1f),
            onClick = onScanNow
        )
        QuickActionButton(
            icon = Icons.Filled.Radar,
            label = "Radar",
            tint = AccentBlue,
            modifier = Modifier.weight(1f),
            onClick = onRadar
        )
        QuickActionButton(
            icon = Icons.Filled.People,
            label = "Mesh",
            tint = Color(0xFF06B6D4),
            modifier = Modifier.weight(1f),
            onClick = onMesh
        )
        QuickActionButton(
            icon = Icons.Filled.Share,
            label = "Export",
            tint = TextSecondary,
            modifier = Modifier.weight(1f),
            onClick = onExport
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun DetectionLayerCards(
    categoryScores: List<SensorCategoryScore>,
    onLayerClick: (SensorCategory) -> Unit = {}
) {
    val layerDescriptions = mapOf(
        SensorCategory.CELLULAR to "IMSI catcher, tower changes, silent SMS",
        SensorCategory.WIFI to "Evil twin, rogue AP, deauth attacks",
        SensorCategory.BLUETOOTH to "AirTag, SmartTag, Tile tracking",
        SensorCategory.NETWORK to "DNS, TLS, VPN integrity",
        SensorCategory.BASELINE to "RF environment anomaly detection"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        categoryScores.forEach { score ->
            val statusColor = when {
                score.score > 0.7 -> StatusDangerous
                score.score > 0.3 -> StatusElevated
                score.activeThreatCount > 0 -> StatusElevated
                else -> StatusClear
            }
            val statusText = when {
                score.activeThreatCount > 0 -> "${score.activeThreatCount} threat${if (score.activeThreatCount != 1) "s" else ""}"
                else -> "Clear"
            }

            Card(
                onClick = { onLayerClick(score.category) },
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = score.category.icon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = score.category.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = layerDescriptions[score.category] ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }

                    // Status badge
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ThreatSummaryBar(posture: DashboardPosture) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Trend
            Row(verticalAlignment = Alignment.CenterVertically) {
                val trendColor = when (posture.trend) {
                    ThreatTrend.IMPROVING -> StatusClear
                    ThreatTrend.STABLE -> TextSecondary
                    ThreatTrend.WORSENING -> StatusDangerous
                }
                Text(
                    text = posture.trend.arrow,
                    fontSize = 18.sp,
                    color = trendColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = posture.trend.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = trendColor
                )
            }

            // Last detection
            Text(
                text = posture.timeSinceLastDetection,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun FusedNarrativeCard(narrative: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Threat Analysis",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = narrative,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 18.sp,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun fusedLevelColor(level: FusedThreatLevel): Color {
    return when (level) {
        FusedThreatLevel.CLEAR -> StatusClear
        FusedThreatLevel.ELEVATED -> StatusElevated
        FusedThreatLevel.DANGEROUS -> StatusDangerous
        FusedThreatLevel.CRITICAL -> StatusCritical
    }
}

@Composable
private fun MeshNetworkCard(onNavigate: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate() },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mesh icon with cyan accent
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF06B6D4).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = "Mesh Network",
                    tint = Color(0xFF06B6D4),
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Mesh Network",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = "Share alerts with nearby devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            
            // Status indicator - you could enhance this to show real mesh status
            Text(
                text = "Tap to configure",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF06B6D4)
            )
        }
    }
}
