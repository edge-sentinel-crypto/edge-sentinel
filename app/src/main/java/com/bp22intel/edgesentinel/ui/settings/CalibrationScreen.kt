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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

@Composable
fun CalibrationScreen(
    onBack: () -> Unit = {},
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val isCalibrating by viewModel.isCalibrating.collectAsState()
    val sampleCount by viewModel.sampleCount.collectAsState()
    val calibrationResults: com.bp22intel.edgesentinel.calibration.CalibrationService.CalibrationResults? by viewModel.calibrationResults.collectAsState()
    val showInstructions: Boolean by viewModel.showInstructions.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.exportMessage.collectLatest { message ->
            // In a real implementation, this would show a snackbar or toast
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration") },
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
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            SectionHeader(title = "Calibration Mode")
        }

        item {
            CalibrationStatusCard(
                isCalibrating = isCalibrating,
                sampleCount = sampleCount,
                calibrationResults = calibrationResults
            )
        }

        item {
            CalibrationControlCard(
                isCalibrating = isCalibrating,
                onStartCalibration = { viewModel.startCalibration() },
                onStopCalibration = { viewModel.stopCalibration() }
            )
        }

        if (showInstructions) {
            item {
                InstructionsCard(
                    onDismiss = { viewModel.dismissInstructions() }
                )
            }
        }

        calibrationResults?.let { results ->
            item {
                CalibrationResultsCard(
                    results = results,
                    onApplyCalibration = { viewModel.applyCalibrationResults() },
                    onExportData = { viewModel.exportCalibrationData() }
                )
            }
        }
    }
    }
}

@Composable
private fun CalibrationStatusCard(
    isCalibrating: Boolean,
    sampleCount: Int,
    calibrationResults: com.bp22intel.edgesentinel.calibration.CalibrationService.CalibrationResults?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (statusText, statusColor) = when {
                    calibrationResults != null -> "Complete" to StatusClear
                    isCalibrating -> "Running" to AccentBlue
                    else -> "Idle" to TextSecondary
                }
                
                Text(
                    text = "State: $statusText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }
            
            if (isCalibrating) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Samples collected: $sampleCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                
                Text(
                    text = "Duration: ${if (sampleCount > 0) "${(sampleCount * 5 / 60.0).roundToInt()} min" else "0 min"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CalibrationControlCard(
    isCalibrating: Boolean,
    onStartCalibration: () -> Unit,
    onStopCalibration: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Control",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (!isCalibrating) {
                Button(
                    onClick = onStartCalibration,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusClear)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Start Calibration")
                }
            } else {
                Button(
                    onClick = onStopCalibration,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDangerous)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Stop Calibration")
                }
            }
        }
    }
}

@Composable
private fun InstructionsCard(
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Instructions",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val instructions = listOf(
                "Walk around your area for 5-10 minutes",
                "Stay outdoors for best GPS accuracy",
                "The app records signal strengths and GPS positions",
                "Results improve your threat geolocation accuracy"
            )
            
            instructions.forEach { instruction ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "• ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentBlue
                    )
                    Text(
                        text = instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationResultsCard(
    results: com.bp22intel.edgesentinel.calibration.CalibrationService.CalibrationResults,
    onApplyCalibration: () -> Unit,
    onExportData: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Results",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            ResultRow("Total samples", "${results.totalSamples}")
            ResultRow("Duration", "${results.durationMinutes.roundToInt()} minutes")
            ResultRow("Distance traveled", "${results.distanceTraveledMeters.roundToInt()} meters")
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApplyCalibration,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Apply")
                }
                
                OutlinedButton(
                    onClick = onExportData,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Export")
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
            color = TextPrimary
        )
    }
}