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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

private const val TAG = "MeshBleServer"

/**
 * BLE GATT Server for the Edge Sentinel cooperative mesh network.
 *
 * Advertises the Edge Sentinel service UUID so other devices can discover us,
 * and serves two GATT characteristics:
 * - OBSERVATION_SHARE (read): other devices read our current observations
 * - OBSERVATION_RECEIVE (write): other devices write their observations to us
 *
 * All data exchange is via BLE GATT — no WiFi, no internet.
 */
class MeshBleServer(
    private val context: Context,
    private val onObservationReceived: (CooperativeObservation) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000001")
        val OBSERVATION_SHARE_UUID: UUID = UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000010")
        val OBSERVATION_RECEIVE_UUID: UUID = UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000011")
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        try {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (e: Exception) {
            Log.w(TAG, "Could not get BluetoothManager: ${e.message}")
            null
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private var isServerRunning = false

    /** Current observations to share with peers. Thread-safe via synchronized. */
    private val currentObservations = mutableListOf<CooperativeObservation>()

    /** Connected devices for tracking. */
    private val connectedDevices = mutableSetOf<String>()

    val connectedDeviceCount: Int get() = synchronized(connectedDevices) { connectedDevices.size }

    // ── GATT Server Callback ─────────────────────────────────────────────

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT peer connected: ${address.takeLast(5)}")
                    synchronized(connectedDevices) { connectedDevices.add(address) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT peer disconnected: ${address.takeLast(5)}")
                    synchronized(connectedDevices) { connectedDevices.remove(address) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            when (characteristic?.uuid) {
                OBSERVATION_SHARE_UUID -> {
                    // Peer is reading our observations
                    val data = synchronized(currentObservations) {
                        CooperativeObservation.listToJson(currentObservations.toList())
                    }.toByteArray(Charsets.UTF_8)

                    // Handle offset for large payloads
                    val responseData = if (offset < data.size) {
                        data.copyOfRange(offset, data.size)
                    } else {
                        ByteArray(0)
                    }

                    Log.d(TAG, "Read request from ${device?.address?.takeLast(5)}: " +
                        "${currentObservations.size} observations, ${data.size} bytes")

                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData
                    )
                }
                else -> {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, 0, null
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            when (characteristic?.uuid) {
                OBSERVATION_RECEIVE_UUID -> {
                    // Peer is sending us their observations
                    val data = value ?: ByteArray(0)
                    val json = String(data, Charsets.UTF_8)

                    Log.d(TAG, "Write request from ${device?.address?.takeLast(5)}: ${data.size} bytes")

                    // Parse and dispatch observations
                    try {
                        val observations = CooperativeObservation.listFromJson(json)
                        observations.forEach { obs ->
                            onObservationReceived(obs)
                        }
                        Log.d(TAG, "Received ${observations.size} observations from peer")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse peer observation: ${e.message}")
                    }

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null
                        )
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_FAILURE, 0, null
                        )
                    }
                }
            }
        }
    }

    // ── Advertise Callback ───────────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown ($errorCode)"
            }
            Log.w(TAG, "BLE advertising failed: $reason")
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Start the GATT server and BLE advertising.
     * Requires BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT permissions (Android 12+).
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isServerRunning) return true

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing required BLE permissions")
            return false
        }

        val manager = bluetoothManager ?: run {
            Log.w(TAG, "BluetoothManager not available")
            return false
        }

        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "BluetoothAdapter not available")
            return false
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not enabled")
            return false
        }

        // 1. Open GATT server
        gattServer = manager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }

        // 2. Create service with characteristics
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Read characteristic: peers read our observations
        val shareCharacteristic = BluetoothGattCharacteristic(
            OBSERVATION_SHARE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Write characteristic: peers write their observations to us
        val receiveCharacteristic = BluetoothGattCharacteristic(
            OBSERVATION_RECEIVE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(shareCharacteristic)
        service.addCharacteristic(receiveCharacteristic)

        val added = gattServer?.addService(service) ?: false
        if (!added) {
            Log.e(TAG, "Failed to add GATT service")
            gattServer?.close()
            gattServer = null
            return false
        }

        isServerRunning = true
        Log.d(TAG, "GATT server started with cooperative observation service")

        // 3. Start BLE advertising
        startAdvertising(adapter)

        return true
    }

    /**
     * Stop the GATT server and BLE advertising.
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        // Stop advertising
        if (isAdvertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping advertising: ${e.message}")
            }
            isAdvertising = false
        }
        advertiser = null

        // Close GATT server
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
        isServerRunning = false

        synchronized(connectedDevices) { connectedDevices.clear() }
        synchronized(currentObservations) { currentObservations.clear() }

        Log.d(TAG, "GATT server and advertising stopped")
    }

    /**
     * Update the observations available for peers to read.
     * Called periodically (every 30s) when threats are active.
     */
    fun updateObservations(observations: List<CooperativeObservation>) {
        synchronized(currentObservations) {
            currentObservations.clear()
            currentObservations.addAll(observations)
        }
        Log.d(TAG, "Updated shared observations: ${observations.size}")
    }

    val isRunning: Boolean get() = isServerRunning

    // ── Private Helpers ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE advertising not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising: ${e.message}")
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-S doesn't need these specific permissions
        }
    }
}
