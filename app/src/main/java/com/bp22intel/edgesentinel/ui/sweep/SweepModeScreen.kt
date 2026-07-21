/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.sweep

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.detection.geo.HeatMapPoint
import com.bp22intel.edgesentinel.mesh.MeshViewModel
import com.bp22intel.edgesentinel.ui.map.HeatMapLegend
import com.bp22intel.edgesentinel.ui.map.HeatMapOverlay
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.SensorBluetooth
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private val CARTO_DARK_MATTER = XYTileSource(
    "CartoDB Dark Matter",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
)

// ── Main Screen ─────────────────────────────────────────────────────────

@Composable
fun SweepModeScreen(
    onBack: () -> Unit = {},
    sweepViewModel: SweepModeViewModel = hiltViewModel(),
    meshViewModel: MeshViewModel = hiltViewModel()
) {
    val uiState by sweepViewModel.uiState.collectAsState()
    val meshState by meshViewModel.uiState.collectAsState()
    val coopEnabled by meshViewModel.isCooperativeEnabled.collectAsState()

    val heatMapPoints by sweepViewModel.heatMapPoints.collectAsState()

    val context = LocalContext.current
    var panelExpanded by remember { mutableStateOf(false) }
    var showMarkDialog by remember { mutableStateOf(false) }
    var heatMapEnabled by remember { mutableStateOf(false) }

    // Bind mesh data into sweep VM
    LaunchedEffect(Unit) {
        sweepViewModel.bindProviders(
            meshUiState = { meshViewModel.uiState.value },
            cooperativeEnabled = { meshViewModel.isCooperativeEnabled.value },
            userLocation = { Pair(0.0, 0.0) } // Updated by map/location service
        )
    }

    // Auto-start sweep on screen entry
    LaunchedEffect(Unit) {
        if (!uiState.sweep.isActive) {
            sweepViewModel.startSweep()
        }
    }

    // Haptic + toast when target located
    LaunchedEffect(uiState.targetLocatedCid) {
        val cid = uiState.targetLocatedCid ?: return@LaunchedEffect
        triggerHaptic(context)
        Toast.makeText(context, "⚡ TARGET LOCATED — CID $cid", Toast.LENGTH_LONG).show()
        kotlinx.coroutines.delay(3000)
        sweepViewModel.clearTargetLocated()
    }

    // Clear observation flash after delay
    LaunchedEffect(uiState.flashObservationDeviceId) {
        if (uiState.flashObservationDeviceId != null) {
            kotlinx.coroutines.delay(2000)
            sweepViewModel.clearObservationFlash()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
    ) {
        // ── Top Bar ─────────────────────────────────────────────────
        SweepTopBar(
            elapsedMs = uiState.sweep.elapsedMs,
            isActive = uiState.sweep.isActive,
            isPaused = uiState.sweep.isPaused,
            heatMapEnabled = heatMapEnabled,
            onToggleHeatMap = { heatMapEnabled = !heatMapEnabled },
            onBack = {
                sweepViewModel.stopSweep()
                onBack()
            }
        )

        // ── Map ─────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (panelExpanded) 0.5f else 0.7f)
        ) {
            SweepMapView(
                uiState = uiState,
                heatMapPoints = if (heatMapEnabled) heatMapPoints else emptyList(),
                heatMapEnabled = heatMapEnabled,
                modifier = Modifier.fillMaxSize()
            )

            // "TARGET LOCATED" overlay flash
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.targetLocatedCid != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Card(
                    modifier = Modifier.padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusThreat.copy(alpha = 0.9f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "⚡ TARGET LOCATED — CID ${uiState.targetLocatedCid}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }

            // Heat map legend
            androidx.compose.animation.AnimatedVisibility(
                visible = heatMapEnabled && heatMapPoints.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 24.dp)
            ) {
                HeatMapLegend(showPeerLegend = true)
            }

            // Map attribution
            Text(
                text = "© OpenStreetMap · © CARTO",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            )
        }

        // ── Bottom Panel ────────────────────────────────────────────
        SweepBottomPanel(
            uiState = uiState,
            expanded = panelExpanded,
            onToggleExpand = { panelExpanded = !panelExpanded },
            onExportReport = {
                val intent = sweepViewModel.shareSweepReport()
                context.startActivity(Intent.createChooser(intent, "Share Sweep Report"))
            },
            onMarkLocation = { showMarkDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (panelExpanded) 0.5f else 0.3f)
        )
    }

    // Mark Location dialog
    if (showMarkDialog) {
        MarkLocationDialog(
            onConfirm = { note ->
                sweepViewModel.markLocation(note)
                showMarkDialog = false
            },
            onDismiss = { showMarkDialog = false }
        )
    }
}

// ── Top Bar ─────────────────────────────────────────────────────────────

@Composable
private fun SweepTopBar(
    elapsedMs: Long,
    isActive: Boolean,
    isPaused: Boolean,
    heatMapEnabled: Boolean = false,
    onToggleHeatMap: () -> Unit = {},
    onBack: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sweep_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundPrimary)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary
            )
        }

        // Pulsing status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (isActive && !isPaused) StatusClear.copy(alpha = pulseAlpha)
                    else if (isPaused) StatusSuspicious
                    else TextSecondary
                )
        )
        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "SWEEP MODE",
            color = StatusClear,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )

        // Heat map toggle
        IconButton(
            onClick = onToggleHeatMap,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = "Heat Map",
                tint = if (heatMapEnabled) SensorBluetooth else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Elapsed time
        Text(
            text = formatDuration(elapsedMs),
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}

// ── Map View ────────────────────────────────────────────────────────────

@Composable
private fun SweepMapView(
    uiState: SweepUiState,
    heatMapPoints: List<HeatMapPoint> = emptyList(),
    heatMapEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = "EdgeSentinel/2.0"
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(CARTO_DARK_MATTER)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            setBackgroundColor(0xFF0D1117.toInt())
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
        }
    }

    // Heat map overlay (persistent instance)
    val heatMapOverlay = remember { HeatMapOverlay() }

    // Update heat map data
    LaunchedEffect(heatMapPoints, heatMapEnabled) {
        if (heatMapEnabled) {
            heatMapOverlay.setPoints(heatMapPoints)
        } else {
            heatMapOverlay.setPoints(emptyList())
        }
        mapView.invalidate()
    }

    // Update overlays when state changes
    LaunchedEffect(
        uiState.userLat, uiState.userLng,
        uiState.peers, uiState.targets,
        uiState.markers, uiState.flashObservationDeviceId,
        heatMapEnabled
    ) {
        mapView.overlays.clear()

        // Heat map layer first (renders behind all markers)
        if (heatMapEnabled) {
            mapView.overlays.add(heatMapOverlay)
        }

        // ── Coverage polygon (connecting peer positions) ────────
        val polygonPoints = mutableListOf<GeoPoint>()
        if (uiState.userLat != 0.0) {
            polygonPoints.add(GeoPoint(uiState.userLat, uiState.userLng))
        }
        // For peers with known positions from observations
        uiState.targets.flatMap { it.observations }
            .distinctBy { it.deviceId }
            .forEach { obs ->
                if (obs.latCoarse != 0.0) {
                    polygonPoints.add(GeoPoint(obs.latCoarse, obs.lngCoarse))
                }
            }
        if (polygonPoints.size >= 3) {
            val coveragePoly = Polygon(mapView).apply {
                points = polygonPoints + polygonPoints.first() // Close polygon
                fillPaint.color = 0x1006B6D4 // Very faint cyan fill
                outlinePaint.color = 0x4006B6D4 // Faint cyan outline
                outlinePaint.strokeWidth = 2f
            }
            mapView.overlays.add(coveragePoly)
        }

        // ── Observation lines (peer → tower) ───────────────────
        uiState.targets.forEach { target ->
            if (target.hasEstimate) {
                val towerPoint = GeoPoint(target.estimatedLat!!, target.estimatedLng!!)
                target.observations.forEach { obs ->
                    if (obs.latCoarse != 0.0) {
                        val isFlashing = obs.deviceId == uiState.flashObservationDeviceId
                        val line = Polyline(mapView).apply {
                            addPoint(GeoPoint(obs.latCoarse, obs.lngCoarse))
                            addPoint(towerPoint)
                            outlinePaint.color = if (isFlashing) 0xAAEF4444.toInt()
                            else 0x30EF4444 // Faint red lines
                            outlinePaint.strokeWidth = if (isFlashing) 4f else 1.5f
                        }
                        mapView.overlays.add(line)
                    }
                }
            }
        }

        // ── Suspicious towers (red pulsing + accuracy rings) ───
        uiState.targets.forEach { target ->
            if (target.hasEstimate) {
                val towerPoint = GeoPoint(target.estimatedLat!!, target.estimatedLng!!)

                // Accuracy ring
                val accuracy = target.accuracyMeters ?: 500.0
                val ring = Polygon.pointsAsCircle(towerPoint, accuracy)
                val accuracyPoly = Polygon(mapView).apply {
                    points = ring
                    fillPaint.color = when (target.accuracyColor) {
                        AccuracyTier.HIGH -> 0x1510B981   // Green
                        AccuracyTier.MEDIUM -> 0x15F59E0B  // Yellow
                        AccuracyTier.LOW -> 0x15EF4444     // Red
                        AccuracyTier.UNKNOWN -> 0x10FFFFFF
                    }
                    outlinePaint.color = when (target.accuracyColor) {
                        AccuracyTier.HIGH -> 0x4010B981.toInt()
                        AccuracyTier.MEDIUM -> 0x40F59E0B
                        AccuracyTier.LOW -> 0x40EF4444
                        AccuracyTier.UNKNOWN -> 0x20FFFFFF
                    }
                    outlinePaint.strokeWidth = 2f
                }
                mapView.overlays.add(accuracyPoly)

                // Tower marker
                val towerSize = when {
                    accuracy > 500 -> 56
                    accuracy > 200 -> 44
                    else -> 36
                }
                val towerMarker = Marker(mapView).apply {
                    position = towerPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "CID ${target.cid}"
                    snippet = "±${accuracy.toInt()}m • ${target.participatingDevices} devices"
                    icon = BitmapDrawable(
                        context.resources,
                        createTowerBitmap(target, towerSize)
                    )
                }
                mapView.overlays.add(towerMarker)
            }
        }

        // ── Peer position markers (from observations) ──────────
        uiState.targets.flatMap { it.observations }
            .distinctBy { it.deviceId }
            .forEachIndexed { index, obs ->
                if (obs.latCoarse != 0.0) {
                    val peerMarker = Marker(mapView).apply {
                        position = GeoPoint(obs.latCoarse, obs.lngCoarse)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Peer ${('A' + index).toChar()}"
                        icon = BitmapDrawable(
                            context.resources,
                            createPeerBitmap(('A' + index).toChar().toString())
                        )
                    }
                    mapView.overlays.add(peerMarker)
                }
            }

        // ── User position (blue pulsing dot) ───────────────────
        if (uiState.userLat != 0.0) {
            val userMarker = Marker(mapView).apply {
                position = GeoPoint(uiState.userLat, uiState.userLng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Your Position"
                icon = BitmapDrawable(context.resources, createUserBitmap())
            }
            mapView.overlays.add(userMarker)
        }

        // ── Dropped pins ───────────────────────────────────────
        uiState.markers.forEach { marker ->
            val pinMarker = Marker(mapView).apply {
                position = GeoPoint(marker.lat, marker.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = marker.note
                snippet = formatTimestamp(marker.timestamp)
                icon = BitmapDrawable(context.resources, createPinBitmap())
            }
            mapView.overlays.add(pinMarker)
        }

        // Fit to points
        val allPoints = mutableListOf<GeoPoint>()
        if (uiState.userLat != 0.0) {
            allPoints.add(GeoPoint(uiState.userLat, uiState.userLng))
        }
        uiState.targets.forEach { target ->
            if (target.hasEstimate) {
                allPoints.add(GeoPoint(target.estimatedLat!!, target.estimatedLng!!))
            }
        }
        uiState.targets.flatMap { it.observations }
            .filter { it.latCoarse != 0.0 }
            .forEach { allPoints.add(GeoPoint(it.latCoarse, it.lngCoarse)) }

        if (allPoints.size > 1) {
            val bbox = BoundingBox.fromGeoPoints(allPoints)
            mapView.post {
                try {
                    mapView.zoomToBoundingBox(bbox.increaseByScale(1.4f), true)
                } catch (_: Exception) {
                    mapView.controller.setZoom(15.0)
                    if (allPoints.isNotEmpty()) mapView.controller.setCenter(allPoints.first())
                }
            }
        } else if (allPoints.size == 1) {
            mapView.controller.setCenter(allPoints.first())
        }

        mapView.invalidate()
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

// ── Bottom Panel ────────────────────────────────────────────────────────

@Composable
private fun SweepBottomPanel(
    uiState: SweepUiState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onExportReport: () -> Unit,
    onMarkLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                Surface,
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            )
            .animateContentSize()
    ) {
        // Drag handle + status bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -20) onToggleExpand()
                        if (dragAmount > 20 && expanded) onToggleExpand()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextSecondary.copy(alpha = 0.3f))
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = buildString {
                    append(if (uiState.sweep.isActive) "SWEEP ACTIVE" else "SWEEP STOPPED")
                    append(" • ${uiState.peers.size + 1} devices")
                    if (uiState.targets.isNotEmpty()) {
                        append(" • ${uiState.targets.size} target${if (uiState.targets.size > 1) "s" else ""} tracked")
                    }
                }
                Text(
                    text = statusText,
                    color = if (uiState.sweep.isActive) StatusClear else TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = "Toggle panel",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Scrollable content
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Target cards
            if (uiState.targets.isNotEmpty()) {
                items(uiState.targets, key = { it.cid }) { target ->
                    TargetCard(target = target)
                }
            } else if (uiState.sweep.isActive) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
                    ) {
                        Text(
                            text = "Scanning for suspicious towers...",
                            modifier = Modifier.padding(16.dp),
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Peer status row
            item {
                PeerStatusRow(peers = uiState.peers)
            }

            // Action buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExportReport,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StatusClear
                        )
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Export Report",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onMarkLocation,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AccentBlue
                        )
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Mark Location",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

// ── Target Card ─────────────────────────────────────────────────────────

@Composable
private fun TargetCard(target: SweepTarget) {
    val accentColor = when (target.accuracyColor) {
        AccuracyTier.HIGH -> StatusClear
        AccuracyTier.MEDIUM -> StatusSuspicious
        AccuracyTier.LOW -> StatusThreat
        AccuracyTier.UNKNOWN -> TextSecondary
    }

    // Progress: convergence based on observation count (5 = full)
    val convergenceProgress by animateFloatAsState(
        targetValue = (target.participatingDevices / 5f).coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "convergence"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.08f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠",
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CID ${target.cid}",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "MCC ${target.mcc}/MNC ${target.mnc}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                // Accuracy badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accentColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = when {
                            target.accuracyMeters == null -> "?"
                            else -> "±${target.accuracyMeters.toInt()}m"
                        },
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Observations: ${target.participatingDevices} of ${target.totalDevices} devices",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            if (target.hasEstimate) {
                Text(
                    text = "Position: ${"%.4f".format(target.estimatedLat)}°N, ${"%.4f".format(target.estimatedLng)}°W",
                    color = accentColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "Position: Refining... (need ${3 - target.participatingDevices} more devices)",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Convergence progress bar
            LinearProgressIndicator(
                progress = { convergenceProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.15f)
            )
        }
    }
}

// ── Peer Status Row ─────────────────────────────────────────────────────

@Composable
private fun PeerStatusRow(peers: List<SweepPeer>) {
    if (peers.isEmpty()) return

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(peers, key = { it.deviceId }) { peer ->
            PeerChip(peer = peer)
        }
    }
}

