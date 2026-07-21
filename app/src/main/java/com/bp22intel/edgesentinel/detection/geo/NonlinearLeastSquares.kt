/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.geo

import kotlin.math.*

/**
 * Lightweight nonlinear least-squares trilateration solver.
 *
 * Implements Gauss-Newton iteration to find the position that minimizes
 * the sum of squared errors between observed distances and computed
 * distances to known anchor points.
 *
 * This replaces the external `com.lemmingapex:trilateration` library
 * to avoid dependency risk (small Maven library) and keep everything
 * on-device with zero network calls.
 */
object NonlinearLeastSquares {

    /**
     * Result of a trilateration solve.
     *
     * @param latitude Estimated latitude
     * @param longitude Estimated longitude
     * @param residualM RMS residual in meters (lower = better fit)
     */
    data class TrilaterationResult(
        val latitude: Double,
        val longitude: Double,
        val residualM: Double
    )

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MAX_ITERATIONS = 20
    private const val CONVERGENCE_THRESHOLD_M = 0.5

    /**
     * Trilaterate a position from 3+ anchor points with distance measurements.
     *
     * Uses Gauss-Newton optimization on a local Cartesian projection
     * (centered at the centroid of anchors) to avoid issues with
     * latitude/longitude nonlinearity at small scales.
     *
     * @param positions Array of [latitude, longitude] for each anchor
     * @param distances Array of estimated distances in meters to each anchor
     * @return Trilateration result, or null if inputs are degenerate
     */
    fun solve(
        positions: Array<DoubleArray>,
        distances: DoubleArray
    ): TrilaterationResult? {
        val n = positions.size
        if (n < 3 || distances.size != n) return null

        // Compute centroid as projection origin
        val centLat = positions.sumOf { it[0] } / n
        val centLng = positions.sumOf { it[1] } / n

        // Convert to local meters (ENU-like flat earth approximation)
        val mPerDegLat = EARTH_RADIUS_M * PI / 180.0
        val mPerDegLng = mPerDegLat * cos(Math.toRadians(centLat))

        // Anchor positions in local meters
        val anchorsX = DoubleArray(n) { (positions[it][1] - centLng) * mPerDegLng }
        val anchorsY = DoubleArray(n) { (positions[it][0] - centLat) * mPerDegLat }

        // Initial guess: weighted centroid (closer distances = more weight)
        var x = 0.0; var y = 0.0; var wSum = 0.0
        for (i in 0 until n) {
            val w = 1.0 / distances[i].coerceAtLeast(1.0)
            x += anchorsX[i] * w
            y += anchorsY[i] * w
            wSum += w
        }
        x /= wSum
        y /= wSum

        // Gauss-Newton iterations
        repeat(MAX_ITERATIONS) {
            // Compute residuals and Jacobian
            val residuals = DoubleArray(n)
            val jacobian = Array(n) { DoubleArray(2) }

            for (i in 0 until n) {
                val dx = x - anchorsX[i]
                val dy = y - anchorsY[i]
                val computedDist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01)
                residuals[i] = computedDist - distances[i]
                jacobian[i][0] = dx / computedDist  // ∂r/∂x
                jacobian[i][1] = dy / computedDist  // ∂r/∂y
            }

            // J^T * J (2x2 matrix)
            var jtj00 = 0.0; var jtj01 = 0.0; var jtj11 = 0.0
            var jtr0 = 0.0; var jtr1 = 0.0
            for (i in 0 until n) {
                jtj00 += jacobian[i][0] * jacobian[i][0]
                jtj01 += jacobian[i][0] * jacobian[i][1]
                jtj11 += jacobian[i][1] * jacobian[i][1]
                jtr0 += jacobian[i][0] * residuals[i]
                jtr1 += jacobian[i][1] * residuals[i]
            }

            // Solve 2x2 system: (J^T J) delta = -J^T r
            val det = jtj00 * jtj11 - jtj01 * jtj01
            if (abs(det) < 1e-12) return@repeat // degenerate

            val deltaX = -(jtj11 * jtr0 - jtj01 * jtr1) / det
            val deltaY = -(-jtj01 * jtr0 + jtj00 * jtr1) / det

            x += deltaX
            y += deltaY

            // Check convergence
            if (sqrt(deltaX * deltaX + deltaY * deltaY) < CONVERGENCE_THRESHOLD_M) {
                return@repeat
            }
        }

        // Convert back to lat/lng
        val resultLat = centLat + y / mPerDegLat
        val resultLng = centLng + x / mPerDegLng

        // Compute RMS residual
        var rmsSum = 0.0
        for (i in 0 until n) {
            val dx = x - anchorsX[i]
            val dy = y - anchorsY[i]
            val err = sqrt(dx * dx + dy * dy) - distances[i]
            rmsSum += err * err
        }
        val rms = sqrt(rmsSum / n)

        return TrilaterationResult(resultLat, resultLng, rms)
    }
}
