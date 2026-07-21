/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Detects device motion state using the accelerometer.
 *
 * Classification thresholds (acceleration magnitude variance over a sliding window):
 * - STATIONARY: variance < 0.3 sustained for 30+ seconds
 * - WALKING: variance in [0.3, 8.0)
 * - DRIVING: variance >= 8.0 with sustained readings
 */
@Singleton
class MotionDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    companion object {
        /** Sliding window size for variance calculation. */
        private const val WINDOW_SIZE = 100

        /** Variance threshold below which the device is considered stationary. */
        private const val STATIONARY_VARIANCE_THRESHOLD = 0.3

        /** Variance threshold above which the device is likely in a vehicle. */
        private const val DRIVING_VARIANCE_THRESHOLD = 8.0

        /**
         * Duration (ms) the variance must remain below the stationary threshold
         * before committing to STATIONARY state.
         */
        private const val STATIONARY_HOLD_MS = 30_000L

        /** Minimum consecutive driving-level samples before committing to DRIVING. */
        private const val DRIVING_SUSTAINED_COUNT = 20
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _motionState = MutableStateFlow(MotionState.UNKNOWN)
    val motionState: StateFlow<MotionState> = _motionState.asStateFlow()

    /** Whether the device is currently in motion (walking or driving). */
    val isMoving: Boolean
        get() = _motionState.value == MotionState.WALKING ||
                _motionState.value == MotionState.DRIVING

    // Ring buffer for magnitude samples
    private val magnitudeBuffer = FloatArray(WINDOW_SIZE)
    private var bufferIndex = 0
    private var sampleCount = 0

    // Timing for stationary hold
    private var stationarySinceMs = 0L

    // Counter for sustained driving-level variance
    private var drivingSustainedCount = 0

    private var isListening = false

    /**
     * Start listening to accelerometer events.
     * Safe to call multiple times — duplicate registrations are ignored.
     */
    fun start() {
        if (isListening || accelerometer == null) return
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        isListening = true
    }

    /**
     * Stop listening. Resets state to UNKNOWN.
     */
    fun stop() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
        reset()
    }

    // ---- SensorEventListener ------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Magnitude of the acceleration vector (includes gravity ~9.8)
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // Store in ring buffer
        magnitudeBuffer[bufferIndex % WINDOW_SIZE] = magnitude
        bufferIndex++
        sampleCount++

        if (sampleCount < WINDOW_SIZE) return // wait for full window

        val variance = computeVariance()
        classify(variance)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    // ---- Internal -----------------------------------------------------------

    private fun computeVariance(): Double {
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until WINDOW_SIZE) {
            val v = magnitudeBuffer[i].toDouble()
            sum += v
            sumSq += v * v
        }
        val mean = sum / WINDOW_SIZE
        return (sumSq / WINDOW_SIZE) - (mean * mean)
    }

    private fun classify(variance: Double) {
        val now = System.currentTimeMillis()

        when {
            variance < STATIONARY_VARIANCE_THRESHOLD -> {
                drivingSustainedCount = 0
                if (stationarySinceMs == 0L) {
                    stationarySinceMs = now
                }
                if (now - stationarySinceMs >= STATIONARY_HOLD_MS) {
                    _motionState.value = MotionState.STATIONARY
                }
                // While waiting for the hold period, keep previous state
                // (avoids flickering on brief stops)
            }
            variance >= DRIVING_VARIANCE_THRESHOLD -> {
                stationarySinceMs = 0L
                drivingSustainedCount++
                if (drivingSustainedCount >= DRIVING_SUSTAINED_COUNT) {
                    _motionState.value = MotionState.DRIVING
                } else if (_motionState.value != MotionState.DRIVING) {
                    _motionState.value = MotionState.WALKING
                }
            }
            else -> {
                // Moderate variance → walking
                stationarySinceMs = 0L
                drivingSustainedCount = 0
                _motionState.value = MotionState.WALKING
            }
        }
    }

    private fun reset() {
        _motionState.value = MotionState.UNKNOWN
        bufferIndex = 0
        sampleCount = 0
        stationarySinceMs = 0L
        drivingSustainedCount = 0
        magnitudeBuffer.fill(0f)
    }
}
