/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observed WiFi access point with metadata extracted from Android [ScanResult].
 */
data class ObservedAp(
    val bssid: String,
    val ssid: String,
    val signalStrength: Int,
    val frequency: Int,
    val securityType: SecurityType,
    val capabilities: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Coarse security classification derived from AP capabilities string.
 */
enum class SecurityType(val label: String) {
    OPEN("Open"),
    WEP("WEP"),
    WPA("WPA"),
    WPA2("WPA2"),
    WPA3("WPA3"),
    UNKNOWN("Unknown");

    companion object {
        fun fromCapabilities(caps: String): SecurityType = when {
            caps.contains("WPA3", ignoreCase = true) -> WPA3
            caps.contains("WPA2", ignoreCase = true) -> WPA2
            caps.contains("WPA", ignoreCase = true) -> WPA
            caps.contains("WEP", ignoreCase = true) -> WEP
            caps.contains("ESS") && !caps.contains("WPA") && !caps.contains("WEP") -> OPEN
            else -> UNKNOWN
        }
    }
}

/**
 * A snapshot of a single WiFi scan.
 */
data class WifiScanSnapshot(
    val accessPoints: List<ObservedAp>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Continuously scans the WiFi environment and maintains a rolling history.
 *
 * Uses [WifiManager.ScanResultsCallback] on API 30+ with a fallback to
 * [BroadcastReceiver] on older devices. Triggers periodic scans and emits
 * results as a [StateFlow].
 *
 * All processing is LOCAL — no data ever leaves the device.
 */
@Singleton
class WifiMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _latestScan = MutableStateFlow(WifiScanSnapshot(emptyList()))
    val latestScan: StateFlow<WifiScanSnapshot> = _latestScan.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    /** Rolling history — keeps the last [MAX_HISTORY_SNAPSHOTS] scan snapshots. */
    private val scanHistory = ConcurrentLinkedDeque<WifiScanSnapshot>()

    companion object {
        private const val SCAN_INTERVAL_MS = 15_000L
        private const val MAX_HISTORY_SNAPSHOTS = 60

        /**
         * Android API 28+ throttles WifiManager.startScan() to 4 scans per 2 minutes
         * for foreground apps. We enforce a minimum gap to stay within budget.
         */
        private const val MIN_FORCE_RESCAN_INTERVAL_MS = 30_000L
    }

    /** Timestamp of the last forced rescan to respect Android throttle limits. */
    @Volatile
    private var lastForceScanTimestamp = 0L

    /**
     * Immediately triggers a new WiFi scan outside the normal periodic interval.
     *
     * Call this when the user has moved to a new location and stale scan results
     * would misrepresent the current WiFi environment.
     *
     * Respects Android API 28+ throttling (4 scans / 2 min foreground) by enforcing
     * a minimum [MIN_FORCE_RESCAN_INTERVAL_MS] gap between forced scans.
     *
     * @return `true` if a scan was actually triggered, `false` if throttled.
     */
    fun forceRescan(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastForceScanTimestamp < MIN_FORCE_RESCAN_INTERVAL_MS) {
            return false // Throttled — too soon since last forced scan
        }
        lastForceScanTimestamp = now
        @Suppress("DEPRECATION")
        return wifiManager.startScan()
    }

    /**
     * Returns an immutable view of the scan history (most recent last).
     */
    fun getHistory(): List<WifiScanSnapshot> = scanHistory.toList()

    /**
     * Returns all unique APs observed across the entire history window.
     */
    fun getAllObservedAps(): List<ObservedAp> =
        scanHistory.flatMap { it.accessPoints }
            .distinctBy { it.bssid }

    /**
     * Emits WiFi scan results as a cold [Flow].
     *
     * On API 30+ registers a [WifiManager.ScanResultsCallback]; on older devices
     * listens for [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION] broadcasts. In both
     * cases a periodic scan is kicked off every [SCAN_INTERVAL_MS].
     */
    fun scanFlow(): Flow<WifiScanSnapshot> = callbackFlow {
        _isMonitoring.value = true

        val processScanResults: () -> Unit = {
            val results = wifiManager.scanResults.orEmpty()
            val snapshot = toSnapshot(results)
            pushHistory(snapshot)
            _latestScan.value = snapshot
            trySend(snapshot)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val callback = object : WifiManager.ScanResultsCallback() {
                override fun onScanResultsAvailable() {
                    processScanResults()
                }
            }
            wifiManager.registerScanResultsCallback(context.mainExecutor, callback)

            // Periodic scan trigger
            val scanJob = launch {
                while (isActive) {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                    delay(SCAN_INTERVAL_MS)
                }
            }

            awaitClose {
                scanJob.cancel()
                wifiManager.unregisterScanResultsCallback(callback)
                _isMonitoring.value = false
            }
        } else {
            // Fallback: BroadcastReceiver for API < 30
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    processScanResults()
                }
            }
            @Suppress("DEPRECATION")
            context.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )

            val scanJob = launch {
                while (isActive) {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                    delay(SCAN_INTERVAL_MS)
                }
            }

            awaitClose {
                scanJob.cancel()
                context.unregisterReceiver(receiver)
                _isMonitoring.value = false
            }
        }
    }

    private fun toSnapshot(results: List<ScanResult>): WifiScanSnapshot {
        val aps = results.map { sr ->
            ObservedAp(
                bssid = sr.BSSID,
                ssid = sr.SSID.orEmpty(),
                signalStrength = sr.level,
                frequency = sr.frequency,
                securityType = SecurityType.fromCapabilities(sr.capabilities),
                capabilities = sr.capabilities
            )
        }
        return WifiScanSnapshot(aps)
    }

    private fun pushHistory(snapshot: WifiScanSnapshot) {
        scanHistory.addLast(snapshot)
        while (scanHistory.size > MAX_HISTORY_SNAPSHOTS) {
            scanHistory.pollFirst()
        }
    }
}
