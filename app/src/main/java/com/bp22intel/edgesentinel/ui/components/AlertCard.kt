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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.EdgeSentinelTheme
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary

private fun threatTypeIcon(type: ThreatType): ImageVector {
    return when (type) {
        ThreatType.FAKE_BTS -> Icons.Filled.SignalCellular4Bar
        ThreatType.NETWORK_DOWNGRADE -> Icons.Filled.NetworkCheck
        ThreatType.SILENT_SMS -> Icons.Filled.Sms
        ThreatType.TRACKING_PATTERN -> Icons.Filled.TrackChanges
        ThreatType.CIPHER_ANOMALY -> Icons.Filled.GppBad
        ThreatType.SIGNAL_ANOMALY -> Icons.Filled.GppMaybe
        ThreatType.NR_ANOMALY -> Icons.Filled.NetworkCheck
        ThreatType.REGISTRATION_FAILURE -> Icons.Filled.GppBad
        ThreatType.TEMPORAL_ANOMALY -> Icons.Filled.TrackChanges
        ThreatType.KNOWN_TOWER_ANOMALY -> Icons.Filled.GppMaybe
        ThreatType.COMPOUND_PATTERN -> Icons.Filled.Warning
    }
}

private fun threatTypeLabel(type: ThreatType): String {
    return when (type) {
        ThreatType.FAKE_BTS -> "Fake BTS"
        ThreatType.NETWORK_DOWNGRADE -> "Downgrade"
        ThreatType.SILENT_SMS -> "Silent SMS"
        ThreatType.TRACKING_PATTERN -> "Tracking"
        ThreatType.CIPHER_ANOMALY -> "Cipher"
        ThreatType.SIGNAL_ANOMALY -> "Signal"
        ThreatType.NR_ANOMALY -> "5G NR"
        ThreatType.REGISTRATION_FAILURE -> "Auth Fail"
        ThreatType.TEMPORAL_ANOMALY -> "Temporal"
        ThreatType.KNOWN_TOWER_ANOMALY -> "Tower Clone"
        ThreatType.COMPOUND_PATTERN -> "Compound"
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - timestampMs
    val diffSec = diffMs / 1000
    val diffMin = diffSec / 60
    val diffHour = diffMin / 60
    val diffDay = diffHour / 24

    return when {
        diffSec < 60 -> "just now"
        diffMin < 60 -> "${diffMin} min ago"
        diffHour < 24 -> "${diffHour} hour${if (diffHour != 1L) "s" else ""} ago"
        diffDay < 7 -> "${diffDay} day${if (diffDay != 1L) "s" else ""} ago"
        else -> "${diffDay / 7} week${if (diffDay / 7 != 1L) "s" else ""} ago"
    }
}

@Composable
fun AlertCard(
    alert: Alert,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onTrustDevice: ((Long) -> Unit)? = null
) {
    // Slide-in animation when card first appears
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Trust confirmation dialog state
    var showTrustDialog by remember { mutableStateOf(false) }

    // Determine entity label for trust dialog
    val trustEntityLabel = remember(alert.detailsJson) {
        try {
            val dj = org.json.JSONObject(alert.detailsJson)
            when {
                dj.has("ssid") -> "network \"${dj.optString("ssid", "")}\""
                dj.has("cellId") -> "tower CID ${dj.optString("cellId", "")}"
                else -> "device"
            }
        } catch (_: Exception) { "device" }
    }

    // Trust confirmation dialog
    if (showTrustDialog && onTrustDevice != null) {
        AlertDialog(
            onDismissRequest = { showTrustDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = AccentBlue
                )
            },
            title = { Text("Trust this $trustEntityLabel?") },
            text = {
                Text(
                    text = "Future alerts from this $trustEntityLabel will be suppressed.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTrustDialog = false
                    onTrustDevice(alert.id)
                }) {
                    Text("Trust", color = AccentBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrustDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it / 3 }) + fadeIn()
    ) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = MaterialTheme.shapes.medium
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Confidence ring on the left
                    ConfidenceRing(
                        confidence = alert.confidence,
                        size = 44.dp,
                        strokeWidth = 3.dp,
                        ringColor = when (alert.severity) {
                            ThreatLevel.CLEAR -> StatusClear
                            ThreatLevel.SUSPICIOUS -> StatusSuspicious
                            ThreatLevel.THREAT -> StatusThreat
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = threatTypeLabel(alert.threatType),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            StatusBadge(
                                text = alert.severity.label.uppercase(),
                                threatLevel = alert.severity
                            )
                        }

                        Text(
                            text = alert.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 2
                        )

                        // Rich details from detailsJson
                        AlertDetailChips(alert.detailsJson)

                        ExplainableText(
                            text = "${threatTypeLabel(alert.threatType)} ${alert.summary}",
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Text(
                            text = formatRelativeTime(alert.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }

                    // Threat type icon on the right
                    Icon(
                        imageVector = threatTypeIcon(alert.threatType),
                        contentDescription = threatTypeLabel(alert.threatType),
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Quick-trust shortcut button (top-right corner)
                if (onTrustDevice != null) {
                    IconButton(
                        onClick = { showTrustDialog = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(32.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = AccentBlue.copy(alpha = 0.6f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Trust this $trustEntityLabel",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AlertDetailChips(detailsJson: String) {
    val details = try { org.json.JSONObject(detailsJson) } catch (_: Exception) { return }

    val chips = mutableListOf<Pair<String, String>>() // label to value

    // Cell tower info
    if (details.has("cellId")) {
        chips.add("CID" to details.optString("cellId", ""))
    }
    if (details.has("lac")) {
        chips.add("LAC" to details.optString("lac", ""))
    }
    if (details.has("mcc") && details.has("mnc")) {
        chips.add("MCC/MNC" to "${details.optInt("mcc")}/${details.optInt("mnc")}")
    }
    if (details.has("signalStrength")) {
        val sig = details.optInt("signalStrength", 0)
        if (sig != 0) chips.add("Signal" to "${sig} dBm")
    }
    if (details.has("networkType")) {
        chips.add("Network" to details.optString("networkType", ""))
    }
    if (details.has("nearbyTowerCount")) {
        chips.add("Nearby" to "${details.optInt("nearbyTowerCount")} towers")
    }
    // WiFi details
    if (details.has("ssid")) {
        chips.add("SSID" to details.optString("ssid", ""))
    }
    if (details.has("bssid")) {
        chips.add("BSSID" to details.optString("bssid", ""))
    }
    // Downgrade details
    if (details.has("fromNetwork") && details.has("toNetwork")) {
        chips.add("Downgrade" to "${details.optString("fromNetwork")} \u2192 ${details.optString("toNetwork")}")
    }
    // Cipher
    if (details.has("cipher")) {
        chips.add("Cipher" to details.optString("cipher", ""))
    }

    if (chips.isEmpty()) return

    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        chips.forEach { (label, value) ->
            androidx.compose.material3.SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        text = "$label: $value",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                },
                modifier = Modifier.heightIn(min = 24.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun AlertCardPreview() {
    EdgeSentinelTheme {
        AlertCard(
            alert = Alert(
                id = 1,
                timestamp = System.currentTimeMillis() - 300_000,
                threatType = ThreatType.FAKE_BTS,
                severity = ThreatLevel.THREAT,
                confidence = Confidence.HIGH,
                summary = "Suspicious base station detected with unusual signal pattern",
                detailsJson = "{}",
                cellId = 12345
            )
        )
    }
}
