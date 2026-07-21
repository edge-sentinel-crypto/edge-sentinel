/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bp22intel.edgesentinel.detection.geo.GeolocatedThreat
import com.bp22intel.edgesentinel.detection.geo.HeatMapPoint
import com.bp22intel.edgesentinel.domain.model.SensorCategory
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.ui.theme.StatusDangerous
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * CartoDB Dark Matter tile source for dark-themed map display.
 */
private val CARTO_DARK_MATTER: OnlineTileSourceBase = XYTileSource(
    "CartoDB Dark Matter",
    0,       // min zoom
    19,      // max zoom
    256,     // tile size px
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
)

/**
 * Creates a colored circle bitmap with a category letter for use as a map marker icon.
 */
private fun createMarkerBitmap(
    threatLevel: ThreatLevel,
    category: SensorCategory,
    sizePx: Int = 48
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val fillColor = when (threatLevel) {
        ThreatLevel.CLEAR -> 0xFF4CAF50.toInt()
        ThreatLevel.SUSPICIOUS -> 0xFFFF9800.toInt()
        ThreatLevel.THREAT -> 0xFFF44336.toInt()
    }

    // Outer glow
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        alpha = 80
        style = Paint.Style.FILL
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, glowPaint)

    // Inner fill
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f * 0.7f, fillPaint)

    // Category letter
    val symbol = when (category) {
        SensorCategory.CELLULAR -> "C"
        SensorCategory.WIFI -> "W"
        SensorCategory.BLUETOOTH -> "B"
        SensorCategory.NETWORK -> "N"
        SensorCategory.BASELINE -> "R"
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textSize = sizePx * 0.4f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textBounds = Rect()
    textPaint.getTextBounds(symbol, 0, symbol.length, textBounds)
    canvas.drawText(
        symbol,
        sizePx / 2f,
        sizePx / 2f + textBounds.height() / 2f,
        textPaint
    )

    return bitmap
}

/**
 * Composable that wraps an osmdroid MapView to display geolocated threats
 * on a dark-themed OpenStreetMap.
 */
@Composable
fun ThreatMapView(
    threats: List<GeolocatedThreat>,
    userLocation: Pair<Double, Double>,
    onThreatClick: (GeolocatedThreat) -> Unit,
    modifier: Modifier = Modifier,
    isNetworkAvailable: Boolean = true,
    heatMapPoints: List<HeatMapPoint> = emptyList(),
    heatMapEnabled: Boolean = false
) {
    val context = LocalContext.current
    var selectedThreat by remember { mutableStateOf<GeolocatedThreat?>(null) }

    // Initialize osmdroid configuration once
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = "EdgeSentinel/2.0"
    }

    // Remember the MapView so we can update it
    val mapView = remember {
        MapView(context).apply {
            setTileSource(CARTO_DARK_MATTER)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            // Dark background behind tiles
            setBackgroundColor(0xFF1a1a2e.toInt())
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
        }
    }

    // Heat map overlay (persistent, updated separately)
    val heatMapOverlay = remember { HeatMapOverlay() }

    // Update heat map overlay when data or toggle changes
    LaunchedEffect(heatMapPoints, heatMapEnabled) {
        if (heatMapEnabled) {
            heatMapOverlay.setPoints(heatMapPoints)
        } else {
            heatMapOverlay.setPoints(emptyList())
        }
        mapView.invalidate()
    }

    // Update markers when threats or user location change
    LaunchedEffect(threats, userLocation, heatMapEnabled) {
        mapView.overlays.clear()

        // Add heat map overlay first (renders behind markers)
        if (heatMapEnabled) {
            mapView.overlays.add(heatMapOverlay)
        }

        // User location marker (blue dot)
        val userMarker = Marker(mapView).apply {
            position = GeoPoint(userLocation.first, userLocation.second)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Your Location"
            // Create blue dot bitmap
            val blueDot = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
            val c = Canvas(blueDot)
            val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x554488FF.toInt()
                style = Paint.Style.FILL
            }
            c.drawCircle(18f, 18f, 18f, outerPaint)
            val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF4488FF.toInt()
                style = Paint.Style.FILL
            }
            c.drawCircle(18f, 18f, 10f, innerPaint)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            c.drawCircle(18f, 18f, 10f, borderPaint)
            icon = BitmapDrawable(context.resources, blueDot)
        }
        mapView.overlays.add(userMarker)

        // Threat markers
        threats.forEach { threat ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(threat.latitude, threat.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = threat.label
                snippet = "${threat.category.label} — ${threat.threatLevel.label}"
                icon = BitmapDrawable(
                    context.resources,
                    createMarkerBitmap(threat.threatLevel, threat.category)
                )
                setOnMarkerClickListener { _, _ ->
                    selectedThreat = threat
                    onThreatClick(threat)
                    true
                }
            }
            mapView.overlays.add(marker)
        }

        // Fit bounding box to all points
        val allPoints = mutableListOf(GeoPoint(userLocation.first, userLocation.second))
        threats.forEach { allPoints.add(GeoPoint(it.latitude, it.longitude)) }

        if (allPoints.size > 1) {
            val boundingBox = BoundingBox.fromGeoPoints(allPoints)
            mapView.post {
                try {
                    mapView.zoomToBoundingBox(boundingBox.increaseByScale(1.3f), true)
                } catch (_: Exception) {
                    // MapView not laid out yet, set a reasonable default
                    mapView.controller.setZoom(15.0)
                    mapView.controller.setCenter(allPoints.first())
                }
            }
        } else {
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(allPoints.first())
        }

        mapView.invalidate()
    }

    // Lifecycle management for osmdroid
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Network warning
        if (!isNetworkAvailable) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Surface.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "⚠ Map requires network — showing cached tiles",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // OSM attribution
        Text(
            text = "© OpenStreetMap contributors · © CARTO",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
        )

        // Selected threat popup
        selectedThreat?.let { threat ->
            ThreatInfoPopup(
                threat = threat,
                onDismiss = { selectedThreat = null },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}
