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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * CartoDB Dark Matter tile source — matches the existing ThreatMapView style.
 */
private val CARTO_DARK_MATTER = XYTileSource(
    "CartoDB Dark Matter",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
)

/**
 * Data class holding all information needed to display the tower location sheet.
 */
data class TowerLocationInfo(
    val cellTower: CellTower,
    val knownTower: KnownTowerEntity
)

/**
 * Bottom sheet that shows a live map with:
 * - The selected tower's geographic position (from OpenCelliD database)
 * - The user's current GPS position (updated in real-time)
 * - A dashed line between the two
 * - Distance and bearing information
 *
 * Uses osmdroid with CartoDB Dark Matter tiles (same as the existing threat map).
 * All processing is LOCAL — no data ever leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowerLocationSheet(
    towerInfo: TowerLocationInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val towerLat = towerInfo.knownTower.latitude
    val towerLon = towerInfo.knownTower.longitude
    val cell = towerInfo.cellTower
    val knownTower = towerInfo.knownTower

    // Live user location state
    var userLat by remember { mutableDoubleStateOf(0.0) }
    var userLon by remember { mutableDoubleStateOf(0.0) }
    var hasUserLocation by remember { mutableStateOf(false) }
    var distanceMeters by remember { mutableDoubleStateOf(0.0) }
    var bearingDegrees by remember { mutableDoubleStateOf(0.0) }

    // Location listener for live GPS updates
    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    val locationListener = remember {
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                userLat = location.latitude
                userLon = location.longitude
                hasUserLocation = true

                // Compute distance and bearing to tower
                val results = FloatArray(2)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    towerLat, towerLon,
                    results
                )
                distanceMeters = results[0].toDouble()
                bearingDegrees = results[1].toDouble()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
    }

    // Start/stop location updates with the sheet lifecycle
    DisposableEffect(Unit) {
        val hasPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                val provider = when {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                        LocationManager.GPS_PROVIDER
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                        LocationManager.NETWORK_PROVIDER
                    else -> null
                }

                provider?.let {
                    locationManager.requestLocationUpdates(
                        it, 2000L, 5f, locationListener
                    )
                    // Seed with last known location for instant display
                    locationManager.getLastKnownLocation(it)?.let { loc ->
                        locationListener.onLocationChanged(loc)
                    }
                }
            } catch (_: SecurityException) { /* already guarded */ }
        }

        onDispose {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: SecurityException) { /* ignore */ }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextSecondary.copy(alpha = 0.4f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "TOWER LOCATION",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "CID ${cell.cid} · ${knownTower.radio}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tower identity row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TowerInfoChip("MCC", "${knownTower.mcc}")
                TowerInfoChip("MNC", "${knownTower.mnc}")
                TowerInfoChip("LAC", "${knownTower.lac}")
                TowerInfoChip("CID", "${knownTower.cid}")
                TowerInfoChip("Signal", "${cell.signalStrength} dBm")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Distance & bearing bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasUserLocation) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            tint = StatusClear,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = formatDistance(distanceMeters),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = formatBearing(bearingDegrees),
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Text(
                        text = "Acquiring GPS…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Tower coordinates
                Text(
                    text = "%.5f, %.5f".format(towerLat, towerLon),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // === Live Map ===
            TowerMapView(
                towerLat = towerLat,
                towerLon = towerLon,
                userLat = userLat,
                userLon = userLon,
                hasUserLocation = hasUserLocation,
                towerLabel = "CID ${cell.cid}",
                radioType = knownTower.radio,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            // OSM attribution
            Text(
                text = "© OpenStreetMap contributors · © CARTO",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 20.dp, top = 4.dp)
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Map composable (osmdroid AndroidView)
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun TowerMapView(
    towerLat: Double,
    towerLon: Double,
    userLat: Double,
    userLon: Double,
    hasUserLocation: Boolean,
    towerLabel: String,
    radioType: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Init osmdroid config once
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = "EdgeSentinel/2.0"
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(CARTO_DARK_MATTER)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            setBackgroundColor(0xFF1a1a2e.toInt())
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
        }
    }

    // Update overlays whenever positions change
    LaunchedEffect(towerLat, towerLon, userLat, userLon, hasUserLocation) {
        mapView.overlays.clear()

        val towerPoint = GeoPoint(towerLat, towerLon)

        // Tower marker (red/orange cell tower icon)
        val towerMarker = Marker(mapView).apply {
            position = towerPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = towerLabel
            snippet = "$radioType tower"
            icon = BitmapDrawable(
                context.resources,
                createTowerBitmap(56)
            )
        }
        mapView.overlays.add(towerMarker)

        if (hasUserLocation) {
            val userPoint = GeoPoint(userLat, userLon)

            // User marker (blue dot — matching existing ThreatMapView style)
            val userMarker = Marker(mapView).apply {
                position = userPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Your Location"
                icon = BitmapDrawable(
                    context.resources,
                    createUserDotBitmap(40)
                )
            }
            mapView.overlays.add(userMarker)

            // Dashed line connecting user → tower
            val line = Polyline(mapView).apply {
                addPoint(userPoint)
                addPoint(towerPoint)
                outlinePaint.apply {
                    color = 0xBB4488FF.toInt()
                    strokeWidth = 4f
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
                    isAntiAlias = true
                }
            }
            mapView.overlays.add(line)

            // Fit both points in view
            val bbox = BoundingBox.fromGeoPoints(listOf(towerPoint, userPoint))
            mapView.post {
                try {
                    mapView.zoomToBoundingBox(bbox.increaseByScale(1.5f), true)
                } catch (_: Exception) {
                    mapView.controller.setZoom(14.0)
                    mapView.controller.setCenter(towerPoint)
                }
            }
        } else {
            // Only tower — center on it
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(towerPoint)
        }

        mapView.invalidate()
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

// ──────────────────────────────────────────────────────────────────────
//  Bitmap helpers
// ──────────────────────────────────────────────────────────────────────

/** Orange/red tower marker bitmap with "T" label. */
private fun createTowerBitmap(sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    // Outer glow
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6D00.toInt()
        alpha = 70
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, sizePx / 2f, glow)

    // Inner fill
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6D00.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, sizePx * 0.38f, fill)

    // White border
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    canvas.drawCircle(cx, cy, sizePx * 0.38f, border)

    // "T" label
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sizePx * 0.38f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val bounds = Rect()
    text.getTextBounds("T", 0, 1, bounds)
    canvas.drawText("T", cx, cy + bounds.height() / 2f, text)

    return bitmap
}

/** Blue user-position dot bitmap — matches ThreatMapView user marker. */
private fun createUserDotBitmap(sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x554488FF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, sizePx / 2f, outer)

    val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4488FF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, sizePx * 0.3f, inner)

    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawCircle(cx, cy, sizePx * 0.3f, ring)

    return bitmap
}

// ──────────────────────────────────────────────────────────────────────
//  Small UI helpers
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun TowerInfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}

private fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> "%.1f km".format(meters / 1000)
    else -> "${meters.toInt()} m"
}

private fun formatBearing(degrees: Double): String {
    // Normalize to 0-360
    val d = ((degrees % 360) + 360) % 360
    val cardinal = when {
        d < 22.5 || d >= 337.5 -> "N"
        d < 67.5 -> "NE"
        d < 112.5 -> "E"
        d < 157.5 -> "SE"
        d < 202.5 -> "S"
        d < 247.5 -> "SW"
        d < 292.5 -> "W"
        else -> "NW"
    }
    return "${d.toInt()}° $cardinal"
}
