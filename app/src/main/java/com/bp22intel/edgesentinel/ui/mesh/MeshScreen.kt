/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.mesh

import com.bp22intel.edgesentinel.ui.utils.TimeFormatUtils

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.mesh.MeshAlert
import com.bp22intel.edgesentinel.mesh.MeshAlertAggregator
import com.bp22intel.edgesentinel.mesh.MeshUiState
import com.bp22intel.edgesentinel.mesh.MeshViewModel
import com.bp22intel.edgesentinel.mesh.CooperativeModeState
import com.bp22intel.edgesentinel.mesh.TrackedCid
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusElevated
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val MeshCyan = Color(0xFF06B6D4)
val CoopGold = Color(0xFFF59E0B)

// Data classes for the enhanced UI (extending existing MeshUiState)
data class MeshPeer(
    val id: String,
    val displayName: String,
    val rssi: Int,
    val lastSeen: Long,
    val threatLevel: ThreatLevel,
    val isConnected: Boolean,
    val isEdgeSentinel: Boolean = false
)

data class SharedAlert(
    val id: String,
    val peerId: String,
    val peerDisplayName: String,
    val alertType: String,
    val description: String,
    val timestamp: Long,
    val isCorroborated: Boolean
)

data class ConsensusAlert(
    val threatDescription: String,
    val detectingPeerCount: Int,
    val totalNearbyPeers: Int,
    val confidenceLevel: String,
    val firstDetected: Long,
    val category: SensorCategory
)

data class MeshStats(
    val totalPeersToday: Int,
    val alertsShared: Int,
    val alertsReceived: Int,
    val correlatedDetections: Int,
    val cooperativeLocations: Int
)

