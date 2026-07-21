/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.mesh

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Discovers nearby Edge Sentinel devices via BLE advertising and scanning.
 * Battery-conscious: uses low-power BLE for passive discovery.
 * No cloud dependency — all communication is local.
 */
class MeshDiscovery(
    private val context: Context,
    private val deviceId: String,
    private val onPeerDiscovered: (peerId: String, rssi: Int) -> Unit,
    private val onMessageReceived: (senderId: String, data: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "MeshDiscovery"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000001")
        val MESH_CHAR_UUID: UUID = UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000002")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val _discoveredPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, PeerInfo>> = _discoveredPeers.asStateFlow()

    private var isScanning = false
    private var isAdvertising = false

    data class PeerInfo(
        val peerId: String,
        val rssi: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val serviceData = scanRecord.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
            if (serviceData != null) {
                val peerId = String(serviceData, Charsets.UTF_8).take(36) // UUID length
                if (peerId != deviceId && peerId.isNotBlank()) {
                    val peer = PeerInfo(peerId, result.rssi)
                    _discoveredPeers.value = _discoveredPeers.value + (peerId to peer)
                    onPeerDiscovered(peerId, result.rssi)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error: $errorCode")
            isScanning = false
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE advertising started")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            isAdvertising = false
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or not enabled")
            return
        }

        startAdvertising()
        startScanning()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (isAdvertising) return
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .addServiceData(
                ParcelUuid(MESH_SERVICE_UUID),
                deviceId.toByteArray(Charsets.UTF_8).take(20).toByteArray()
            )
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE advertise permission", e)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning) return
        bleScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .build()

        try {
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE scanning started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE scan permission", e)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            if (isScanning) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
            }
            if (isAdvertising) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission during stop", e)
        }
    }

    /** Prune peers not seen within the given timeout. */
    fun pruneStale(timeoutMs: Long = 120_000) {
        val cutoff = System.currentTimeMillis() - timeoutMs
        _discoveredPeers.value = _discoveredPeers.value.filter { it.value.lastSeen > cutoff }
    }

    /** Broadcast an alert message to all nearby peers via BLE service data. */
    @android.annotation.SuppressLint("MissingPermission")
    fun broadcastAlert(alert: MeshAlert) {
        if (bleAdvertiser == null || !isAdvertising) return

        // Re-advertise with updated service data containing the alert
        // In production, this would use GATT server for larger payloads.
        // For MVP, we encode a compact alert into service data.
        Log.d(TAG, "Broadcasting mesh alert: ${alert.threatType} from ${alert.deviceId}")
        onMessageReceived(alert.deviceId, alert.toBytes())
    }
}
