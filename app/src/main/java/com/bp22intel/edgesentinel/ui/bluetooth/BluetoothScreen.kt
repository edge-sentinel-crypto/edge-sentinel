/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.bluetooth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.data.local.entity.BleDeviceEntity
import com.bp22intel.edgesentinel.detection.bluetooth.BleAlertManager
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BluetoothScreen(
    onBack: () -> Unit = {},
    viewModel: BluetoothViewModel = hiltViewModel()
) {
    val isScanning by viewModel.isScanning.collectAsState()
    val nearbyCount by viewModel.nearbyDeviceCount.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val recentDevices by viewModel.recentDevices.collectAsState()
    val trackers by viewModel.trackers.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val context = LocalContext.current

    // Runtime permission request for BLE scanning (Android 12+)
    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.toggleScanning()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth Tracking") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundPrimary,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        },
        containerColor = BackgroundPrimary,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val scanGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.BLUETOOTH_SCAN
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        val connectGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.BLUETOOTH_CONNECT
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!scanGranted || !connectGranted) {
                            blePermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.BLUETOOTH_SCAN,
                                    android.Manifest.permission.BLUETOOTH_CONNECT
                                )
                            )
                        } else {
                            viewModel.toggleScanning()
                        }
                    } else {
                        viewModel.toggleScanning()
                    }
                },
                containerColor = if (isScanning) StatusSuspicious else AccentBlue
            ) {
                Icon(
                    imageVector = if (isScanning) {
                        Icons.Default.BluetoothSearching
                    } else {
                        Icons.Default.Bluetooth
                    },
                    contentDescription = if (isScanning) "Stop scanning" else "Start scanning"
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // BLE Environment Summary
            item {
                SectionHeader(title = "BLE Environment")
            }

            item {
                BleEnvironmentCard(
                    isScanning = isScanning,
                    nearbyCount = nearbyCount,
                    totalTracked = recentDevices.size,
                    knownTrackers = trackers.size
                )
            }

            // Tracking Alerts
            if (alerts.isNotEmpty()) {
                item {
                    SectionHeader(title = "Tracking Alerts")
                }

                items(alerts, key = { "${it.type}_${it.deviceEntity.macAddress}" }) { alert ->
                    BleAlertCard(alert = alert)
                }
            }

            // Known Trackers Nearby
            if (trackers.isNotEmpty()) {
                item {
                    SectionHeader(title = "Known Trackers")
                }

                items(trackers, key = { it.id }) { tracker ->
                    BleDeviceCard(device = tracker, isTracker = true)
                }
            }

            // Recent BLE Devices
            item {
                SectionHeader(title = "Nearby BLE Devices")
            }

            val nonTrackerDevices = recentDevices.filter { !it.isTrackerType }
            if (nonTrackerDevices.isEmpty()) {
                item {
                    Text(
                        text = when {
                            statusMessage != null -> statusMessage!!
                            isScanning -> "Scanning for BLE devices..."
                            else -> "Tap the button to start scanning"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (statusMessage != null) Color(0xFFEF4444) else TextSecondary
                    )
                }
            } else {
                items(nonTrackerDevices.take(50), key = { it.id }) { device ->
                    BleDeviceCard(device = device, isTracker = false)
                }
            }
        }
    }
}

@Composable
private fun BleEnvironmentCard(
    isScanning: Boolean,
    nearbyCount: Int,
    totalTracked: Int,
    knownTrackers: Int
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = if (isScanning) "Scanning" else "Idle",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isScanning) StatusClear else TextSecondary
                )
            }

            StatRow(label = "Nearby devices (current scan)", value = "$nearbyCount")
            StatRow(label = "Devices tracked (last 2 hours)", value = "$totalTracked")
            StatRow(
                label = "Known trackers detected",
                value = "$knownTrackers",
                valueColor = if (knownTrackers > 0) StatusThreat else StatusClear
            )
        }
    }
}

@Composable
private fun BleAlertCard(alert: BleAlertManager.BleAlert) {
    val alertColor = when {
        alert.confidence >= 0.80f -> StatusThreat
        alert.confidence >= 0.50f -> StatusSuspicious
        else -> AccentBlue
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Alert",
                tint = alertColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alert.type.name.replace("_", " "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = alertColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = alert.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Confidence: ${"%.0f".format(alert.confidence * 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun BleDeviceCard(
    device: BleDeviceEntity,
    isTracker: Boolean
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isTracker) SurfaceVariant else Surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isTracker) Icons.Default.LocationOn else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (isTracker) StatusThreat else AccentBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = device.deviceName ?: device.macAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    if (device.trackerProtocol != null) {
                        Text(
                            text = device.trackerProtocol.replace("_", " "),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusThreat,
                            modifier = Modifier
                                .background(
                                    StatusThreat.copy(alpha = 0.15f),
                                    MaterialTheme.shapes.extraSmall
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Seen ${device.seenCount}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "Last: ${timeFormat.format(Date(device.lastSeen))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}
