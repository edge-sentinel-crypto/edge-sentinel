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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * Live-updating map card showing the estimated location of a detected threat.
 *
 * The map animates marker position and accuracy circle as new readings
 * refine the estimate. The user can literally watch the accuracy circle
 * shrink and the marker converge on the true tower position.
 *
 * @param latitude      Estimated latitude of the threat source
 * @param longitude     Estimated longitude of the threat source
 * @param accuracyM     Estimated accuracy in meters (shown as a circle)
 * @param readingCount  Number of signal readings that contributed
 * @param confidenceScore Tower confidence 0.0-1.0
 * @param label         Label for the marker
 * @param userLat       User's current latitude (optional, shows user dot + distance)
 * @param userLng       User's current longitude (optional)
 */
@Composable
fun AlertLocationMap(
    latitude: Double,
    longitude: Double,
    accuracyM: Double = 500.0,
    readingCount: Int = 0,
    confidenceScore: Float = 0f,
    label: String = "Estimated Location",
    userLat: Double? = null,
    userLng: Double? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val cartoDark = remember {
        XYTileSource(
            "CartoDB Dark Matter",
            0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/"
            )
        )
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(cartoDark)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            setBackgroundColor(0xFF1a1a2e.toInt())
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
        }
    }

    // Keep references to overlays for smooth updates
    val threatMarker = remember { Marker(mapView) }
    val accuracyCircle = remember { Polygon(mapView) }
    val userMarker = remember { Marker(mapView) }

    // Track previous values for smooth animation
    var prevLat by remember { mutableDoubleStateOf(latitude) }
    var prevLng by remember { mutableDoubleStateOf(longitude) }
    var prevAccuracy by remember { mutableDoubleStateOf(accuracyM) }
    var isInitialized by remember { mutableStateOf(false) }

    // Animated confidence for the progress bar
    val animatedConfidence by animateFloatAsState(
        targetValue = confidenceScore,
        animationSpec = tween(durationMillis = 800),
        label = "confidence"
    )

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = "EdgeSentinel/2.0"
    }

    // Initialize map overlays once
    LaunchedEffect(Unit) {
        mapView.overlays.clear()

        // Accuracy circle overlay
        accuracyCircle.apply {
            fillPaint.apply {
                color = 0x20EF4444.toInt()
                style = Paint.Style.FILL
            }
            outlinePaint.apply {
                color = 0xAAEF4444.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
        }
        mapView.overlays.add(accuracyCircle)

        // Threat marker
        val threatBitmap = createThreatMarkerBitmap()
        threatMarker.apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(context.resources, threatBitmap)
        }
        mapView.overlays.add(threatMarker)

        // User marker
        val userBitmap = createUserMarkerBitmap()
        userMarker.apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(context.resources, userBitmap)
            title = "Your Location"
        }
        mapView.overlays.add(userMarker)
    }

    // Animate to new position when latitude/longitude/accuracy changes
    LaunchedEffect(latitude, longitude, accuracyM, userLat, userLng) {
        if (!isInitialized) {
            // First render — snap to position
            val threatPoint = GeoPoint(latitude, longitude)
            threatMarker.position = threatPoint
            threatMarker.title = label
            accuracyCircle.points = Polygon.pointsAsCircle(threatPoint, accuracyM)
            accuracyCircle.title = "Estimated accuracy: ${accuracyM.toInt()}m"

            if (userLat != null && userLng != null) {
                userMarker.position = GeoPoint(userLat, userLng)
                userMarker.setVisible(true)
            } else {
                userMarker.setVisible(false)
            }

            val zoomLevel = computeZoomLevel(accuracyM)
            mapView.controller.setZoom(zoomLevel)
            mapView.controller.setCenter(threatPoint)
            mapView.invalidate()

            prevLat = latitude
            prevLng = longitude
            prevAccuracy = accuracyM
            isInitialized = true
        } else {
            // Smooth animation to new position over 1 second
            animateMapUpdate(
                mapView = mapView,
                marker = threatMarker,
                circle = accuracyCircle,
                fromLat = prevLat,
                fromLng = prevLng,
                fromAccuracy = prevAccuracy,
                toLat = latitude,
                toLng = longitude,
                toAccuracy = accuracyM,
                label = label,
                durationMs = 1000L
            )

            if (userLat != null && userLng != null) {
                userMarker.position = GeoPoint(userLat, userLng)
                userMarker.setVisible(true)
            }

            // Smooth zoom if accuracy changed significantly
            val newZoom = computeZoomLevel(accuracyM)
            val currentZoom = mapView.zoomLevelDouble
            if (kotlin.math.abs(newZoom - currentZoom) > 0.5) {
                mapView.controller.animateTo(GeoPoint(latitude, longitude), newZoom, 1000L)
            } else {
                mapView.controller.animateTo(GeoPoint(latitude, longitude), null, 1000L)
            }

            prevLat = latitude
            prevLng = longitude
            prevAccuracy = accuracyM
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    // Distance label
    val distanceText = if (userLat != null && userLng != null) {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(userLat, userLng, latitude, longitude, results)
        val distM = results[0]
        when {
            distM < 100 -> "${distM.toInt()}m from you"
            distM < 1000 -> "${distM.toInt()}m away"
            else -> "%.1f km away".format(distM / 1000f)
        }
    } else null

    // Accuracy color based on quality
    val accuracyColor = when {
        accuracyM <= 100 -> StatusClear
        accuracyM <= 300 -> StatusSuspicious
        else -> TextSecondary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Estimated Location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            if (distanceText != null) {
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Live accuracy + reading count
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Accuracy: ±${accuracyM.toInt()}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = accuracyColor,
                    fontWeight = FontWeight.Medium
                )
                if (readingCount > 0) {
                    Text(
                        text = "  •  $readingCount reading${if (readingCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // Confidence progress bar (only show if we have tracker data)
            if (readingCount > 0 && confidenceScore > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { animatedConfidence },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = when {
                            confidenceScore >= 0.7f -> StatusClear
                            confidenceScore >= 0.4f -> StatusSuspicious
                            else -> AccentBlue
                        },
                        trackColor = TextSecondary.copy(alpha = 0.2f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            confidenceScore >= 0.7f -> "High confidence"
                            confidenceScore >= 0.4f -> "Improving..."
                            else -> "Collecting data..."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )

                // OSM attribution
                Text(
                    text = "© OpenStreetMap · © CARTO",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                )
            }
        }
    }
}

// ── Animation helpers ────────────────────────────────────────────────────

/**
 * Smoothly animate the marker and accuracy circle from one position to another.
 * Uses a Handler to post animation frames on the main thread.
 */
private fun animateMapUpdate(
    mapView: MapView,
    marker: Marker,
    circle: Polygon,
    fromLat: Double,
    fromLng: Double,
    fromAccuracy: Double,
    toLat: Double,
    toLng: Double,
    toAccuracy: Double,
    label: String,
    durationMs: Long = 1000L
) {
    val handler = Handler(Looper.getMainLooper())
    val startTime = System.currentTimeMillis()
    val frameIntervalMs = 16L // ~60 fps

    val animator = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - startTime
            val rawProgress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
            // Ease-out cubic for smooth deceleration
            val t = 1f - (1f - rawProgress) * (1f - rawProgress) * (1f - rawProgress)

            val currentLat = fromLat + (toLat - fromLat) * t
            val currentLng = fromLng + (toLng - fromLng) * t
            val currentAccuracy = fromAccuracy + (toAccuracy - fromAccuracy) * t

            val point = GeoPoint(currentLat, currentLng)
            marker.position = point
            marker.title = label
            circle.points = Polygon.pointsAsCircle(point, currentAccuracy)
            circle.title = "Estimated accuracy: ${currentAccuracy.toInt()}m"
            mapView.invalidate()

            if (rawProgress < 1f) {
                handler.postDelayed(this, frameIntervalMs)
            }
        }
    }
    handler.post(animator)
}

private fun computeZoomLevel(accuracyM: Double): Double = when {
    accuracyM > 2000 -> 13.0
    accuracyM > 1000 -> 14.0
    accuracyM > 500 -> 15.0
    accuracyM > 200 -> 16.0
    accuracyM > 100 -> 16.5
    accuracyM > 50 -> 17.0
    else -> 17.5
}

private fun createThreatMarkerBitmap(): Bitmap {
    return Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.drawCircle(24f, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x60EF4444.toInt()
            style = Paint.Style.FILL
        })
        c.drawCircle(24f, 24f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEF4444.toInt()
            style = Paint.Style.FILL
        })
        c.drawCircle(24f, 24f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 18f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        c.drawText("!", 24f, 30f, textPaint)
    }
}

private fun createUserMarkerBitmap(): Bitmap {
    return Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.drawCircle(14f, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x554488FF.toInt()
            style = Paint.Style.FILL
        })
        c.drawCircle(14f, 14f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4488FF.toInt()
            style = Paint.Style.FILL
        })
        c.drawCircle(14f, 14f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })
    }
}
