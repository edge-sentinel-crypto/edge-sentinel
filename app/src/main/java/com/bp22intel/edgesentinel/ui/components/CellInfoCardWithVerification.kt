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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import com.bp22intel.edgesentinel.detection.tower.TowerVerifier
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.EdgeSentinelTheme
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.StatusElevated
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary

@Composable
fun CellInfoCardWithVerification(
    cellTower: CellTower,
    modifier: Modifier = Modifier
) {
    // Create a ViewModel wrapper for TowerVerifier
    val viewModel: CellInfoCardViewModel = hiltViewModel()
    var verificationResult by remember { mutableStateOf<TowerVerifier.VerificationResult?>(null) }
    var knownTowerEntity by remember { mutableStateOf<KnownTowerEntity?>(null) }
    var showTowerLocationSheet by remember { mutableStateOf(false) }

    LaunchedEffect(cellTower.cid, cellTower.lacTac, cellTower.mcc, cellTower.mnc) {
        verificationResult = viewModel.towerVerifier.verifyTower(
            mcc = cellTower.mcc,
            mnc = cellTower.mnc,
            lac = cellTower.lacTac,
            cid = cellTower.cid,
            observedLat = cellTower.latitude,
            observedLng = cellTower.longitude
        )
        // Look up full tower entity for location data
        knownTowerEntity = viewModel.towerDatabaseManager.lookupTower(
            mcc = cellTower.mcc,
            mnc = cellTower.mnc,
            lac = cellTower.lacTac,
            cid = cellTower.cid
        )
    }

    // Determine if we can show the tower on a map
    val hasTowerLocation = knownTowerEntity != null

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
            // Top row: Cell ID + Verification badge + Network generation badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CID: ${cellTower.cid}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    
                    // Tower verification badge
                    verificationResult?.let { result ->
                        TowerVerificationBadge(result)
                    }
                }

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

            // "Tap to view on map" hint when tower location is available
            if (hasTowerLocation) {
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
            
            // Verification details (if there are anomalies)
            verificationResult?.let { result ->
                if (result.anomalies.isNotEmpty()) {
                    Text(
                        text = result.anomalies.first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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
private fun TowerVerificationBadge(result: TowerVerifier.VerificationResult) {
    val (icon, color, text) = when {
        result.isKnown && result.anomalies.isEmpty() -> Triple(
            Icons.Default.Check, 
            StatusClear, 
            "✓ VERIFIED"
        )
        !result.isKnown && result.confidence >= 0.7f -> Triple(
            Icons.Default.Warning, 
            StatusDangerous, 
            "SUSPICIOUS"
        )
        else -> Triple(
            Icons.Default.Help, 
            StatusElevated, 
            "? UNKNOWN"
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
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
private fun CellInfoCardWithVerificationPreview() {
    EdgeSentinelTheme {
        // Note: Preview won't show verification badge since TowerVerifier requires Hilt
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
