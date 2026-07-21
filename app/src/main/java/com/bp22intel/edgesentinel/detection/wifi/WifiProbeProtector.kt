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

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.WifiThreatType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy status of the device's WiFi probe behaviour.
 */
data class ProbePrivacyStatus(
    val macRandomizationEnabled: Boolean,
    val savedNetworkCount: Int,
    val openSavedNetworks: List<String>,
    val probeLeakRisk: ProbeLeakRisk,
    val recommendations: List<String>
)

enum class ProbeLeakRisk(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High")
}

/**
 * Monitors device WiFi configuration for probe request privacy risks.
 *
 * WiFi devices send probe requests that can leak previously-connected SSIDs
 * to any nearby listener. This module assesses the risk and recommends
 * mitigations such as MAC randomization and removing unused saved networks.
 *
 * All processing is LOCAL — no data ever leaves the device.
 */
@Singleton
class WifiProbeProtector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        private const val HIGH_SAVED_NETWORK_THRESHOLD = 15
        private const val MEDIUM_SAVED_NETWORK_THRESHOLD = 8
    }

    /**
     * Assess the current probe request privacy posture.
     */
    fun assessPrivacy(): ProbePrivacyStatus {
        val macRandomization = isMacRandomizationEnabled()
        val savedNetworks = getSavedNetworkSsids()
        val openNetworks = getOpenSavedNetworks()
        val risk = calculateRisk(macRandomization, savedNetworks.size, openNetworks.size)
        val recommendations = buildRecommendations(macRandomization, savedNetworks.size, openNetworks)

        return ProbePrivacyStatus(
            macRandomizationEnabled = macRandomization,
            savedNetworkCount = savedNetworks.size,
            openSavedNetworks = openNetworks,
            probeLeakRisk = risk,
            recommendations = recommendations
        )
    }

    /**
     * Produce a detection result if probe leak risk is elevated.
     */
    fun detectProbeLeak(): WifiDetectionResult? {
        val status = assessPrivacy()
        if (status.probeLeakRisk == ProbeLeakRisk.LOW) return null

        val score = when (status.probeLeakRisk) {
            ProbeLeakRisk.HIGH -> 2.5
            ProbeLeakRisk.MEDIUM -> 1.5
            ProbeLeakRisk.LOW -> 0.0
        }

        val indicators = mutableMapOf(
            "mac_randomization" to if (status.macRandomizationEnabled) "Enabled" else "Disabled",
            "saved_networks" to "${status.savedNetworkCount} saved networks",
            "open_networks" to status.openSavedNetworks.joinToString(", ").ifEmpty { "None" }
        )

        return WifiDetectionResult(
            threatType = WifiThreatType.PROBE_LEAK,
            score = score,
            confidence = when (status.probeLeakRisk) {
                ProbeLeakRisk.HIGH -> Confidence.HIGH
                ProbeLeakRisk.MEDIUM -> Confidence.MEDIUM
                ProbeLeakRisk.LOW -> Confidence.LOW
            },
            summary = "Probe request privacy risk: ${status.probeLeakRisk.label} — " +
                "${status.recommendations.firstOrNull() ?: "Review saved networks"}",
            details = indicators
        )
    }

    /**
     * Check if the system has WiFi MAC randomization enabled.
     * Android 10+ enables it by default; we check the global setting.
     */
    private fun isMacRandomizationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {
            val value = Settings.Global.getInt(
                context.contentResolver,
                "wifi_connected_mac_randomization_enabled",
                1
            )
            value == 1
        } catch (_: Exception) {
            // Default to true on API 29+ as it's the platform default
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }
    }

    /**
     * Get SSIDs of saved/configured networks.
     */
    @Suppress("DEPRECATION")
    private fun getSavedNetworkSsids(): List<String> {
        return try {
            wifiManager.configuredNetworks?.map {
                it.SSID?.removeSurrounding("\"") ?: ""
            }?.filter { it.isNotBlank() } ?: emptyList()
        } catch (_: SecurityException) {
            // Requires ACCESS_FINE_LOCATION on newer APIs
            emptyList()
        }
    }

    /**
     * Identify saved networks with no/weak security.
     */
    @Suppress("DEPRECATION")
    private fun getOpenSavedNetworks(): List<String> {
        return try {
            wifiManager.configuredNetworks?.filter { config ->
                config.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.NONE) == true
            }?.map {
                it.SSID?.removeSurrounding("\"") ?: ""
            }?.filter { it.isNotBlank() } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun calculateRisk(
        macRandomization: Boolean,
        savedCount: Int,
        openCount: Int
    ): ProbeLeakRisk = when {
        !macRandomization && savedCount > HIGH_SAVED_NETWORK_THRESHOLD -> ProbeLeakRisk.HIGH
        !macRandomization || openCount > 3 -> ProbeLeakRisk.HIGH
        savedCount > HIGH_SAVED_NETWORK_THRESHOLD || openCount > 0 -> ProbeLeakRisk.MEDIUM
        savedCount > MEDIUM_SAVED_NETWORK_THRESHOLD -> ProbeLeakRisk.MEDIUM
        else -> ProbeLeakRisk.LOW
    }

    private fun buildRecommendations(
        macRandomization: Boolean,
        savedCount: Int,
        openNetworks: List<String>
    ): List<String> {
        val recs = mutableListOf<String>()

        if (!macRandomization) {
            recs.add("Enable MAC randomization in WiFi settings to prevent device tracking")
        }

        if (openNetworks.isNotEmpty()) {
            recs.add(
                "Remove saved open networks to prevent auto-connection attacks: " +
                    openNetworks.take(3).joinToString(", ") +
                    if (openNetworks.size > 3) " (+${openNetworks.size - 3} more)" else ""
            )
        }

        if (savedCount > HIGH_SAVED_NETWORK_THRESHOLD) {
            recs.add(
                "Remove unused saved networks ($savedCount saved) — each one is leaked in probe requests on older devices"
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            recs.add("Consider upgrading to Android 9+ for improved probe request privacy")
        }

        if (recs.isEmpty()) {
            recs.add("WiFi probe privacy is well-configured")
        }

        return recs
    }
}