@Composable
fun MeshScreen(
    onBack: () -> Unit = {},
    onNavigateToSweep: () -> Unit = {},
    viewModel: MeshViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val coopEnabled by viewModel.isCooperativeEnabled.collectAsState()
    var showCoopGuide by remember { mutableStateOf(false) }
    
    // Real discovered peers from BLE scan
    val realPeers = uiState.discoveredPeers.map { peer ->
        MeshPeer(
            id = peer.deviceAddress,
            displayName = peer.deviceName ?: peer.deviceAddress.takeLast(8),
            rssi = peer.rssi,
            lastSeen = peer.lastSeen,
            threatLevel = ThreatLevel.CLEAR,
            isConnected = (System.currentTimeMillis() - peer.lastSeen) < 60_000,
            isEdgeSentinel = peer.isEdgeSentinel
        )
    }
    
    val consensusAlerts = if (uiState.correlatedAlerts.isNotEmpty()) {
        uiState.correlatedAlerts.map { correlated ->
            ConsensusAlert(
                threatDescription = "Suspicious tower CID ${correlated.cellCid ?: "unknown"}",
                detectingPeerCount = correlated.peerCount,
                totalNearbyPeers = realPeers.size + 1,
                confidenceLevel = when {
                    correlated.peerCount >= 3 -> "Confirmed"
                    correlated.peerCount >= 2 -> "Corroborated"
                    else -> "Unconfirmed"
                },
                firstDetected = correlated.firstSeen,
                category = SensorCategory.CELLULAR
            )
        }
    } else emptyList()
    
    val sharedAlerts = uiState.recentMeshAlerts.map { alert ->
        SharedAlert(
            id = alert.messageId,
            peerId = alert.deviceId,
            peerDisplayName = alert.deviceId.take(8),
            alertType = alert.threatType.name,
            description = alert.summary,
            timestamp = alert.timestamp,
            isCorroborated = false
        )
    }
    
    val coopState = uiState.cooperativeMode
    
    val meshStats = MeshStats(
        totalPeersToday = realPeers.size,
        alertsShared = uiState.totalAlertsReceived,
        alertsReceived = uiState.totalAlertsReceived,
        correlatedDetections = uiState.corroboratedAlertCount,
        cooperativeLocations = coopState.trilaterations.count { it.hasEstimate }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Network") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // Top Status Bar
        item { 
            MeshStatusCard(
                state = uiState,
                peerCount = realPeers.size,
                onStart = viewModel::startMesh,
                onStop = viewModel::stopMesh
            ) 
        }

        // Error message
        uiState.error?.let { errorMsg ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFEF4444).copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss", color = Color(0xFFEF4444))
                        }
                    }
                }
            }
        }

        // ── Sweep Mode Button ────────────────────────────────────────
        if (uiState.isActive && coopEnabled) {
            item {
                Button(
                    onClick = onNavigateToSweep,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusClear
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "START SWEEP",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // ── Cooperative Localization Section ──────────────────────────
        if (uiState.isActive && coopEnabled) {
            item {
                CooperativeModeCard(coopState = coopState)
            }
            item {
                TextButton(onClick = { showCoopGuide = true }) {
                    Icon(Icons.Filled.Info, null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("How cooperative localization works", style = MaterialTheme.typography.bodySmall, color = AccentBlue)
                }
            }

            // Cooperatively tracked CIDs
            if (coopState.trackedCids.isNotEmpty()) {
                item {
                    SectionHeader(title = "Cooperative Tracking")
                }
                items(coopState.trackedCids, key = { it.cid }) { trackedCid ->
                    CooperativeTrackedCidCard(trackedCid)
                }
            }
        }
        
        // Nearby Peers Section
        item {
            SectionHeader(title = "Nearby Peers")
        }
        
        if (uiState.isActive) {
            if (realPeers.isNotEmpty()) {
                items(realPeers, key = { it.id }) { peer ->
                    NearbyPeerCard(peer)
                }
            } else {
                item {
                    ScanningEmptyState()
                }
            }
        } else {
            item {
                DisabledEmptyState()
            }
        }
        
        // Team Threat Consensus Section
        if (consensusAlerts.isNotEmpty()) {
            item {
                SectionHeader(title = "Team Threat Consensus")
            }
            items(consensusAlerts, key = { "${it.threatDescription}_${it.firstDetected}" }) { consensus ->
                ConsensusAlertCard(consensus)
            }
        }
        
        // Shared Alerts Feed Section
        if (sharedAlerts.isNotEmpty()) {
            item {
                SectionHeader(title = "Shared Alerts")
            }
            items(sharedAlerts, key = { it.id }) { alert ->
                SharedAlertCard(alert)
            }
        }
        
        // Mesh Stats Footer
        item {
            MeshStatsCard(meshStats)
        }
        
        // Bottom spacer
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    }

    // Cooperative guide bottom sheet
    if (showCoopGuide) {
        CooperativeGuideSheet(onDismiss = { showCoopGuide = false })
    }
}

// ── Cooperative Mode Cards ───────────────────────────────────────────────

@Composable
private fun CooperativeModeCard(coopState: CooperativeModeState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (coopState.isActive) CoopGold.copy(alpha = 0.1f) else SurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.GpsFixed,
                    contentDescription = null,
                    tint = if (coopState.isActive) CoopGold else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (coopState.isActive) "Cooperative Mode: Active" else "Cooperative Mode: Standby",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (coopState.isActive) {
                        Text(
                            text = "Sharing anonymous signal data with ${coopState.edgeSentinelPeerCount} nearby Edge Sentinel users",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        Text(
                            text = "Waiting for threats + peers to begin cooperative localization",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            if (coopState.isActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = CoopGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Contributing ${coopState.sharingObservationCount} observations to ${coopState.edgeSentinelPeerCount} peers",
                            style = MaterialTheme.typography.bodySmall,
                            color = CoopGold
                        )
                    }
                }

                if (coopState.receivingFromPeerCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Receiving from ${coopState.receivingFromPeerCount} peer devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun CooperativeTrackedCidCard(trackedCid: TrackedCid) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (trackedCid.hasEstimate)
                CoopGold.copy(alpha = 0.08f) else Surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // CID badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (trackedCid.hasEstimate) CoopGold.copy(alpha = 0.2f)
                        else SurfaceVariant
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "CID ${trackedCid.cid}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (trackedCid.hasEstimate) CoopGold else TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${trackedCid.observationCount} observations from ${trackedCid.participatingDevices} devices",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }

                if (trackedCid.hasEstimate) {
                    Text(
                        text = "📍 Located — ${trackedCid.accuracyLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = CoopGold
                    )
                } else if (trackedCid.participatingDevices < 3) {
                    Text(
                        text = "Need ${3 - trackedCid.participatingDevices} more devices to locate",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (trackedCid.hasEstimate) {
                // Star icon for cooperatively located threats
                Icon(
                    imageVector = Icons.Default.GpsFixed,
                    contentDescription = "Cooperatively located",
                    tint = CoopGold,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Existing Cards (preserved) ───────────────────────────────────────────

@Composable
private fun MeshStatusCard(
    state: MeshUiState,
    peerCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        tint = if (state.isActive) MeshCyan else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Mesh Network",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.isActive) StatusClear else TextSecondary
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (state.isActive) "Active — Scanning + Advertising" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                Switch(
                    checked = state.isActive,
                    onCheckedChange = { enabled ->
                        if (enabled) onStart() else onStop()
                    }
                )
            }
            
            if (state.isActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val esPeers = state.discoveredPeers.count { it.isEdgeSentinel }
                    Text(
                        "$peerCount devices nearby" + if (esPeers > 0) " ($esPeers Edge Sentinel)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshCyan
                    )
                    Text(
                        "BLE discovery active",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyPeerCard(peer: MeshPeer) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    if (peer.isEdgeSentinel) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MeshCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MeshCyan
                            )
                        }
                    }
                }
                Text(
                    text = formatLastSeen(peer.lastSeen),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            RssiIndicator(rssi = peer.rssi)
            Spacer(modifier = Modifier.width(12.dp))
            
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when (peer.threatLevel) {
                            ThreatLevel.CLEAR -> StatusClear
                            ThreatLevel.SUSPICIOUS -> StatusElevated
                            ThreatLevel.THREAT -> StatusDangerous
                        }
                    )
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "Connection quality",
                tint = if (peer.isConnected) MeshCyan else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun RssiIndicator(rssi: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val bars = when {
            rssi > -60 -> 4
            rssi > -70 -> 3
            rssi > -80 -> 2
            rssi > -90 -> 1
            else -> 0
        }
        
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + index * 2).dp)
                    .background(
                        if (index < bars) MeshCyan else TextSecondary.copy(alpha = 0.3f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
private fun ScanningEmptyState() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = MeshCyan.copy(alpha = alpha),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Scanning for nearby Edge Sentinel devices...",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DisabledEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enable mesh networking to discover nearby devices",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ConsensusAlertCard(consensus: ConsensusAlert) {
    val consensusColor = when (consensus.confidenceLevel) {
        "Confirmed" -> StatusDangerous
        "Corroborated" -> StatusElevated
        else -> StatusClear
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = consensusColor.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = consensusColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${consensus.detectingPeerCount} of ${consensus.totalNearbyPeers} peers also detect",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        consensus.threatDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Correlated detection — ${consensus.confidenceLevel.uppercase()} confidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = consensusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(consensusColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        consensus.confidenceLevel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = consensusColor
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedAlertCard(alert: SharedAlert) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MeshCyan.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    alert.peerDisplayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MeshCyan
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alert.alertType.replace("_", " "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                if (alert.description.isNotBlank()) {
                    Text(
                        text = alert.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = TimeFormatUtils.formatTimestampShort(alert.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (alert.isCorroborated) {
                    Text(
                        text = "✓ Corroborated",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusClear
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshStatsCard(stats: MeshStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Mesh Statistics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatColumn("Peers", stats.totalPeersToday.toString())
                StatColumn("Shared", stats.alertsShared.toString())
                StatColumn("Received", stats.alertsReceived.toString())
                StatColumn("Correlated", stats.correlatedDetections.toString())
                StatColumn("Located", stats.cooperativeLocations.toString(), CoopGold)
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, color: Color = MeshCyan) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CooperativeGuideSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "HOW COOPERATIVE LOCALIZATION WORKS",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AccentBlue
            )
            Spacer(modifier = Modifier.height(16.dp))

            GuideStep("1", "Install Edge Sentinel on every team member's phone",
                "Each device becomes a sensor node. More devices = more accurate positioning.")
            GuideStep("2", "Enable Mesh Network",
                "Dashboard \u2192 Mesh Network \u2192 Enable. Devices find each other automatically via Bluetooth within ~50m line of sight.")
            GuideStep("3", "Spread out around the area of concern",
                "Devices need to be at different positions (50m+ apart) to triangulate. Think corners of a building, different floors, or spread across a facility.")
            GuideStep("4", "Threats are automatically shared and triangulated",
                "When a suspicious tower is detected, your phone shares anonymous signal observations with nearby Edge Sentinel users. With 3+ devices seeing the same tower, the system triangulates its position.")

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "WHAT\u2019S SHARED",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = StatusElevated
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("\u2022 Your approximate position (grid-snapped to ~100m \u2014 not exact GPS)",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("\u2022 Signal strength and timing advance for the suspicious tower",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("\u2022 Anonymous device ID (rotated every 24 hours)",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("\u2022 NO personal data, NO exact location, NO phone identity",
                style = MaterialTheme.typography.bodySmall, color = TextPrimary,
                fontWeight = FontWeight.SemiBold)

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "TIPS FOR BEST RESULTS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = StatusClear
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("\u2022 5+ devices can locate a tower to \u00b1100m",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("\u2022 3 devices is the minimum for triangulation",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("\u2022 Walk around the area \u2014 even 1 device moving gives multiple observation points",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("\u2022 Stay within Bluetooth range (~50m) of at least one other device",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("\u2022 The system works solo too \u2014 cooperative mode just makes it better",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GuideStep(number: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(AccentBlue.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = AccentBlue, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = TextSecondary)
        }
    }
}

// Utility functions
// formatTimestamp removed — use TimeFormatUtils.formatTimestampShort()

private fun formatLastSeen(lastSeen: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - lastSeen
    return when {
        diff < 10_000 -> "${diff / 1000}s ago"
        diff < 60_000 -> "${diff / 1000}s ago" 
        diff < 3600_000 -> "${diff / 60_000}m ago"
        else -> "${diff / 3600_000}h ago"
    }
}
