/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.travel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bp22intel.edgesentinel.travel.AdvicePriority
import com.bp22intel.edgesentinel.travel.ChecklistCategory
import com.bp22intel.edgesentinel.travel.ChecklistItem
import com.bp22intel.edgesentinel.travel.CountryThreatProfile
import com.bp22intel.edgesentinel.travel.CountryThreatProfiles
import com.bp22intel.edgesentinel.travel.SecurityAdvice
import com.bp22intel.edgesentinel.travel.TravelAdvisor
import com.bp22intel.edgesentinel.travel.TravelModeState
import com.bp22intel.edgesentinel.travel.TravelState
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
import com.bp22intel.edgesentinel.ui.theme.TextTertiary

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TravelModeScreen(
    travelState: TravelModeState = TravelModeState(),
    checkedItems: Set<String> = emptySet(),
    onBack: () -> Unit = {},
    onActivate: () -> Unit = {},
    onDeactivate: () -> Unit = {},
    onExportData: (passphrase: String) -> Unit = {},
    onWipeTravelData: () -> Unit = {},
    onPanicWipe: () -> Unit = {},
    onChecklistItemToggle: (String, Boolean) -> Unit = { _, _ -> }
) {
    var showPanicConfirm by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var showCountryBrief by remember { mutableStateOf(false) }

    val isActive = travelState.state != TravelState.INACTIVE
    val profile = travelState.threatProfile

    if (showPanicConfirm) {
        PanicWipeConfirmDialog(
            onConfirm = {
                showPanicConfirm = false
                onPanicWipe()
            },
            onDismiss = { showPanicConfirm = false }
        )
    }

    if (showExportDialog) {
        ExportDialog(
            onExport = { passphrase ->
                showExportDialog = false
                onExportData(passphrase)
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showWipeConfirm) {
        WipeConfirmDialog(
            onConfirm = {
                showWipeConfirm = false
                onWipeTravelData()
            },
            onDismiss = { showWipeConfirm = false }
        )
    }

    if (showCountryBrief && profile != null) {
        CountryBriefDialog(
            profile = profile,
            onDismiss = { showCountryBrief = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Travel Mode") },
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
        containerColor = BackgroundPrimary
    ) { innerPadding ->
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Travel Mode Toggle
        item {
            SectionHeader(title = "Travel Mode")
        }

        item {
            TravelModeToggleCard(
                state = travelState,
                onToggle = { enabled ->
                    if (enabled) onActivate() else onDeactivate()
                }
            )
        }

        // Country & Threat Profile (visible when active)
        if (isActive && profile != null) {
            item {
                SectionHeader(title = "Current Location")
            }

            item {
                CountryThreatCard(
                    profile = profile,
                    state = travelState.state,
                    onViewBrief = { showCountryBrief = true }
                )
            }

            // Risk Level
            item {
                RiskLevelIndicator(riskLevel = profile.riskLevel)
            }

            // Primary Threats
            item {
                SectionHeader(title = "Primary Threats")
            }

            items(profile.primaryThreats) { threat ->
                ThreatItem(threat = threat)
            }

            // Security Advice
            item {
                SectionHeader(title = "Security Advice")
            }

            items(TravelAdvisor.getEntryBriefing(profile)) { item ->
                AdviceCard(advice = item)
            }
        }

        // Pre-departure checklist
        item {
            SectionHeader(title = "Pre-Departure Checklist")
        }

        item {
            ChecklistSection(
                items = TravelAdvisor.getPreDepartureChecklist(),
                category = ChecklistCategory.PRE_DEPARTURE,
                checkedItems = checkedItems,
                onItemToggle = onChecklistItemToggle
            )
        }

        // Post-departure checklist
        item {
            SectionHeader(title = "Post-Departure Checklist")
        }

        item {
            ChecklistSection(
                items = TravelAdvisor.getPostDepartureChecklist(),
                category = ChecklistCategory.POST_DEPARTURE,
                checkedItems = checkedItems,
                onItemToggle = onChecklistItemToggle
            )
        }

        // Data Management
        item {
            SectionHeader(title = "Data Management")
        }

        item {
            Button(
                onClick = { showExportDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Travel Data (Encrypted)")
            }
        }

        item {
            Button(
                onClick = { showWipeConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = StatusSuspicious
                )
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Wipe Travel Data")
            }
        }

        // Panic Wipe
        item {
            SectionHeader(title = "Emergency")
        }

        item {
            Button(
                onClick = { showPanicConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusThreat,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("PANIC WIPE — Erase All Data", fontWeight = FontWeight.Bold)
            }
        }

        // Bottom spacer
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
    } // end Scaffold
}

@Composable
private fun TravelModeToggleCard(
    state: TravelModeState,
    onToggle: (Boolean) -> Unit
) {
    val isActive = state.state != TravelState.INACTIVE
    val statusText = when (state.state) {
        TravelState.INACTIVE -> "Inactive"
        TravelState.LEARNING -> "Learning (first 24h — heightened sensitivity)"
        TravelState.ACTIVE -> "Active"
        TravelState.EXITING -> "Exiting — review data before leaving"
    }
    val statusColor = when (state.state) {
        TravelState.INACTIVE -> TextTertiary
        TravelState.LEARNING -> StatusSuspicious
        TravelState.ACTIVE -> StatusClear
        TravelState.EXITING -> AccentBlue
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isActive) "Travel Mode Active" else "Enter Travel Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
            Switch(
                checked = isActive,
                onCheckedChange = onToggle,
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

@Composable
private fun CountryThreatCard(
    profile: CountryThreatProfile,
    state: TravelState,
    onViewBrief: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.flagEmoji,
                    fontSize = 32.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = profile.countryName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "MCC: ${profile.mcc} · Risk Level: ${profile.riskLevel}/5",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = profile.advisoryText,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onViewBrief) {
                Text("View Full Briefing", color = AccentBlue)
            }
        }
    }
}

@Composable
private fun RiskLevelIndicator(riskLevel: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Risk Level",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(5) { index ->
                    val filled = index < riskLevel
                    val color = when {
                        !filled -> TextTertiary
                        riskLevel <= 2 -> StatusClear
                        riskLevel <= 3 -> StatusSuspicious
                        else -> StatusThreat
                    }
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                val label = when (riskLevel) {
                    1 -> "Low"
                    2 -> "Moderate"
                    3 -> "Elevated"
                    4 -> "High"
                    5 -> "Critical"
                    else -> "Unknown"
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        riskLevel <= 2 -> StatusClear
                        riskLevel <= 3 -> StatusSuspicious
                        else -> StatusThreat
                    },
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}

@Composable
private fun ThreatItem(threat: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = StatusSuspicious,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = threat,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun AdviceCard(advice: SecurityAdvice) {
    val priorityColor = when (advice.priority) {
        AdvicePriority.CRITICAL -> StatusThreat
        AdvicePriority.HIGH -> StatusSuspicious
        AdvicePriority.MEDIUM -> AccentBlue
        AdvicePriority.LOW -> StatusClear
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = priorityColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = advice.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = priorityColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = advice.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 28.dp)
            )
        }
    }
}

@Composable
private fun ChecklistSection(
    items: List<ChecklistItem>,
    category: ChecklistCategory,
    checkedItems: Set<String> = emptySet(),
    onItemToggle: (String, Boolean) -> Unit = { _, _ -> }
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            items.forEach { item ->
                val isChecked = item.id in checkedItems
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onItemToggle(item.id, it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentBlue,
                            uncheckedColor = TextSecondary,
                            checkmarkColor = TextPrimary
                        )
                    )
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isChecked) TextSecondary else TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun PanicWipeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                "PANIC WIPE",
                color = StatusThreat,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "This will permanently and irreversibly erase ALL Edge Sentinel data " +
                    "including alerts, scan history, baselines, settings, and travel data.\n\n" +
                    "This cannot be undone.",
                color = TextPrimary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusThreat,
                    contentColor = TextPrimary
                )
            ) {
                Text("ERASE EVERYTHING", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ExportDialog(
    onExport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text("Export Travel Data", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Data will be exported as AES-256-GCM encrypted JSON. " +
                        "Enter a strong passphrase to protect the export.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextTertiary,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassphrase,
                    onValueChange = { confirmPassphrase = it },
                    label = { Text("Confirm Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextTertiary,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                if (passphrase.isNotEmpty() && confirmPassphrase.isNotEmpty() &&
                    passphrase != confirmPassphrase
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Passphrases do not match",
                        color = StatusThreat,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onExport(passphrase) },
                enabled = passphrase.length >= 8 && passphrase == confirmPassphrase,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = TextPrimary
                )
            ) {
                Text("Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun WipeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text("Wipe Travel Data", color = StatusSuspicious, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                "This will erase all baselines, alert history, and scan data collected " +
                    "during this trip. Settings and app configuration will be preserved.\n\n" +
                    "Consider exporting data first for post-trip analysis.",
                color = TextPrimary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusSuspicious,
                    contentColor = TextPrimary
                )
            ) {
                Text("Wipe Travel Data")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
