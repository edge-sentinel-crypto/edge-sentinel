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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.analysis.AlertAnalysis
import com.bp22intel.edgesentinel.analysis.FilterRecommendation
import com.bp22intel.edgesentinel.analysis.RiskLevel
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import com.bp22intel.edgesentinel.ui.components.AlertLocationMap
import com.bp22intel.edgesentinel.ui.components.ConfidenceRing
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    alertId: Long,
    onBack: () -> Unit = {},
    viewModel: AlertDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = BackgroundPrimary,
        topBar = {
            TopAppBar(
                title = { Text("Alert Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundPrimary,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentBlue)
            }
        }
        uiState.alert == null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Alert not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            }
        }
        else -> {
            val alert = uiState.alert!!
            val analysis = uiState.analysis!!

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Alert type + severity badge (large)
                AlertHeader(alert = alert)

                // 2. Learning status (if system has prior knowledge)
                LearningStatusCard(filterRecommendation = uiState.filterRecommendation)

                // 3. AI Analysis
                AnalysisCard(analysis = analysis)

                // 4. Tower details
                TowerDetailsCard(detailsJson = alert.detailsJson)

                // 5. Possible causes
                PossibleCausesCard(causes = analysis.possibleCauses)

                // 6. Recommended actions
                RecommendationCard(recommendation = analysis.recommendation)

                // 7. Timestamp + duration
                TimestampCard(alert = alert)

                // 8. Location map — live-updating tower position from continuous tracker
                AlertLocationMapSection(
                    alert = alert,
                    towerLat = uiState.towerLatitude,
                    towerLon = uiState.towerLongitude,
                    towerAccuracyM = uiState.towerAccuracyMeters,
                    towerReadingCount = uiState.towerReadingCount,
                    towerConfidenceScore = uiState.towerConfidenceScore,
                    currentUserLat = uiState.userLatitude,
                    currentUserLon = uiState.userLongitude
                )

                // 9. Action buttons (acknowledge + share)
                ActionButtons(
                    isAcknowledged = uiState.isAcknowledged,
                    onAcknowledge = { viewModel.acknowledgeAlert() },
                    onShare = { shareAlertToClipboard(context, alert, analysis) }
                )

                // 9. Feedback buttons
                // Detect if this is a WiFi alert for context-aware button labels
                val isWifiAlert = try {
                    val dj = JSONObject(alert.detailsJson)
                    dj.has("ssid") || dj.has("bssid")
                } catch (_: Exception) { false }

                FeedbackSection(
                    feedbackGiven = uiState.feedbackGiven,
                    feedbackConfirmation = uiState.feedbackConfirmation,
                    isWifiAlert = isWifiAlert,
                    onFeedback = { feedback -> viewModel.submitFeedback(feedback) }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    } // end Scaffold
}

