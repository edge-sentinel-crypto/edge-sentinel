/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.alerts

import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.ui.components.AlertCard
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.SensorCellular
import com.bp22intel.edgesentinel.ui.theme.SensorWifi
import com.bp22intel.edgesentinel.ui.theme.SensorBluetooth
import com.bp22intel.edgesentinel.ui.theme.SensorNetwork
import com.bp22intel.edgesentinel.ui.theme.SensorBaseline
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertListScreen(
    viewModel: AlertsViewModel = hiltViewModel(),
    onAlertClick: (Alert) -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val alerts by viewModel.alerts.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val selectedCategoryFilter by viewModel.selectedCategoryFilter.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Severity filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AlertFilter.entries.forEach { filter ->
                val label = when (filter) {
                    AlertFilter.ALL -> "All"
                    AlertFilter.SUSPICIOUS -> "Suspicious"
                    AlertFilter.THREAT -> "Threat"
                }
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = {
                        Text(
                            text = label,
                            color = if (selectedFilter == filter) TextPrimary else TextSecondary
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBlue.copy(alpha = 0.2f),
                        containerColor = Surface
                    )
                )
            }
        }

        // Category filter chips — only show Cellular and Bluetooth (the types that
        // have Alert DB records). WiFi/Network/Baseline threats live on their
        // dedicated screens and never create Alert records.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Only show categories that can actually have alert records
            val visibleFilters = listOf(
                CategoryFilter.ALL,
                CategoryFilter.CELLULAR,
                CategoryFilter.BLUETOOTH
            )
            visibleFilters.forEach { filter ->
                val label = when (filter) {
                    CategoryFilter.ALL -> "All"
                    CategoryFilter.CELLULAR -> "Cellular"
                    CategoryFilter.BLUETOOTH -> "Bluetooth"
                    else -> ""
                }
                val selectedColor = when (filter) {
                    CategoryFilter.ALL -> AccentBlue
                    CategoryFilter.CELLULAR -> SensorCellular
                    CategoryFilter.BLUETOOTH -> SensorBluetooth
                    else -> AccentBlue
                }
                FilterChip(
                    selected = selectedCategoryFilter == filter,
                    onClick = { viewModel.setCategoryFilter(filter) },
                    label = {
                        Text(
                            text = label,
                            color = if (selectedCategoryFilter == filter) TextPrimary else TextSecondary
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = selectedColor.copy(alpha = 0.2f),
                        containerColor = Surface
                    )
                )
            }
        }

        // Cross-navigation hint for other sensor screens
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "WiFi, Network & Baseline threats appear on their dedicated screens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "WiFi" to "wifi",
                        "Network" to "network",
                        "Baseline" to "baseline"
                    ).forEach { (label, route) ->
                        val chipColor = when (label) {
                            "WiFi" -> SensorWifi
                            "Network" -> SensorNetwork
                            else -> SensorBaseline
                        }
                        FilterChip(
                            selected = false,
                            onClick = { onNavigate(route) },
                            label = {
                                Text(
                                    text = "$label \u2192",
                                    color = chipColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = chipColor.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
            }
        }

        // Export buttons
        if (alerts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val alertExporter = viewModel.alertExporter
                Button(
                    onClick = {
                        val intent = alertExporter.shareTextReport(
                            alerts = alerts,
                            situationBrief = null
                        )
                        context.startActivity(Intent.createChooser(intent, "Share Report"))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export Report", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = {
                        val intent = alertExporter.shareCsvExport(alerts)
                        context.startActivity(Intent.createChooser(intent, "Share CSV"))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.TableChart,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export CSV", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Alert list
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() }
        ) {
            if (alerts.isEmpty()) {
                // ── Empty state card ────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = StatusClear,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "All Clear",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusClear
                            )
                            Text(
                                text = "No active threats detected. Edge Sentinel is monitoring your environment.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // Swipe hint visibility — shows once then fades
                var showSwipeHint by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(3000)
                    showSwipeHint = false
                }

                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(alerts, key = { _, alert -> alert.id }) { index, alert ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart ||
                                    value == SwipeToDismissBoxValue.StartToEnd
                                ) {
                                    // Haptic feedback on successful swipe
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            val vm = context.getSystemService(VibratorManager::class.java)
                                            vm?.defaultVibrator?.vibrate(
                                                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                                            )
                                        } else {
                                            @Suppress("DEPRECATION")
                                            val v = context.getSystemService(Vibrator::class.java)
                                            v?.vibrate(
                                                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                                            )
                                        }
                                    } catch (_: Exception) { /* graceful fallback */ }

                                    viewModel.acknowledgeAlert(alert.id)
                                    true
                                } else {
                                    false
                                }
                            }
                        )

                        Column {
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val color by animateColorAsState(
                                        targetValue = when (dismissState.targetValue) {
                                            SwipeToDismissBoxValue.Settled -> Surface
                                            else -> StatusClear.copy(alpha = 0.2f)
                                        },
                                        label = "swipe_bg"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(color, MaterialTheme.shapes.medium)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Acknowledge",
                                            tint = StatusClear
                                        )
                                    }
                                }
                            ) {
                                AlertCard(
                                    alert = alert,
                                    onClick = { onAlertClick(alert) },
                                    onTrustDevice = { alertId ->
                                        viewModel.acknowledgeAlert(alertId)
                                    }
                                )
                            }

                            // Swipe hint on first card only, fades after 3s
                            if (index == 0) {
                                AnimatedVisibility(
                                    visible = showSwipeHint,
                                    exit = fadeOut()
                                ) {
                                    Text(
                                        text = "← Swipe to acknowledge",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary.copy(alpha = 0.7f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        textAlign = TextAlign.Center
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
