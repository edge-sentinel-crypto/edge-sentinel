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

/**
 * Simple 1D Kalman filter for smoothing RSSI readings.
 *
 * Applied per-device (keyed by BLE MAC or WiFi BSSID) to reduce jitter
 * in raw signal strength measurements before distance estimation.
 * Typical improvement: 3-5m jitter → 1.5-2.5m accuracy for BLE.
 */
class RssiKalmanFilter(
    private var estimate: Double = -70.0,
    private var errorEstimate: Double = 5.0,
    private val processNoise: Double = 1.0,
    private val measurementNoise: Double = 3.0
) {
    /**
     * Feed a new RSSI measurement and return the smoothed estimate.
     */
    fun update(measurement: Double): Double {
        val kalmanGain = errorEstimate / (errorEstimate + measurementNoise)
        estimate += kalmanGain * (measurement - estimate)
        errorEstimate = (1.0 - kalmanGain) * errorEstimate + processNoise
        return estimate
    }

    /** Current smoothed RSSI estimate. */
    fun currentEstimate(): Double = estimate

    /** Reset the filter to initial state with a new seed measurement. */
    fun reset(seedRssi: Double = -70.0) {
        estimate = seedRssi
        errorEstimate = 5.0
    }
}