@Composable
private fun AlertHeader(alert: Alert) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Large confidence ring
            ConfidenceRing(
                confidence = alert.confidence,
                size = 64.dp,
                strokeWidth = 5.dp,
                ringColor = severityColor(alert.severity)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = threatTypeLabel(alert.threatType),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SeverityBadge(
                        text = alert.severity.label.uppercase(),
                        color = severityColor(alert.severity)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Detection confidence: ${alert.confidence.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }

            // Threat type icon
            Icon(
                imageVector = threatTypeIcon(alert.threatType),
                contentDescription = null,
                tint = severityColor(alert.severity).copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun SeverityBadge(text: String, color: Color) {
    Text(
        text = text,
        color = BackgroundPrimary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
private fun AnalysisCard(analysis: AlertAnalysis) {
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
            Text(
                text = "AI Analysis",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            // Plain English explanation
            Text(
                text = analysis.plainEnglish,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )

            // Risk level + confidence row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = "Risk Level",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = analysis.riskLevel.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(analysis.riskLevel.color)
                    )
                }
                Column {
                    Text(
                        text = "Confidence",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "${(analysis.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            // Graduated assessment (replaces binary "Should Worry")
            val (assessmentLabel, assessmentColor, assessmentExplanation) = when {
                analysis.riskLevel == RiskLevel.HIGH || analysis.riskLevel == RiskLevel.CRITICAL ->
                    Triple(
                        "Take action",
                        StatusThreat,
                        "High confidence detection with significant risk — review and respond."
                    )
                analysis.riskLevel == RiskLevel.MEDIUM ->
                    Triple(
                        "Worth monitoring",
                        StatusSuspicious,
                        "Something unusual but not yet confirmed — keep an eye on it."
                    )
                else ->
                    Triple(
                        "No action needed",
                        StatusClear,
                        "Low risk detection — likely normal network behavior."
                    )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(assessmentColor.copy(alpha = 0.1f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = when {
                        analysis.riskLevel == RiskLevel.HIGH || analysis.riskLevel == RiskLevel.CRITICAL -> Icons.Default.Warning
                        analysis.riskLevel == RiskLevel.MEDIUM -> Icons.Default.GppMaybe
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = assessmentColor,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = assessmentLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = assessmentColor
                    )
                    Text(
                        text = assessmentExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TowerDetailsCard(detailsJson: String) {
    val details = try { JSONObject(detailsJson) } catch (_: Exception) { return }
    if (details.length() == 0) return

    val chips = mutableListOf<Pair<String, String>>()

    if (details.has("cellId")) chips.add("CID" to details.optString("cellId", ""))
    if (details.has("lac")) chips.add("LAC" to details.optString("lac", ""))
    if (details.has("mcc") && details.has("mnc")) {
        chips.add("MCC/MNC" to "${details.optInt("mcc")}/${details.optInt("mnc")}")
    }
    if (details.has("signalStrength")) {
        val sig = details.optInt("signalStrength", 0)
        if (sig != 0) chips.add("Signal" to "$sig dBm")
    }
    if (details.has("networkType")) chips.add("Network" to details.optString("networkType", ""))
    if (details.has("nearbyTowerCount")) {
        chips.add("Nearby Towers" to "${details.optInt("nearbyTowerCount")}")
    }
    if (details.has("ssid")) chips.add("SSID" to details.optString("ssid", ""))
    if (details.has("bssid")) chips.add("BSSID" to details.optString("bssid", ""))
    if (details.has("fromNetwork") && details.has("toNetwork")) {
        chips.add("Downgrade" to "${details.optString("fromNetwork")} → ${details.optString("toNetwork")}")
    }
    if (details.has("cipher")) chips.add("Cipher" to details.optString("cipher", ""))
    if (details.has("previousCipher")) chips.add("Previous Cipher" to details.optString("previousCipher", ""))

    if (chips.isEmpty()) return

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
            Text(
                text = "Tower Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.forEach { (label, value) ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = "$label: $value",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.heightIn(min = 28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PossibleCausesCard(causes: List<String>) {
    if (causes.isEmpty()) return

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Possible Causes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            causes.forEachIndexed { index, cause ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentBlue,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(
                        text = cause,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recommended Action",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = recommendation,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun TimestampCard(alert: Alert) {
    val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm:ss a", Locale.getDefault())
    val formattedTime = dateFormat.format(Date(alert.timestamp))
    val elapsed = System.currentTimeMillis() - alert.timestamp
    val elapsedText = formatDuration(elapsed)

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
                text = "Timestamp",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "$elapsedText ago",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ActionButtons(
    isAcknowledged: Boolean,
    onAcknowledge: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onAcknowledge,
            enabled = !isAcknowledged,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isAcknowledged) StatusClear.copy(alpha = 0.3f) else AccentBlue,
                disabledContainerColor = StatusClear.copy(alpha = 0.2f),
                disabledContentColor = StatusClear
            )
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isAcknowledged) "Acknowledged" else "Acknowledge"
            )
        }

        OutlinedButton(
            onClick = onShare,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Share",
                color = TextPrimary
            )
        }
    }
}

// ── Learning Status Card ───────────────────────────────────────────────

@Composable
private fun LearningStatusCard(filterRecommendation: FilterRecommendation?) {
    val notes = filterRecommendation?.learningNotes ?: return
    if (notes.isEmpty()) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = AccentBlue.copy(alpha = 0.1f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Learning",
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Learning System",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue
                )
            }
            notes.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

// ── Feedback Section ──────────────────────────────────────────────────

@Composable
private fun FeedbackSection(
    feedbackGiven: String?,
    feedbackConfirmation: String?,
    isWifiAlert: Boolean = false,
    onFeedback: (String) -> Unit
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
            Text(
                text = "Was this a real threat?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Your feedback helps Edge Sentinel learn and reduce false alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Show confirmation if feedback was just given.
            AnimatedVisibility(
                visible = feedbackConfirmation != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (feedbackConfirmation != null) {
                    Text(
                        text = feedbackConfirmation,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = StatusClear,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StatusClear.copy(alpha = 0.1f))
                            .padding(12.dp)
                    )
                }
            }

            if (feedbackGiven == null) {
                // ── No feedback yet — show the four buttons ───────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // "False Alarm" (was "Not a Threat") — outlined
                    OutlinedButton(
                        onClick = { onFeedback("FALSE_POSITIVE") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = StatusClear,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "False Alarm",
                                    color = StatusClear,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                text = "Detection was incorrect",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    // "Real Threat" — filled red
                    Button(
                        onClick = { onFeedback("CONFIRMED_THREAT") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusThreat
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Real Threat",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                text = "I believe this is real",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // My Device + Not Sure row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onFeedback("KNOWN_DEVICE") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isWifiAlert) Icons.Default.Wifi else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isWifiAlert) "My Network" else "My Device",
                                    color = AccentBlue,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                text = "Correct detection, I trust it",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    TextButton(
                        onClick = { onFeedback("UNSURE") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.HelpOutline,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Not Sure",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                text = "I don't know",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                // ── Feedback already given — show what they chose ──────
                val (label, color) = when (feedbackGiven) {
                    "FALSE_POSITIVE" -> "Marked as False Alarm" to StatusClear
                    "CONFIRMED_THREAT" -> "Confirmed as Real Threat" to StatusThreat
                    "KNOWN_DEVICE" -> if (isWifiAlert) "My Network (trusted)" to AccentBlue
                        else "My Device (trusted)" to AccentBlue
                    else -> "Marked as Unsure" to TextSecondary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.1f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = color
                    )
                }
            }
        }
    }
}

// ── Helper functions ──────────────────────────────────────────────────

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
        ThreatType.FAKE_BTS -> "Fake Base Station"
        ThreatType.NETWORK_DOWNGRADE -> "Network Downgrade"
        ThreatType.SILENT_SMS -> "Silent SMS"
        ThreatType.TRACKING_PATTERN -> "Tracking Pattern"
        ThreatType.CIPHER_ANOMALY -> "Cipher Anomaly"
        ThreatType.SIGNAL_ANOMALY -> "Signal Anomaly"
        ThreatType.NR_ANOMALY -> "5G NR Anomaly"
        ThreatType.REGISTRATION_FAILURE -> "Authentication Failure"
        ThreatType.TEMPORAL_ANOMALY -> "Temporal Anomaly"
        ThreatType.KNOWN_TOWER_ANOMALY -> "Known Tower Anomaly"
        ThreatType.COMPOUND_PATTERN -> "Compound Attack Pattern"
    }
}

private fun severityColor(level: ThreatLevel): Color {
    return when (level) {
        ThreatLevel.CLEAR -> StatusClear
        ThreatLevel.SUSPICIOUS -> StatusSuspicious
        ThreatLevel.THREAT -> StatusThreat
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "$days day${if (days != 1L) "s" else ""}, ${hours % 24} hr"
        hours > 0 -> "$hours hour${if (hours != 1L) "s" else ""}, ${minutes % 60} min"
        minutes > 0 -> "$minutes minute${if (minutes != 1L) "s" else ""}"
        else -> "Less than a minute"
    }
}

@Composable
private fun AlertLocationMapSection(
    alert: Alert,
    towerLat: Double? = null,
    towerLon: Double? = null,
    towerAccuracyM: Double? = null,
    towerReadingCount: Int = 0,
    towerConfidenceScore: Float = 0f,
    currentUserLat: Double? = null,
    currentUserLon: Double? = null
) {
    val details = try { JSONObject(alert.detailsJson) } catch (_: Exception) { JSONObject() }

    // Priority: 1) Live estimated position (from ViewModel/tracker), 2) alert JSON fields
    val lat = towerLat
        ?: details.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() }
        ?: details.optDouble("towerLatitude", Double.NaN).takeIf { !it.isNaN() }
    val lng = towerLon
        ?: details.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() }
        ?: details.optDouble("towerLongitude", Double.NaN).takeIf { !it.isNaN() }

    if (lat == null || lng == null) {
        // Tower not in DB — fall back to user's GPS location as approximate tower position
        val fallbackLat = details.optDouble("userLatitude", Double.NaN).takeIf { !it.isNaN() }
            ?: currentUserLat
        val fallbackLng = details.optDouble("userLongitude", Double.NaN).takeIf { !it.isNaN() }
            ?: currentUserLon
        if (fallbackLat != null && fallbackLng != null) {
            val cidStr = details.optString("cellId", details.optString("cid", ""))
            AlertLocationMap(
                latitude = fallbackLat,
                longitude = fallbackLng,
                accuracyM = 500.0,
                label = if (cidStr.isNotEmpty()) "Approximate — CID $cidStr (not in tower DB)" else "Approximate Location"
            )
            return
        }
        // No location data at all — show placeholder
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Tower Location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Location unavailable. Tower not in database and no GPS data recorded with this alert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    // User location (if stored with the alert, or current live position)
    val userLat = currentUserLat
        ?: details.optDouble("userLatitude", Double.NaN).takeIf { !it.isNaN() }
    val userLng = currentUserLon
        ?: details.optDouble("userLongitude", Double.NaN).takeIf { !it.isNaN() }

    // Use tracker accuracy if available, otherwise fall back to heuristic
    val accuracy = towerAccuracyM ?: details.optDouble("accuracyMeters", Double.NaN).let {
        if (!it.isNaN()) it else when (alert.threatType) {
            ThreatType.FAKE_BTS -> 300.0
            ThreatType.SIGNAL_ANOMALY -> 500.0
            ThreatType.KNOWN_TOWER_ANOMALY -> 250.0
            ThreatType.COMPOUND_PATTERN -> 200.0
            else -> 400.0
        }
    }

    val cid = details.optString("cellId", details.optString("cid", ""))
    val label = if (cid.isNotEmpty()) {
        "${threatTypeLabel(alert.threatType)} — CID $cid"
    } else {
        threatTypeLabel(alert.threatType)
    }

    AlertLocationMap(
        latitude = lat,
        longitude = lng,
        accuracyM = accuracy,
        readingCount = towerReadingCount,
        confidenceScore = towerConfidenceScore,
        label = label,
        userLat = userLat,
        userLng = userLng
    )
}

private fun shareAlertToClipboard(context: Context, alert: Alert, analysis: AlertAnalysis) {
    val text = buildString {
        appendLine("⚠️ Edge Sentinel Alert")
        appendLine("Type: ${threatTypeLabel(alert.threatType)}")
        appendLine("Severity: ${alert.severity.label}")
        appendLine("Risk Level: ${analysis.riskLevel.label}")
        appendLine()
        appendLine("Analysis:")
        appendLine(analysis.plainEnglish)
        appendLine()
        appendLine("Recommendation:")
        appendLine(analysis.recommendation)
        appendLine()
        appendLine("Confidence: ${(analysis.confidence * 100).toInt()}%")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(alert.timestamp))}")
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Edge Sentinel Alert", text))
    Toast.makeText(context, "Alert copied to clipboard", Toast.LENGTH_SHORT).show()
}
