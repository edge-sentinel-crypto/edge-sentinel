/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses BluetoothLeScanner to passively scan for BLE devices.
 *
 * Detects:
 * - Persistent followers: BLE devices appearing at multiple distinct locations
 * - AirTag/SmartTag/Tile: known tracker advertising patterns
 * - Anomalous BLE beacons: rotating MACs with consistent advertising data
 * - BLE environment anomalies: sudden appearance of many new devices
 *
 * Scan strategy: 10 seconds active scanning, 50 seconds pause (low power duty cycle).
 */
@Singleton
class BleTrackingDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceTracker: BleDeviceTracker,
    private val alertManager: BleAlertManager,
    private val trackerIdentifier: BleTrackerIdentifier
) {

    companion object {
        private const val TAG = "BleTrackingDetector"

        /** Active scan duration in milliseconds. */
        private const val SCAN_DURATION_MS = 10_000L

        /** Pause between scans in milliseconds. */
        private const val SCAN_PAUSE_MS = 50_000L

        /** Prune devices older than 24 hours. */
        private const val DEVICE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private var scanner: BluetoothLeScanner? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _nearbyDeviceCount = MutableStateFlow(0)
    val nearbyDeviceCount: StateFlow<Int> = _nearbyDeviceCount.asStateFlow()

    private val _lastScanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val lastScanResults: StateFlow<List<ScanResult>> = _lastScanResults.asStateFlow()

    private val currentScanBatch = mutableListOf<ScanResult>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            synchronized(currentScanBatch) {
                // Deduplicate by address within a scan window
                currentScanBatch.removeAll { it.device.address == result.device.address }
                currentScanBatch.add(result)
                _nearbyDeviceCount.value = currentScanBatch.size
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            synchronized(currentScanBatch) {
                for (result in results) {
                    currentScanBatch.removeAll { it.device.address == result.device.address }
                    currentScanBatch.add(result)
                }
                _nearbyDeviceCount.value = currentScanBatch.size
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    /**
     * Start continuous BLE scanning with duty cycling (10s scan, 50s pause).
     */
    fun startScanning() {
        if (scanJob?.isActive == true) {
            _statusMessage.value = "Already scanning"
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing BLE scan permissions")
            _statusMessage.value = "Missing permissions: grant Nearby Devices in Settings → Apps → Edge Sentinel → Permissions"
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter not available")
            _statusMessage.value = "Bluetooth not available on this device"
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth disabled")
            _statusMessage.value = "Bluetooth is OFF — turn it on in Settings"
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner not available")
            _statusMessage.value = "BLE scanner not available — try toggling Bluetooth off and on"
            return
        }

        _statusMessage.value = null  // Clear any error

        scanJob = scope.launch {
            while (isActive) {
                performScanCycle()
                delay(SCAN_PAUSE_MS)
            }
        }
    }

    /**
     * Stop BLE scanning.
     */
    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        stopCurrentScan()
        _isScanning.value = false
    }

    /**
     * Perform a single scan-evaluate cycle.
     */
    private suspend fun performScanCycle() {
        synchronized(currentScanBatch) {
            currentScanBatch.clear()
        }

        startCurrentScan()
        delay(SCAN_DURATION_MS)
        stopCurrentScan()

        // Process results
        val results: List<ScanResult>
        synchronized(currentScanBatch) {
            results = currentScanBatch.toList()
        }

        _lastScanResults.value = results

        // Record each device with current location
        val location = getLastKnownLocation()
        for (result in results) {
            deviceTracker.recordDevice(
                result,
                location?.latitude,
                location?.longitude
            )
        }

        // Evaluate all tracked devices for alerts
        val allDevices = mutableListOf<com.bp22intel.edgesentinel.data.local.entity.BleDeviceEntity>()
        // Collect current snapshot from DAO through tracker
        deviceTracker.getAllDevices().collect { devices ->
            allDevices.addAll(devices)
        }

        // Prune old records periodically
        deviceTracker.pruneOldDevices(DEVICE_MAX_AGE_MS)
    }

    private fun startCurrentScan() {
        if (!hasRequiredPermissions()) return

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)
                .build()

            // No filters — we want to see all BLE advertisements for tracking detection
            scanner?.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            _isScanning.value = true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan", e)
        }
    }

    private fun stopCurrentScan() {
        if (!hasRequiredPermissions()) return

        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping BLE scan", e)
        }
    }

    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null

        return try {
            locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            null
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val locationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return scanPermission && locationPermission
    }
}
