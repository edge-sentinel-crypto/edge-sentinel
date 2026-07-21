/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary


@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToAbout: () -> Unit = {},
    onNavigateToTravel: () -> Unit = {},
    onNavigateToTowerDatabase: () -> Unit = {},
    onNavigateToCalibration: () -> Unit = {}
) {
    val context = LocalContext.current
    val isMonitoringEnabled by viewModel.isMonitoringEnabled.collectAsState()
    val detectionSensitivity by viewModel.detectionSensitivity.collectAsState()
    val notificationSound by viewModel.notificationSound.collectAsState()
    val notificationVibration by viewModel.notificationVibration.collectAsState()
    val isDemoMode by viewModel.isDemoMode.collectAsState()
    val isAdvancedMode by viewModel.isAdvancedMode.collectAsState()
    val isRooted by viewModel.isRooted.collectAsState()
    val alertRetentionDays by viewModel.alertRetentionDays.collectAsState()
    val isCooperativeLocalization by viewModel.isCooperativeLocalization.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Monitoring toggle
        item {
            SectionHeader(title = "Monitoring")
        }

        item {
            SettingsToggleItem(
                title = "Enable Monitoring",
                subtitle = "Continuously scan for cellular threats in the background",
                checked = isMonitoringEnabled,
                onCheckedChange = { viewModel.setMonitoringEnabled(it) }
            )
        }

        // Detection sensitivity
        item {
            SectionHeader(title = "Detection Sensitivity")
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DetectionSensitivity.entries.forEach { sensitivity ->
                        val label = when (sensitivity) {
                            DetectionSensitivity.LOW -> "Low"
                            DetectionSensitivity.MEDIUM -> "Medium"
                            DetectionSensitivity.HIGH -> "High"
                        }
                        val description = when (sensitivity) {
                            DetectionSensitivity.LOW -> "Fewer false positives, may miss subtle threats"
                            DetectionSensitivity.MEDIUM -> "Balanced detection (recommended)"
                            DetectionSensitivity.HIGH -> "Maximum sensitivity, may produce more alerts"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = detectionSensitivity == sensitivity,
                                onClick = { viewModel.setDetectionSensitivity(sensitivity) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentBlue,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Notification preferences
        item {
            SectionHeader(title = "Notifications")
        }

        item {
            SettingsToggleItem(
                title = "Alert Sound",
                subtitle = "Play a sound when threats are detected",
                checked = notificationSound,
                onCheckedChange = { viewModel.setNotificationSound(it) }
            )
        }

        item {
            SettingsToggleItem(
                title = "Vibration",
                subtitle = "Vibrate when threats are detected",
                checked = notificationVibration,
                onCheckedChange = { viewModel.setNotificationVibration(it) }
            )
        }

        // Permissions
        item {
            SectionHeader(title = "Permissions")
        }

        item {
            PermissionsCard(context = context)
        }

        // Cooperative Localization (Mesh)
        item {
            SectionHeader(title = "Mesh Network")
        }

        item {
            SettingsToggleItem(
                title = "Cooperative Localization",
                subtitle = "Share anonymous signal observations with nearby Edge Sentinel users to collectively locate suspicious towers. Only grid-snapped position and cell signal data is shared.",
                checked = isCooperativeLocalization,
                onCheckedChange = { viewModel.setCooperativeLocalization(it) }
            )
        }

        // Demo mode
        item {
            SectionHeader(title = "Development")
        }

        item {
            SettingsToggleItem(
                title = "Demo Mode",
                subtitle = "Use simulated data for testing and demonstration",
                checked = isDemoMode,
                onCheckedChange = { viewModel.setDemoMode(it) }
            )
        }

        // Advanced mode (root required)
        item {
            SettingsToggleItem(
                title = "Advanced Mode",
                subtitle = if (isRooted) {
                    "Access low-level radio data for enhanced detection"
                } else {
                    "Requires root access"
                },
                checked = isAdvancedMode,
                onCheckedChange = { viewModel.setAdvancedMode(it) },
                enabled = isRooted
            )
        }

        // Calibration Mode (only show if Advanced Mode is enabled)
        if (isAdvancedMode) {
            item {
                SectionHeader(title = "Advanced")
            }
            
            item {
                Button(
                    onClick = onNavigateToCalibration,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Calibration Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Improve geolocation accuracy by walking your area",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Travel Mode
        item {
            SectionHeader(title = "Travel Mode")
        }

        item {
            Button(
                onClick = onNavigateToTravel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "Travel Mode Settings")
            }
        }

        // Data section
        item {
            SectionHeader(title = "Data")
        }

        // Alert retention period
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Alert Retention",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "How long to keep alert history",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    listOf(7, 14, 30).forEach { days ->
                        val label = when (days) {
                            7 -> "7 days"
                            14 -> "14 days"
                            30 -> "30 days"
                            else -> "$days days"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = alertRetentionDays == days,
                                onClick = { viewModel.setAlertRetentionDays(days) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentBlue,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onNavigateToTowerDatabase,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "Tower Database")
            }
        }

        item {
            Button(
                onClick = { viewModel.exportLogs(context) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "Export Logs")
            }
        }

        // About section
        item {
            SectionHeader(title = "About")
        }

        item {
            Button(
                onClick = onNavigateToAbout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "About Edge Sentinel")
            }
        }
    }
}

private data class PermissionItem(
    val name: String,
    val permission: String,
    val description: String
)

@Composable
private fun PermissionsCard(context: Context) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-check permissions when returning from system settings
    val refreshTrigger = remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger.value++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = buildList {
        add(PermissionItem("Fine Location", Manifest.permission.ACCESS_FINE_LOCATION, "Cell tower & WiFi scanning"))
        add(PermissionItem("Coarse Location", Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate positioning"))
        add(PermissionItem("Phone State", Manifest.permission.READ_PHONE_STATE, "Cell network info"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(PermissionItem("Bluetooth Scan", Manifest.permission.BLUETOOTH_SCAN, "BLE tracker detection"))
            add(PermissionItem("Bluetooth Connect", Manifest.permission.BLUETOOTH_CONNECT, "Mesh alerting"))
            add(PermissionItem("Bluetooth Advertise", Manifest.permission.BLUETOOTH_ADVERTISE, "Cooperative mesh sharing"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem("Notifications", Manifest.permission.POST_NOTIFICATIONS, "Threat alerts"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem("Nearby WiFi", Manifest.permission.NEARBY_WIFI_DEVICES, "WiFi threat detection"))
        }
    }

    // Force recomposition on refresh
    @Suppress("UNUSED_EXPRESSION")
    refreshTrigger.value

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            permissions.forEach { item ->
                val granted = ContextCompat.checkSelfPermission(
                    context, item.permission
                ) == PackageManager.PERMISSION_GRANTED

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = if (granted) "Granted" else "Denied",
                            tint = if (granted) StatusClear else StatusDangerous,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    if (!granted) {
                        TextButton(onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }) {
                            Text(
                                text = "Grant",
                                color = AccentBlue,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val allGranted = permissions.all {
                ContextCompat.checkSelfPermission(context, it.permission) == PackageManager.PERMISSION_GRANTED
            }
            Text(
                text = if (allGranted) "All permissions granted" else "Tap Grant to open system settings",
                style = MaterialTheme.typography.bodySmall,
                color = if (allGranted) StatusClear else TextSecondary
            )
        }
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AccentBlue,
                    checkedThumbColor = TextPrimary,
                    uncheckedTrackColor = SurfaceVariant,
                    uncheckedThumbColor = TextSecondary
                )
            )
        }
    }
}

