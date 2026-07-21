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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bp22intel.edgesentinel.detection.geo.ThreatGeolocation
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android Service that manages BLE and Wi-Fi Direct connections to nearby
 * Edge Sentinel devices. Runs as a foreground service for reliable mesh
 * connectivity.
 *
 * ZERO cloud dependency — all communication is local (BLE + WiFi Direct).
 * Privacy-first: devices share alert data but NOT identity, IMSI, or location.
 */
@AndroidEntryPoint
class MeshService : Service() {

    @Inject lateinit var threatGeolocation: ThreatGeolocation

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "edge_sentinel_mesh"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS_NAME = "mesh_prefs"
        private const val KEY_DEVICE_ID = "mesh_device_id"

        /** Default scan interval — battery-conscious. */
        const val DEFAULT_SCAN_INTERVAL_MS = 30_000L
        /** Peer staleness timeout. */
        private const val PEER_TIMEOUT_MS = 120_000L
        /** Heartbeat interval. */
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MeshService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
                // Fallback: try regular service start
                try {
                    context.startService(Intent(context, MeshService::class.java))
                } catch (e2: Exception) {
                    Log.e(TAG, "Could not start service at all: ${e2.message}")
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var maintenanceJob: Job? = null
    private var heartbeatJob: Job? = null

    private lateinit var meshDeviceId: String
    private lateinit var discovery: MeshDiscovery
    lateinit var aggregator: MeshAlertAggregator
        private set

    private val _meshState = MutableStateFlow(MeshState())
    val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

    private val binder = MeshBinder()

    inner class MeshBinder : Binder() {
        val service: MeshService get() = this@MeshService
    }

    data class MeshState(
        val isActive: Boolean = false,
        val connectedPeerCount: Int = 0,
        val totalAlertsReceived: Int = 0,
        val corroboratedAlertCount: Int = 0
    )

    override fun onCreate() {
        super.onCreate()
        try {
            meshDeviceId = getOrCreateDeviceId()
            aggregator = MeshAlertAggregator(meshDeviceId)
            aggregator.onPeerObservation = { obs ->
                threatGeolocation.addMeshPeerObservation(obs)
            }

            discovery = MeshDiscovery(
                context = this,
                deviceId = meshDeviceId,
                onPeerDiscovered = { peerId, rssi ->
                    Log.d(TAG, "Peer discovered: ${peerId.take(8)}... RSSI: $rssi")
                    updateState()
                },
                onMessageReceived = { _, data ->
                    handleIncomingMessage(data)
                }
            )

            createNotificationChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MeshService: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel() // Ensure channel exists before startForeground
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}", e)
            // Still try to run without foreground (may get killed by OS)
        }
        try {
            startMesh()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopMesh()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMesh() {
        try {
            discovery.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE permissions not granted, running in passive mode: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start BLE discovery: ${e.message}")
        }
        _meshState.value = _meshState.value.copy(isActive = true)

        // Periodic peer pruning and state updates
        maintenanceJob = scope.launch {
            while (isActive) {
                delay(DEFAULT_SCAN_INTERVAL_MS)
                discovery.pruneStale(PEER_TIMEOUT_MS)
                aggregator.pruneStale()
                updateState()
            }
        }

        // Periodic heartbeat
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }

        Log.d(TAG, "Mesh started with device ID: ${meshDeviceId.take(8)}...")
    }

    private fun stopMesh() {
        maintenanceJob?.cancel()
        heartbeatJob?.cancel()
        discovery.stopDiscovery()
        _meshState.value = _meshState.value.copy(isActive = false)
        Log.d(TAG, "Mesh stopped")
    }

    private fun handleIncomingMessage(data: ByteArray) {
        val raw = String(data, Charsets.UTF_8)

        // Try parsing as alert first
        MeshAlert.fromJson(raw)?.let { alert ->
            if (alert.messageType == MeshProtocol.MessageType.ALERT) {
                aggregator.onAlertReceived(alert)
                updateState()
                Log.d(TAG, "Received mesh alert: ${alert.threatType} from ${alert.deviceId.take(8)}")
                return
            }
        }

        // Try parsing as heartbeat
        MeshHeartbeat.fromBytes(data)?.let { heartbeat ->
            Log.d(TAG, "Received heartbeat from ${heartbeat.deviceId.take(8)}")
        }
    }

    /** Broadcast a local detection to mesh peers. */
    fun broadcastLocalAlert(alert: MeshAlert) {
        scope.launch {
            discovery.broadcastAlert(alert)
            Log.d(TAG, "Broadcasted alert: ${alert.threatType}")
        }
    }

    private fun sendHeartbeat() {
        val heartbeat = MeshHeartbeat(
            deviceId = meshDeviceId,
            alertCount = aggregator.meshAlerts.value.size
        )
        Log.d(TAG, "Sending heartbeat (${heartbeat.alertCount} alerts)")
    }

    private fun updateState() {
        val peers = discovery.discoveredPeers.value
        val correlated = aggregator.correlatedAlerts.value
        _meshState.value = MeshState(
            isActive = true,
            connectedPeerCount = peers.size,
            totalAlertsReceived = aggregator.meshAlerts.value.size,
            corroboratedAlertCount = correlated.count { it.isCorroborated }
        )
    }

    /** Anonymous device ID — regenerated on app reinstall. */
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            newId
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mesh Network",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Edge Sentinel mesh network status"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Edge Sentinel Mesh")
            .setContentText("Mesh network active — scanning for peers")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
}