@Composable
private fun PeerChip(peer: SweepPeer) {
    val (bgColor, statusIcon, statusColor) = when (peer.status) {
        PeerStatus.CONTRIBUTING -> Triple(StatusClear.copy(alpha = 0.1f), "✓", StatusClear)
        PeerStatus.CONNECTED_IDLE -> Triple(StatusSuspicious.copy(alpha = 0.1f), "⏳", StatusSuspicious)
        PeerStatus.OUT_OF_RANGE -> Triple(StatusThreat.copy(alpha = 0.1f), "✗", StatusThreat)
    }

    Row(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = peer.displayLabel.take(6),
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = statusIcon,
            color = statusColor,
            fontSize = 12.sp
        )
    }
}

// ── Mark Location Dialog ────────────────────────────────────────────────

@Composable
private fun MarkLocationDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text("Mark Location", color = TextPrimary)
        },
        text = {
            Column {
                Text(
                    "Drop a pin at your current position.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note.ifBlank { "Marked point" }) }) {
                Text("Mark", color = AccentBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

// ── Bitmap Factories ────────────────────────────────────────────────────

private fun createUserBitmap(): Bitmap {
    val size = 40
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    // Outer pulse ring
    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x553B82F6
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, size / 2f, outerPaint)

    // Inner solid blue
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, size / 2f * 0.55f, innerPaint)

    // White border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawCircle(cx, cy, size / 2f * 0.55f, borderPaint)

    // Heading indicator (small triangle at top)
    val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    val path = android.graphics.Path().apply {
        moveTo(cx, 2f)
        lineTo(cx - 4f, 9f)
        lineTo(cx + 4f, 9f)
        close()
    }
    canvas.drawPath(path, triPaint)

    return bitmap
}

private fun createPeerBitmap(label: String): Bitmap {
    val size = 32
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF06B6D4.toInt() // Cyan
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, size / 2f * 0.7f, dotPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textSize = size * 0.4f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(label, 0, label.length, textBounds)
    canvas.drawText(label, cx, cy + textBounds.height() / 2f, textPaint)

    return bitmap
}

private fun createTowerBitmap(target: SweepTarget, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val color = when (target.accuracyColor) {
        AccuracyTier.HIGH -> 0xFF10B981.toInt()
        AccuracyTier.MEDIUM -> 0xFFF59E0B.toInt()
        AccuracyTier.LOW -> 0xFFEF4444.toInt()
        AccuracyTier.UNKNOWN -> 0xFFEF4444.toInt()
    }

    // Outer glow
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        alpha = 60
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, sizePx / 2f, glowPaint)

    // Inner fill
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, sizePx / 2f * 0.65f, fillPaint)

    // "!" symbol
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFF000000.toInt()
        textSize = sizePx * 0.45f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds("!", 0, 1, textBounds)
    canvas.drawText("!", cx, cy + textBounds.height() / 2f, textPaint)

    return bitmap
}

private fun createPinBitmap(): Bitmap {
    val size = 36
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f

    // Pin body
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF58A6FF.toInt() // AccentBlue
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, 12f, 10f, pinPaint)

    // Pin point
    val path = android.graphics.Path().apply {
        moveTo(cx - 6f, 16f)
        lineTo(cx, size.toFloat() - 2)
        lineTo(cx + 6f, 16f)
        close()
    }
    canvas.drawPath(path, pinPaint)

    // Inner dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, 12f, 4f, dotPaint)

    return bitmap
}

// ── Utilities ───────────────────────────────────────────────────────────

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatTimestamp(timestamp: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(timestamp))
}

@Suppress("DEPRECATION")
private fun triggerHaptic(context: android.content.Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(
                android.content.Context.VIBRATOR_MANAGER_SERVICE
            ) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            val vibrator = context.getSystemService(
                android.content.Context.VIBRATOR_SERVICE
            ) as? Vibrator
            vibrator?.vibrate(
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    } catch (_: Exception) {
        // Vibration not available, ignore
    }
}
