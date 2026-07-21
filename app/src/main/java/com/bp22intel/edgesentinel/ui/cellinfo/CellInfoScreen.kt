/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.cellinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.ui.components.CellInfoCard
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CellInfoScreen(
    onBack: () -> Unit = {},
    viewModel: CellInfoViewModel = hiltViewModel()
) {
    val currentCell by viewModel.currentCell.collectAsState()
    val allKnownCells by viewModel.allKnownCells.collectAsState()
    val networkTypeHistory by viewModel.networkTypeHistory.collectAsState()

    val uniqueLacs = allKnownCells.map { it.lacTac }.distinct().size
    val networkTypesSeen = allKnownCells.map { it.networkType }.distinct()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cell Tower Info") },
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
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Current serving cell
        item {
            SectionHeader(title = "Current Serving Cell")
        }

        item {
            val cell = currentCell
            if (cell != null) {
                CellInfoCard(cellTower = cell)
            } else {
                Text(
                    text = "No cell information available. Grant location permission to view cell data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // Neighbor cells section
        item {
            SectionHeader(title = "Neighbor Cells")
        }

        val neighborCells = allKnownCells.filter { cell ->
            currentCell?.let { serving -> cell.cid != serving.cid } ?: true
        }.take(10)

        if (neighborCells.isEmpty()) {
            item {
                Text(
                    text = "No neighbor cells detected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        } else {
            items(neighborCells, key = { it.id }) { cell ->
                CellInfoCard(cellTower = cell)
            }
        }

        // Known cell database stats
        item {
            SectionHeader(title = "Known Cell Database")
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatRow(label = "Total cells tracked", value = "${allKnownCells.size}")
                    StatRow(label = "Unique LACs/TACs", value = "$uniqueLacs")
                    StatRow(
                        label = "Network types seen",
                        value = if (networkTypesSeen.isEmpty()) {
                            "None"
                        } else {
                            networkTypesSeen.joinToString(", ") { it.generation }
                        }
                    )
                }
            }
        }

        // Network type history
        item {
            SectionHeader(title = "Recent Network Changes")
        }

        if (networkTypeHistory.isEmpty()) {
            item {
                Text(
                    text = "No network changes recorded yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        } else {
            items(networkTypeHistory) { (timestamp, networkType) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "${networkType.generation} (${networkType.name})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = AccentBlue
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String
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
            color = TextPrimary
        )
    }
}
