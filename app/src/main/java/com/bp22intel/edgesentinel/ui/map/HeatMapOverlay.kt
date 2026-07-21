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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import com.bp22intel.edgesentinel.detection.geo.HeatMapPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Efficient heat map overlay that draws batched colored circles for
 * signal strength readings directly onto the osmdroid MapView canvas.
 *
 * Instead of creating thousands of individual overlays, this renders
 * all points in a single draw pass using four pre-allocated Paint objects.
 *
 * Color scheme:
 * - Strong (> -60 dBm): bright red, 30m radius
 * - Medium (-60 to -80 dBm): orange/yellow, 25m radius
 * - Weak (-80 to -100 dBm): teal/green, 20m radius
 * - Faint (< -100 dBm): cool blue, 15m radius
 *
 * Peer points use cooler variants (cyan/blue shades).
 */
class HeatMapOverlay : Overlay() {

    /** Current heat map points to render. Thread-safe update via [setPoints]. */
    @Volatile
    private var points: List<HeatMapPoint> = emptyList()

    // Pre-allocated paints for each signal tier (local device = warm colors)
    private val paintStrongLocal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FF1744.toInt() // Bright red, ~60% alpha
        style = Paint.Style.FILL
    }
    private val paintMediumLocal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FF9100.toInt() // Orange, ~53% alpha
        style = Paint.Style.FILL
    }
    private val paintWeakLocal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x7710B981.toInt() // Teal/green, ~47% alpha
        style = Paint.Style.FILL
    }
    private val paintFaintLocal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x553B82F6.toInt() // Blue, ~33% alpha
        style = Paint.Style.FILL
    }

    // Peer paints (cool colors)
    private val paintStrongPeer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x9906B6D4.toInt() // Cyan
        style = Paint.Style.FILL
    }
    private val paintMediumPeer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x883B82F6.toInt() // Blue
        style = Paint.Style.FILL
    }
    private val paintWeakPeer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x776366F1.toInt() // Indigo
        style = Paint.Style.FILL
    }
    private val paintFaintPeer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x558B5CF6.toInt() // Purple
        style = Paint.Style.FILL
    }

    // Reusable point to avoid allocations in draw loop
    private val screenPoint = Point()

    /**
     * Update the points to render. Call from the UI thread or a coroutine
     * that coordinates with MapView invalidation.
     */
    fun setPoints(newPoints: List<HeatMapPoint>) {
        points = newPoints
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pts = points
        if (pts.isEmpty()) return

        val projection: Projection = mapView.projection

        // Calculate pixel-per-meter at current zoom to convert meter radii to pixels
        // Use center of visible bounding box for reference
        val boundingBox = mapView.boundingBox
        val centerLat = (boundingBox.latNorth + boundingBox.latSouth) / 2.0
        val centerGeo = GeoPoint(centerLat, boundingBox.lonWest)
        val offsetGeo = GeoPoint(centerLat, boundingBox.lonWest + 0.001)
        val p1 = Point()
        val p2 = Point()
        projection.toPixels(centerGeo, p1)
        projection.toPixels(offsetGeo, p2)
        val pixelsPer001Deg = (p2.x - p1.x).toFloat()
        // 0.001° longitude ≈ 111m * cos(lat) * 0.001 ≈ meters
        val metersPerDeg = 111_000.0 * Math.cos(Math.toRadians(centerLat))
        val metersIn001Deg = (metersPerDeg * 0.001).toFloat()
        val pixelsPerMeter = if (metersIn001Deg > 0) pixelsPer001Deg / metersIn001Deg else 1f

        // Minimum 3px radius so dots are always visible
        val strongRadiusPx = (30f * pixelsPerMeter).coerceAtLeast(6f)
        val mediumRadiusPx = (25f * pixelsPerMeter).coerceAtLeast(5f)
        val weakRadiusPx = (20f * pixelsPerMeter).coerceAtLeast(4f)
        val faintRadiusPx = (15f * pixelsPerMeter).coerceAtLeast(3f)

        // Visible bounds check
        val canvasWidth = canvas.width
        val canvasHeight = canvas.height
        val margin = strongRadiusPx.toInt() + 2

        for (point in pts) {
            val geo = GeoPoint(point.lat, point.lng)
            projection.toPixels(geo, screenPoint)

            // Frustum cull
            if (screenPoint.x < -margin || screenPoint.x > canvasWidth + margin) continue
            if (screenPoint.y < -margin || screenPoint.y > canvasHeight + margin) continue

            val (paint, radius) = selectPaintAndRadius(
                point.rssi, point.isPeer,
                strongRadiusPx, mediumRadiusPx, weakRadiusPx, faintRadiusPx
            )

            canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, paint)
        }
    }

    private fun selectPaintAndRadius(
        rssi: Int,
        isPeer: Boolean,
        strongR: Float, mediumR: Float, weakR: Float, faintR: Float
    ): Pair<Paint, Float> {
        return when {
            rssi > -60 -> if (isPeer) Pair(paintStrongPeer, strongR)
                          else Pair(paintStrongLocal, strongR)
            rssi > -80 -> if (isPeer) Pair(paintMediumPeer, mediumR)
                          else Pair(paintMediumLocal, mediumR)
            rssi > -100 -> if (isPeer) Pair(paintWeakPeer, weakR)
                           else Pair(paintWeakLocal, weakR)
            else -> if (isPeer) Pair(paintFaintPeer, faintR)
                    else Pair(paintFaintLocal, faintR)
        }
    }
}
