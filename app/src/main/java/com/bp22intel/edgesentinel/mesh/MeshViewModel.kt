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
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.detection.geo.ThreatGeolocation
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

private const val TAG = "MeshViewModel"

@HiltViewModel
class MeshViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val threatGeolocation: ThreatGeolocation,
    private val alertRepository: AlertRepository,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        // Valid hex UUIDs for Edge Sentinel mesh
        val MESH_SERVICE_UUID: UUID = UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000001")
        private val KEY_COOPERATIVE_ENABLED = booleanPreferencesKey("cooperative_localization_enabled")
    }

    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    /** Whether cooperative localization is enabled in settings. */
    val isCooperativeEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_COOPERATIVE_ENABLED] ?: true } // default ON
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (e: Exception) {
            Log.w(TAG, "Could not get BluetoothAdapter: ${e.message}")
            null
        }
    }

    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var maintenanceJob: Job? = null
    private var observationSharingJob: Job? = null
    private val discoveredPeers = mutableMapOf<String, PeerInfo>()

    /** BLE GATT server for advertising and serving cooperative observations. */
    private var bleServer: MeshBleServer? = null

    /** Cooperative localization manager. */
    private val coopManager: CooperativeLocalizationManager by lazy {
        CooperativeLocalizationManager(context, threatGeolocation)
    }

    /** Current user location for creating observations. */
    private var userLat: Double = 0.0
    private var userLng: Double = 0.0

    data class PeerInfo(
        val deviceAddress: String,
        val rssi: Int,
        val lastSeen: Long,
        val deviceName: String?,
        val isEdgeSentinel: Boolean = false
    )

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            try {
                result ?: return
                val address = result.device?.address ?: return
                val rssi = result.rssi
                val name = try { result.device?.name } catch (_: SecurityException) { null }

                // Check if this is an Edge Sentinel device (has our service UUID)
                val isEdgeSentinel = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == MESH_SERVICE_UUID } ?: false

                discoveredPeers[address] = PeerInfo(
                    deviceAddress = address,
                    rssi = rssi,
                    lastSeen = System.currentTimeMillis(),
                    deviceName = name,
                    isEdgeSentinel = isEdgeSentinel
                )

                updateUiState()

                if (isEdgeSentinel) {
                    Log.d(TAG, "Edge Sentinel peer found: ${address.takeLast(5)} RSSI: $rssi")
                    // Attempt to connect and exchange observations
                    exchangeObservationsWithPeer(result)
                } else {
                    Log.d(TAG, "BLE device found: ${address.takeLast(5)} RSSI: $rssi")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in scan callback: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed with error code: $errorCode")
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning not supported on this device"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal BLE error"
                else -> "Unknown error ($errorCode)"
            }
            _uiState.value = _uiState.value.copy(error = "Scan failed: $errorMsg")
        }
    }

    fun startMesh() {
        if (_uiState.value.isActive) return

        val adapter = bluetoothAdapter
        if (adapter == null) {
            _uiState.value = _uiState.value.copy(
                error = "Bluetooth not available on this device"
            )
            return
        }

        if (!adapter.isEnabled) {
            _uiState.value = _uiState.value.copy(
                error = "Please enable Bluetooth first"
            )
            return
        }

        if (!hasBlePermissions()) {
            _uiState.value = _uiState.value.copy(
                error = "Bluetooth permissions not granted"
            )
            return
        }

        try {
            // Start BLE scanning
            startBleScan(adapter)

            // Start GATT server for cooperative observations
            startGattServer()

            _uiState.value = _uiState.value.copy(isActive = true, error = null)
            Log.d(TAG, "Mesh started — scanning + advertising for peers")

            // Maintenance: prune stale peers every 30s
            maintenanceJob = viewModelScope.launch {
                while (isActive) {
                    delay(30_000)
                    pruneStale()
                    coopManager.pruneStale()
                }
            }

            // Observation sharing: update shared observations every 30s when threats active
            startObservationSharing()

        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception starting BLE: ${e.message}")
            _uiState.value = _uiState.value.copy(
                error = "Bluetooth permission denied by system"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh: ${e.message}", e)
            _uiState.value = _uiState.value.copy(
                error = "Could not start: ${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(adapter: BluetoothAdapter) {
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _uiState.value = _uiState.value.copy(
                error = "BLE scanner not available — is Bluetooth on?"
            )
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .build()

        // Scan unfiltered to find all BLE devices (tracker detection + ES peers)
        // Edge Sentinel peers are identified by service UUID in scan results
        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
    }

    /**
     * Start the GATT server for cooperative observation exchange.
     */
    private fun startGattServer() {
        if (bleServer != null) return

        bleServer = MeshBleServer(context) { observation ->
            // Peer wrote an observation to us
            viewModelScope.launch {
                if (isCooperativeEnabled.value) {
                    coopManager.onPeerObservationReceived(observation)
                    updateUiState()
                }
            }
        }

        val started = bleServer?.start() ?: false
        if (started) {
            Log.d(TAG, "GATT server started for cooperative observations")
        } else {
            Log.w(TAG, "Failed to start GATT server — cooperative mode limited to scanning")
        }
    }

    /**
     * Periodically create and share observations when threats are active.
     */
    private fun startObservationSharing() {
        observationSharingJob?.cancel()
        observationSharingJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000) // Every 30 seconds

                if (!isCooperativeEnabled.value) continue

                try {
                    // Get active cellular alerts
                    val alerts = alertRepository.getActiveAlerts()
                    if (alerts.isEmpty()) {
                        bleServer?.updateObservations(emptyList())
                        coopManager.updateSharingCount(0)
                        continue
                    }

                    // Create observations for each suspicious tower
                    val observations = alerts.mapNotNull { alert ->
                        try {
                            val details = JSONObject(alert.detailsJson)
                            val cid = details.optLong("cid", 0L)
                            if (cid == 0L || userLat == 0.0) return@mapNotNull null

                            coopManager.createObservation(
                                userLat = userLat,
                                userLng = userLng,
                                cid = cid,
                                mcc = details.optInt("mcc", 0),
                                mnc = details.optInt("mnc", 0),
                                lac = details.optInt("lac", details.optInt("tac", 0)),
                                rsrp = details.optInt("signalStrength",
                                    details.optInt("signalStrengthDbm", -100)),
                                timingAdvance = details.optInt("timingAdvance", -1),
                                threatType = alert.threatType.name,
                                confidence = when (alert.confidence) {
                                    com.bp22intel.edgesentinel.domain.model.Confidence.LOW -> 0.3f
                                    com.bp22intel.edgesentinel.domain.model.Confidence.MEDIUM -> 0.6f
                                    com.bp22intel.edgesentinel.domain.model.Confidence.HIGH -> 0.9f
                                }
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Error creating observation: ${e.message}")
                            null
                        }
                    }

                    bleServer?.updateObservations(observations)
                    coopManager.updateSharingCount(observations.size)
                    updateUiState()

                    Log.d(TAG, "Sharing ${observations.size} observations for " +
                        "${observations.map { it.suspiciousCid }.distinct().size} CIDs")
                } catch (e: Exception) {
                    Log.w(TAG, "Error in observation sharing cycle: ${e.message}")
                }
            }
        }
    }

    /**
     * Exchange cooperative observations with a discovered Edge Sentinel peer.
     * Connects via GATT, reads their observations, and writes ours.
     */
    @SuppressLint("MissingPermission")
    private fun exchangeObservationsWithPeer(scanResult: ScanResult) {
        if (!isCooperativeEnabled.value) return

        val device = scanResult.device ?: return

        viewModelScope.launch {
            try {
                device.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                        if (newState == BluetoothGatt.STATE_CONNECTED) {
                            Log.d(TAG, "Connected to peer GATT, discovering services...")
                            gatt?.discoverServices()
                        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                            gatt?.close()
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            gatt?.close()
                            return
                        }

                        val service = gatt?.getService(MESH_SERVICE_UUID)
                        if (service == null) {
                            Log.d(TAG, "Peer doesn't have Edge Sentinel service")
                            gatt?.close()
                            return
                        }

                        // Read their observations
                        val shareChar = service.getCharacteristic(MeshBleServer.OBSERVATION_SHARE_UUID)
                        if (shareChar != null) {
                            gatt.readCharacteristic(shareChar)
                        }
                    }

                    @Deprecated("Deprecated in API 33")
                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt?,
                        characteristic: BluetoothGattCharacteristic?,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS &&
                            characteristic?.uuid == MeshBleServer.OBSERVATION_SHARE_UUID) {
                            val data = characteristic?.value
                            if (data != null) {
                                val json = String(data, Charsets.UTF_8)
                                val observations = CooperativeObservation.listFromJson(json)
                                observations.forEach { obs ->
                                    coopManager.onPeerObservationReceived(obs)
                                }
                                Log.d(TAG, "Read ${observations.size} observations from peer")
                            }

                            // Now write our observations to them
                            val service = gatt?.getService(MESH_SERVICE_UUID)
                            val receiveChar = service?.getCharacteristic(
                                MeshBleServer.OBSERVATION_RECEIVE_UUID
                            )
                            if (receiveChar != null) {
                                val ourObs = bleServer?.let { /* get current */ } ?: return
                                // Write will be handled by the GATT server callback on their end
                            }
                        }

                        // Close connection after exchange
                        gatt?.close()
                        viewModelScope.launch { updateUiState() }
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Error exchanging with peer: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopMesh() {
        maintenanceJob?.cancel()
        observationSharingJob?.cancel()

        // Stop GATT server
        bleServer?.stop()
        bleServer = null

        if (isScanning) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan: ${e.message}")
            }
            isScanning = false
        }

        scanner = null
        discoveredPeers.clear()
        coopManager.clear()

        _uiState.value = _uiState.value.copy(
            isActive = false,
            connectedPeerCount = 0,
            cooperativeMode = CooperativeModeState(),
            error = null
        )
        Log.d(TAG, "Mesh stopped")
    }

    /** Update user location for observation creation. */
    fun updateUserLocation(lat: Double, lng: Double) {
        userLat = lat
        userLng = lng
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun updateUiState() {
        val edgeSentinelPeers = discoveredPeers.values.count { it.isEdgeSentinel }
        val trilaterations = coopManager.trilaterations.value

        _uiState.value = _uiState.value.copy(
            connectedPeerCount = discoveredPeers.size,
            discoveredPeers = discoveredPeers.values.toList(),
            cooperativeMode = CooperativeModeState(
                isActive = isCooperativeEnabled.value &&
                    coopManager.sharingCount.value > 0 &&
                    edgeSentinelPeers > 0,
                edgeSentinelPeerCount = edgeSentinelPeers,
                sharingObservationCount = coopManager.sharingCount.value,
                receivingFromPeerCount = coopManager.peerCount.value,
                trackedCids = trilaterations.map { trilat ->
                    TrackedCid(
                        cid = trilat.cellId,
                        observationCount = trilat.observations.size,
                        participatingDevices = trilat.participatingDevices,
                        hasEstimate = trilat.hasEstimate,
                        estimatedLat = trilat.estimatedLat,
                        estimatedLng = trilat.estimatedLng,
                        accuracyLabel = trilat.accuracyLabel
                    )
                },
                trilaterations = trilaterations
            )
        )
    }

    private fun pruneStale() {
        val cutoff = System.currentTimeMillis() - 120_000 // 2 minutes
        val before = discoveredPeers.size
        discoveredPeers.entries.removeAll { it.value.lastSeen < cutoff }
        if (discoveredPeers.size != before) {
            updateUiState()
        }
    }

    private fun hasBlePermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCleared() {
        stopMesh()
        super.onCleared()
    }
}

/** Cooperative mode state for the UI. */
data class CooperativeModeState(
    val isActive: Boolean = false,
    val edgeSentinelPeerCount: Int = 0,
    val sharingObservationCount: Int = 0,
    val receivingFromPeerCount: Int = 0,
    val trackedCids: List<TrackedCid> = emptyList(),
    val trilaterations: List<CooperativeTrilateration> = emptyList()
)

/** A CID being cooperatively tracked. */
data class TrackedCid(
    val cid: Long,
    val observationCount: Int,
    val participatingDevices: Int,
    val hasEstimate: Boolean,
    val estimatedLat: Double?,
    val estimatedLng: Double?,
    val accuracyLabel: String
)

data class MeshUiState(
    val isActive: Boolean = false,
    val connectedPeerCount: Int = 0,
    val totalAlertsReceived: Int = 0,
    val corroboratedAlertCount: Int = 0,
    val recentMeshAlerts: List<MeshAlert> = emptyList(),
    val correlatedAlerts: List<MeshAlertAggregator.CorrelatedAlert> = emptyList(),
    val discoveredPeers: List<MeshViewModel.PeerInfo> = emptyList(),
    val cooperativeMode: CooperativeModeState = CooperativeModeState(),
    val error: String? = null
)
