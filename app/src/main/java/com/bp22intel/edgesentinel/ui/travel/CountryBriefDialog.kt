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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bp22intel.edgesentinel.travel.AdvicePriority
import com.bp22intel.edgesentinel.travel.CountryThreatProfile
import com.bp22intel.edgesentinel.travel.TravelAdvisor
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import com.bp22intel.edgesentinel.ui.theme.TextTertiary

@Composable
fun CountryBriefDialog(
    profile: CountryThreatProfile,
    onDismiss: () -> Unit
) {
    val advice = remember(profile) { TravelAdvisor.getEntryBriefing(profile) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.flagEmoji,
                    fontSize = 28.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = profile.countryName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Country Briefing",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Risk Level
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Risk Level",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(5) { index ->
                            val filled = index < profile.riskLevel
                            val color = when {
                                !filled -> TextTertiary
                                profile.riskLevel <= 2 -> StatusClear
                                profile.riskLevel <= 3 -> StatusSuspicious
                                else -> StatusThreat
                            }
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = TextTertiary)

                // Advisory
                Text(
                    text = "Advisory",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = profile.advisoryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                HorizontalDivider(color = TextTertiary)

                // Known Threats
                Text(
                    text = "Known Threats",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                profile.primaryThreats.forEach { threat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusSuspicious,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = threat,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }

                HorizontalDivider(color = TextTertiary)

                // Recommended Actions
                Text(
                    text = "Recommended Actions",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                advice.forEach { item ->
                    val iconColor = when (item.priority) {
                        AdvicePriority.CRITICAL -> StatusThreat
                        AdvicePriority.HIGH -> StatusSuspicious
                        AdvicePriority.MEDIUM -> AccentBlue
                        AdvicePriority.LOW -> StatusClear
                    }
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodySmall,
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
                }

                // Recommended settings summary
                HorizontalDivider(color = TextTertiary)

                Text(
                    text = "Recommended Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                val settings = profile.recommendedSettings
                val settingsText = buildList {
                    add("Detection sensitivity: ${settings.detectionSensitivity}")
                    add("Scan interval: ${settings.scanIntervalMinutes} min")
                    if (settings.enableVpnMonitoring) add("VPN monitoring: Enabled")
                    if (settings.enableDnsIntegrityCheck) add("DNS integrity check: Enabled")
                    if (settings.enableImsiCatcherDetection) add("IMSI catcher detection: Enabled")
                    if (settings.enableNetworkDowngradeAlert) add("Network downgrade alerts: Enabled")
                    if (settings.enableSilentSmsDetection) add("Silent SMS detection: Enabled")
                }
                settingsText.forEach { text ->
                    Text(
                        text = "· $text",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = TextPrimary
                )
            ) {
                Text("Acknowledged", fontWeight = FontWeight.Medium)
            }
        }
    )
}
