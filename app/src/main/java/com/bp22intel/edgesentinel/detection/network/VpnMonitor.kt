/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors VPN connection state using ConnectivityManager.NetworkCallback.
 *
 * Detects:
 * - VPN silently dropping (common in censorship environments)
 * - VPN bypass/leak: traffic routing outside the VPN tunnel
 * - Alerts when VPN was active and suddenly isn't
 * - Tracks VPN uptime/downtime history
 *
 * PRIVACY NOTE: This monitor uses only local system APIs (ConnectivityManager).
 * No network connections are made and no data leaves the device.
 */
@Singleton
class VpnMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _vpnStatus = MutableStateFlow(VpnStatusResult(
        isVpnActive = false,
        wasVpnActive = false,
        vpnDropDetected = false
    ))
    val vpnStatus: StateFlow<VpnStatusResult> = _vpnStatus.asStateFlow()

    private val _dropHistory = MutableStateFlow<List<Long>>(emptyList())
    val dropHistory: StateFlow<List<Long>> = _dropHistory.asStateFlow()

    private var vpnConnectedSince: Long? = null
    private var vpnDisconnectedSince: Long? = null
    private var wasVpnActiveInternal = false
    private var isRegistered = false

    private val vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now = System.currentTimeMillis()
            vpnConnectedSince = now
            vpnDisconnectedSince = null
            wasVpnActiveInternal = true

            _vpnStatus.value = VpnStatusResult(
                isVpnActive = true,
                wasVpnActive = true,
                vpnDropDetected = false,
                vpnUptimeMs = 0L,
                lastDropTimestamp = _vpnStatus.value.lastDropTimestamp
            )
        }

        override fun onLost(network: Network) {
            val now = System.currentTimeMillis()
            val uptimeMs = vpnConnectedSince?.let { now - it } ?: 0L
            vpnDisconnectedSince = now
            vpnConnectedSince = null

            val drops = _dropHistory.value + now
            _dropHistory.value = drops.takeLast(100)

            _vpnStatus.value = VpnStatusResult(
                isVpnActive = false,
                wasVpnActive = wasVpnActiveInternal,
                vpnDropDetected = wasVpnActiveInternal,
                vpnUptimeMs = uptimeMs,
                vpnDowntimeMs = 0L,
                lastDropTimestamp = now,
                bypassLeakDetected = false
            )
        }
    }

    /**
     * Starts monitoring VPN state changes.
     * Call from a long-lived scope (Application or Service).
     */
    fun startMonitoring() {
        if (isRegistered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(request, vpnNetworkCallback)
        isRegistered = true

        // Check initial VPN state
        refreshVpnState()
    }

    /**
     * Stops monitoring VPN state changes.
     */
    fun stopMonitoring() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(vpnNetworkCallback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
        isRegistered = false
    }

    /**
     * Polls the current VPN state and checks for bypass/leak conditions.
     */
    fun refreshVpnState(): VpnStatusResult {
        val now = System.currentTimeMillis()
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val vpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        // Bypass/leak detection: check if there are non-VPN networks carrying internet
        // while VPN is supposed to be active
        val bypassLeak = if (vpnActive) {
            checkForVpnBypass()
        } else false

        val uptimeMs = if (vpnActive) {
            vpnConnectedSince?.let { now - it } ?: 0L
        } else 0L

        val downtimeMs = if (!vpnActive) {
            vpnDisconnectedSince?.let { now - it } ?: 0L
        } else 0L

        val result = VpnStatusResult(
            timestamp = now,
            isVpnActive = vpnActive,
            wasVpnActive = wasVpnActiveInternal || vpnActive,
            vpnDropDetected = wasVpnActiveInternal && !vpnActive,
            vpnUptimeMs = uptimeMs,
            vpnDowntimeMs = downtimeMs,
            lastDropTimestamp = _vpnStatus.value.lastDropTimestamp,
            bypassLeakDetected = bypassLeak,
            leakDetails = if (bypassLeak) "Non-VPN network carrying internet detected while VPN active" else null
        )

        if (vpnActive) wasVpnActiveInternal = true
        _vpnStatus.value = result
        return result
    }

    /**
     * Checks if traffic might be leaking outside the VPN tunnel.
     * Looks for non-VPN networks with internet capability active alongside the VPN.
     */
    private fun checkForVpnBypass(): Boolean {
        val allNetworks = connectivityManager.allNetworks
        var hasVpn = false
        var hasNonVpnInternet = false

        for (network in allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

            if (isVpn) hasVpn = true
            if (hasInternet && !isVpn && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                hasNonVpnInternet = true
            }
        }

        // A leak is when both VPN and a validated non-VPN internet path exist
        // and the active network is NOT the VPN
        val activeNetwork = connectivityManager.activeNetwork
        val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val activeIsVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        return hasVpn && hasNonVpnInternet && !activeIsVpn
    }
}
