/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.baseline

import com.bp22intel.edgesentinel.ui.utils.TimeFormatUtils

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bp22intel.edgesentinel.baseline.BaselineConfidence
import com.bp22intel.edgesentinel.baseline.LocationBaseline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaselineScreen(
    onBack: () -> Unit,
    viewModel: BaselineViewModel = hiltViewModel()
) {
    val baselines by viewModel.baselines.collectAsStateWithLifecycle()
    val selectedBaseline by viewModel.selectedBaseline.collectAsStateWithLifecycle()
    val currentAnomaly by viewModel.currentAnomaly.collectAsStateWithLifecycle()

    if (selectedBaseline != null) {
        BaselineDetailScreen(
            baseline = selectedBaseline!!,
            onBack = { viewModel.clearSelection() },
            onReset = { viewModel.resetBaseline(it) }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("RF Baselines") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                )
            }
        ) { padding ->
            if (baselines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No baselines yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Baselines are learned automatically as you\nvisit locations with monitoring enabled.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    item {
                        if (currentAnomaly != null) {
                            AnomalySummaryCard(
                                compositeScore = currentAnomaly!!.compositeScore,
                                isNewLocation = currentAnomaly!!.isNewLocation,
                                confidence = currentAnomaly!!.confidence,
                                missingTowerScore = currentAnomaly!!.missingTowerScore,
                                unknownTowerScore = currentAnomaly!!.unknownTowerScore,
                                signalDeviationScore = currentAnomaly!!.signalDeviationScore,
                                networkTypeScore = currentAnomaly!!.networkTypeScore,
                                wifiChangeScore = currentAnomaly!!.wifiChangeScore
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    item {
                        Text(
                            "Learned Locations (${baselines.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(baselines, key = { it.id }) { baseline ->
                        BaselineLocationCard(
                            baseline = baseline,
                            onClick = { viewModel.selectBaseline(baseline) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnomalySummaryCard(
    compositeScore: Double,
    isNewLocation: Boolean,
    confidence: BaselineConfidence,
    missingTowerScore: Double,
    unknownTowerScore: Double,
    signalDeviationScore: Double,
    networkTypeScore: Double,
    wifiChangeScore: Double
) {
    val statusColor by animateColorAsState(
        when {
            isNewLocation -> Color(0xFF2196F3)
            compositeScore < 0.3 -> Color(0xFF4CAF50)
            compositeScore < 0.6 -> Color(0xFFFF9800)
            else -> Color(0xFFF44336)
        },
        label = "statusColor"
    )

    val statusText = when {
        isNewLocation -> "Learning New Location"
        compositeScore < 0.3 -> "Normal"
        compositeScore < 0.6 -> "Anomaly Detected"
        else -> "High Anomaly"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!isNewLocation) {
                Spacer(Modifier.height(12.dp))
                AnomalyCategoryRow("Missing towers", missingTowerScore)
                AnomalyCategoryRow("Unknown towers", unknownTowerScore)
                AnomalyCategoryRow("Signal deviation", signalDeviationScore)
                AnomalyCategoryRow("Network type", networkTypeScore)
                AnomalyCategoryRow("WiFi changes", wifiChangeScore)
            }

            if (confidence != BaselineConfidence.CONFIDENT) {
                Spacer(Modifier.height(8.dp))
                LearningProgressBar(confidence)
            }
        }
    }
}

@Composable
private fun AnomalyCategoryRow(label: String, score: Double) {
    val color = when {
        score < 0.3 -> Color(0xFF4CAF50)
        score < 0.6 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { score.toFloat() },
                modifier = Modifier
                    .width(80.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = color.copy(alpha = 0.15f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (score < 0.3) "OK" else "%.0f%%".format(score * 100),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun LearningProgressBar(confidence: BaselineConfidence) {
    val observations = when (confidence) {
        BaselineConfidence.LEARNING -> 0
        BaselineConfidence.LOW -> BaselineConfidence.LOW.minObservations
        BaselineConfidence.MEDIUM -> BaselineConfidence.MEDIUM.minObservations
        BaselineConfidence.CONFIDENT -> BaselineConfidence.CONFIDENT.minObservations
    }
    val progress = observations.toFloat() / BaselineConfidence.CONFIDENT.minObservations.toFloat()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Learning mode",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2196F3)
            )
            Text(
                "$observations/${BaselineConfidence.CONFIDENT.minObservations} observations",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Color(0xFF2196F3)
        )
    }
}

@Composable
private fun BaselineLocationCard(
    baseline: LocationBaseline,
    onClick: () -> Unit
) {
    val confidenceColor = when (baseline.confidence) {
        BaselineConfidence.CONFIDENT -> Color(0xFF4CAF50)
        BaselineConfidence.MEDIUM -> Color(0xFF8BC34A)
        BaselineConfidence.LOW -> Color(0xFFFF9800)
        BaselineConfidence.LEARNING -> Color(0xFF2196F3)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(confidenceColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = confidenceColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    baseline.label ?: "%.4f, %.4f".format(baseline.latitude, baseline.longitude),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        "${baseline.expectedTowers.size} towers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        " \u2022 ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${baseline.expectedWifiAps.size} APs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        " \u2022 ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        baseline.confidence.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = confidenceColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (baseline.confidence != BaselineConfidence.CONFIDENT) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${baseline.observationCount}/${BaselineConfidence.CONFIDENT.minObservations}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2196F3)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaselineDetailScreen(
    baseline: LocationBaseline,
    onBack: () -> Unit,
    onReset: (Long) -> Unit
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Baseline?") },
            text = {
                Text("This will delete all learned RF data for this location. " +
                        "The baseline will be re-learned from scratch.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onReset(baseline.id)
                }) {
                    Text("Reset", color = Color(0xFFF44336))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        baseline.label ?: "%.4f, %.4f".format(baseline.latitude, baseline.longitude),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.Refresh, "Reset baseline")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            // Status card
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        DetailRow("Confidence", baseline.confidence.name)
                        DetailRow("Observations", "${baseline.observationCount}")
                        DetailRow("Radius", "%.0fm".format(baseline.radiusMeters))
                        DetailRow("Last updated", TimeFormatUtils.formatTimestampDate(baseline.updatedAt))
                    }
                }
            }

            // Expected towers
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CellTower, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Expected Towers (${baseline.expectedTowers.size})",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        for (tower in baseline.expectedTowers.sortedByDescending { it.appearanceRate }) {
                            TowerRow(tower)
                        }
                    }
                }
            }

            // WiFi APs
            if (baseline.expectedWifiAps.isNotEmpty()) {
                item {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wifi, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Expected WiFi APs (${baseline.expectedWifiAps.size})",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            for (ap in baseline.expectedWifiAps.sortedByDescending { it.appearanceRate }) {
                                WifiApRow(ap)
                            }
                        }
                    }
                }
            }

            // Network distribution
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NetworkCheck, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Network Distribution", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        for ((type, pct) in baseline.networkTypeDistribution
                            .toList()
                            .sortedByDescending { it.second }) {
                            NetworkDistRow(type, pct)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TowerRow(tower: com.bp22intel.edgesentinel.baseline.ExpectedTower) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.SignalCellular4Bar, null, Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CID ${tower.cid} \u2022 ${tower.mcc}/${tower.mnc} \u2022 ${tower.networkType}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Signal: ${tower.signalMin}..${tower.signalMax} dBm (avg %.0f) \u2022 Seen %.0f%%"
                    .format(tower.signalAvg, tower.appearanceRate * 100),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WifiApRow(ap: com.bp22intel.edgesentinel.baseline.ExpectedWifiAp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Wifi, null, Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${ap.ssid} (${ap.bssid})",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Signal: ${ap.signalMin}..${ap.signalMax} dBm \u2022 Seen %.0f%%"
                    .format(ap.appearanceRate * 100),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NetworkDistRow(type: String, pct: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(type, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { pct.toFloat() },
                modifier = Modifier
                    .width(100.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "%.0f%%".format(pct * 100),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// formatTimestamp removed — use TimeFormatUtils.formatTimestampDate()
