/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.calibration

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalibrationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class CalibrationSample(
        val timestamp: Long,
        val gpsLat: Double,
        val gpsLng: Double,
        val gpsAccuracy: Float,
        val cellTowers: List<CellSample>,
        val wifiAps: List<WifiSample>,
        val bleDevices: List<BleSample>
    )
    
    data class CellSample(
        val mcc: Int, val mnc: Int, val lac: Int, val cid: Int,
        val rssi: Int, val radio: String
    )
    
    data class WifiSample(
        val bssid: String, val ssid: String, val rssi: Int, val frequencyMhz: Int
    )
    
    data class BleSample(
        val address: String, val rssi: Int, val name: String?
    )
    
    data class CalibrationResults(
        val totalSamples: Int,
        val durationMinutes: Double,
        val distanceTraveledMeters: Double,
        val cellAccuracy: AccuracyMetrics?,
        val wifiAccuracy: AccuracyMetrics?,
        val bleAccuracy: AccuracyMetrics?,
        val recommendedCellExponent: Double?,
        val recommendedWifiExponent: Double?,
        val recommendedBleExponent: Double?,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class AccuracyMetrics(
        val meanErrorMeters: Double,
        val medianErrorMeters: Double,
        val p90ErrorMeters: Double,
        val sampleCount: Int
    )
    
    private val _isCalibrating = MutableStateFlow(false)
    val isCalibrating: StateFlow<Boolean> = _isCalibrating.asStateFlow()
    
    private val _samples = MutableStateFlow<List<CalibrationSample>>(emptyList())
    val samples: StateFlow<List<CalibrationSample>> = _samples.asStateFlow()
    
    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()
    
    private var calibrationJob: Job? = null
    private val locationManager by lazy { 
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager 
    }
    
    fun startCalibration() {
        if (_isCalibrating.value) return
        _isCalibrating.value = true
        _samples.value = emptyList()
        _sampleCount.value = 0
        
        calibrationJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && _isCalibrating.value) {
                collectSample()
                delay(5000) // Sample every 5 seconds
            }
        }
    }
    
    fun stopCalibration(): CalibrationResults? {
        _isCalibrating.value = false
        calibrationJob?.cancel()
        
        val sampleList = _samples.value
        if (sampleList.size < 5) return null
        
        return analyzeCalibrationData(sampleList)
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun collectSample() {
        try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return
            
            if (location.accuracy > 20) return // Only use accurate GPS fixes
            
            val sample = CalibrationSample(
                timestamp = System.currentTimeMillis(),
                gpsLat = location.latitude,
                gpsLng = location.longitude,
                gpsAccuracy = location.accuracy,
                cellTowers = emptyList(), // Populated by integration with CellInfoCollector
                wifiAps = emptyList(),    // Populated by integration with WiFi scanner
                bleDevices = emptyList()   // Populated by integration with BLE scanner
            )
            
            _samples.value = _samples.value + sample
            _sampleCount.value = _samples.value.size
        } catch (_: Exception) { }
    }
    
    private fun analyzeCalibrationData(samples: List<CalibrationSample>): CalibrationResults {
        // Calculate distance traveled
        var totalDistance = 0.0
        for (i in 1 until samples.size) {
            totalDistance += haversineDistance(
                samples[i-1].gpsLat, samples[i-1].gpsLng,
                samples[i].gpsLat, samples[i].gpsLng
            )
        }
        
        val durationMs = samples.last().timestamp - samples.first().timestamp
        
        return CalibrationResults(
            totalSamples = samples.size,
            durationMinutes = durationMs / 60000.0,
            distanceTraveledMeters = totalDistance,
            cellAccuracy = null, // Would need tower DB to compute
            wifiAccuracy = null,
            bleAccuracy = null,
            recommendedCellExponent = null,
            recommendedWifiExponent = null,
            recommendedBleExponent = null
        )
    }
    
    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
    
    /**
     * Export calibration data as JSON for analysis
     */
    fun exportCalibrationData(): String {
        // Simple JSON export - in production, use proper JSON library
        val samples = _samples.value
        return "{ \"samples\": ${samples.size}, \"timestamp\": ${System.currentTimeMillis()} }"
    }
}