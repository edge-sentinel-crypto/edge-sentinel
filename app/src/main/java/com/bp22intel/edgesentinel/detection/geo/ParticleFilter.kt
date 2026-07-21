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
import java.util.Random
private val rng = Random()

/**
 * An RF observation from a tower or access point, used to weight particles.
 *
 * @param lat Tower/AP latitude
 * @param lng Tower/AP longitude
 * @param estimatedDistanceM Estimated distance from user to tower (from RSSI/TA)
 * @param accuracyM Accuracy of the distance estimate
 */
data class RfObservation(
    val lat: Double,
    val lng: Double,
    val estimatedDistanceM: Double,
    val accuracyM: Double
)

/**
 * Simple particle filter for user position tracking.
 *
 * Maintains a cloud of particles representing possible user positions,
 * refining them using RF observations (tower distances) and motion
 * predictions (step count + heading from accelerometer/compass).
 *
 * The filter output is a more refined user position estimate that
 * improves threat triangulation accuracy.
 */
class ParticleFilter(private val numParticles: Int = 200) {

    data class Particle(var lat: Double, var lng: Double, var weight: Double = 1.0)

    private val particles = mutableListOf<Particle>()
    private var initialized = false

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
        /** Noise added to particle motion predictions (meters). */
        private const val MOTION_NOISE_M = 2.0
        /** Minimum effective sample size ratio before resampling. */
        private const val RESAMPLE_THRESHOLD = 0.5
    }

    val isInitialized: Boolean get() = initialized

    /**
     * Scatter particles in a circle around an initial position.
     *
     * @param lat Initial latitude (e.g., GPS position)
     * @param lng Initial longitude (e.g., GPS position)
     * @param radiusM Scatter radius in meters
     */
    fun initialize(lat: Double, lng: Double, radiusM: Double) {
        particles.clear()
        repeat(numParticles) {
            val angle = rng.nextDouble() * 2.0 * PI
            val dist = rng.nextDouble() * radiusM
            val (pLat, pLng) = offsetPosition(lat, lng, dist, Math.toDegrees(angle))
            particles.add(Particle(pLat, pLng, 1.0 / numParticles))
        }
        initialized = true
    }

    /**
     * Predict step: move each particle by estimated step + noise.
     *
     * @param stepLengthM Estimated step length from accelerometer
     * @param headingDeg Compass heading in degrees [0, 360)
     */
    fun predict(stepLengthM: Double, headingDeg: Double) {
        if (!initialized) return
        for (p in particles) {
            // Add Gaussian noise to both step length and heading
            val noisyStep = stepLengthM + rng.nextGaussian() * MOTION_NOISE_M
            val noisyHeading = headingDeg + rng.nextGaussian() * 10.0 // ±10° noise
            val (newLat, newLng) = offsetPosition(p.lat, p.lng, noisyStep.coerceAtLeast(0.0), noisyHeading)
            p.lat = newLat
            p.lng = newLng
        }
    }

    /**
     * Update step: weight particles by how well they match RF observations.
     *
     * Each observation provides a tower position + estimated distance.
     * Particles closer to the expected distance get higher weight.
     */
    fun update(observations: List<RfObservation>) {
        if (!initialized || observations.isEmpty()) return

        for (p in particles) {
            var logLikelihood = 0.0
            for (obs in observations) {
                val particleDistToTower = haversineM(p.lat, p.lng, obs.lat, obs.lng)
                val error = particleDistToTower - obs.estimatedDistanceM
                val sigma = obs.accuracyM.coerceAtLeast(10.0)
                // Gaussian likelihood
                logLikelihood += -(error * error) / (2.0 * sigma * sigma)
            }
            p.weight *= exp(logLikelihood)
        }

        // Normalize weights
        val totalWeight = particles.sumOf { it.weight }
        if (totalWeight > 0) {
            for (p in particles) {
                p.weight /= totalWeight
            }
        } else {
            // All weights collapsed — reinitialize uniformly
            val uniform = 1.0 / particles.size
            for (p in particles) {
                p.weight = uniform
            }
        }

        // Resample if effective sample size is too low
        val nEff = 1.0 / particles.sumOf { it.weight * it.weight }
        if (nEff < numParticles * RESAMPLE_THRESHOLD) {
            resample()
        }
    }

    /**
     * Systematic resampling: replicate high-weight particles, discard low-weight ones.
     */
    fun resample() {
        if (!initialized || particles.isEmpty()) return

        val newParticles = mutableListOf<Particle>()
        val n = particles.size
        val cumWeights = DoubleArray(n)
        cumWeights[0] = particles[0].weight
        for (i in 1 until n) {
            cumWeights[i] = cumWeights[i - 1] + particles[i].weight
        }

        val step = 1.0 / n
        var u = rng.nextDouble() * step
        var idx = 0

        repeat(n) {
            while (idx < n - 1 && cumWeights[idx] < u) idx++
            val src = particles[idx]
            // Add small jitter to avoid particle collapse
            val jitterLat = rng.nextGaussian() * 0.0000005 // ~0.05m
            val jitterLng = rng.nextGaussian() * 0.0000005
            newParticles.add(Particle(src.lat + jitterLat, src.lng + jitterLng, 1.0 / n))
            u += step
        }

        particles.clear()
        particles.addAll(newParticles)
    }

    /**
     * Get the weighted mean position estimate.
     *
     * @return (latitude, longitude) or null if not initialized
     */
    fun getEstimate(): Pair<Double, Double>? {
        if (!initialized || particles.isEmpty()) return null
        val lat = particles.sumOf { it.lat * it.weight }
        val lng = particles.sumOf { it.lng * it.weight }
        return Pair(lat, lng)
    }

    /**
     * Get accuracy estimate as standard deviation of particle positions (meters).
     */
    fun getAccuracyM(): Double {
        if (!initialized || particles.isEmpty()) return Double.MAX_VALUE
        val (meanLat, meanLng) = getEstimate() ?: return Double.MAX_VALUE
        val variance = particles.sumOf { p ->
            val d = haversineM(p.lat, p.lng, meanLat, meanLng)
            p.weight * d * d
        }
        return sqrt(variance).coerceAtLeast(1.0)
    }

    // ── Geo helpers ──────────────────────────────────────────────────────

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).pow(2)
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private fun offsetPosition(lat: Double, lng: Double, distM: Double, bearingDeg: Double): Pair<Double, Double> {
        val φ1 = Math.toRadians(lat)
        val λ1 = Math.toRadians(lng)
        val θ = Math.toRadians(bearingDeg)
        val δ = distM / EARTH_RADIUS_M

        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return Pair(Math.toDegrees(φ2), Math.toDegrees(λ2))
    }
}
