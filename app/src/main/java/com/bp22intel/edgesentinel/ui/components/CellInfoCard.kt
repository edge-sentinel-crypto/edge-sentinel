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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.data.local.entity.EstimatedTowerPositionEntity
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.EdgeSentinelTheme
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary

@Composable
fun CellInfoCard(
    cellTower: CellTower,
    modifier: Modifier = Modifier
) {
    val viewModel: CellInfoCardViewModel = hiltViewModel()
    var knownTowerEntity by remember { mutableStateOf<KnownTowerEntity?>(null) }
    var estimatedPosition by remember { mutableStateOf<EstimatedTowerPositionEntity?>(null) }
    var showTowerLocationSheet by remember { mutableStateOf(false) }

    LaunchedEffect(cellTower.cid, cellTower.lacTac, cellTower.mcc, cellTower.mnc) {
        knownTowerEntity = viewModel.towerDatabaseManager.lookupTower(
            mcc = cellTower.mcc,
            mnc = cellTower.mnc,
            lac = cellTower.lacTac,
            cid = cellTower.cid
        )
        estimatedPosition = viewModel.towerPositionTracker.getEstimatedPosition(
            mcc = cellTower.mcc,
            mnc = cellTower.mnc,
            lac = cellTower.lacTac,
            cid = cellTower.cid
        )
    }

    val hasTowerLocation = knownTowerEntity != null || estimatedPosition != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hasTowerLocation) {
                    Modifier.clickable { showTowerLocationSheet = true }
                } else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top row: Cell ID + Network generation badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CID: ${cellTower.cid}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SignalStrengthBar(dBm = cellTower.signalStrength)

                    Text(
                        text = cellTower.networkType.generation,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentBlue.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Details row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CellInfoItem(label = "LAC/TAC", value = "${cellTower.lacTac}")
                CellInfoItem(label = "MCC", value = "${cellTower.mcc}")
                CellInfoItem(label = "MNC", value = "${cellTower.mnc}")
                CellInfoItem(label = "Signal", value = "${cellTower.signalStrength} dBm")
            }

            // Estimated position info when available from continuous tracker
            if (estimatedPosition != null && knownTowerEntity == null) {
                val ep = estimatedPosition!!
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = AccentBlue.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Est. position: ±${ep.accuracyMeters.toInt()}m (${ep.readingCount} readings)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue.copy(alpha = 0.7f)
                    )
                }
            } else if (hasTowerLocation) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = AccentBlue.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Tap to view tower on map",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue.copy(alpha = 0.7f)
                    )
                }
            }

            ExplainableText(
                text = "LAC TAC MCC MNC CID",
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    // Tower Location Bottom Sheet
    if (showTowerLocationSheet && knownTowerEntity != null) {
        TowerLocationSheet(
            towerInfo = TowerLocationInfo(
                cellTower = cellTower,
                knownTower = knownTowerEntity!!
            ),
            onDismiss = { showTowerLocationSheet = false }
        )
    }
}

@Composable
private fun CellInfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun CellInfoCardPreview() {
    EdgeSentinelTheme {
        CellInfoCard(
            cellTower = CellTower(
                id = 1,
                cid = 48926,
                lacTac = 1234,
                mcc = 310,
                mnc = 260,
                signalStrength = -78,
                networkType = NetworkType.LTE,
                latitude = null,
                longitude = null,
                firstSeen = System.currentTimeMillis() - 3600_000,
                lastSeen = System.currentTimeMillis(),
                timesSeen = 42
            )
        )
    }
}
