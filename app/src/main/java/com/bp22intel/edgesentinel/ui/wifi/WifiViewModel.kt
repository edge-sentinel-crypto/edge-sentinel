/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.detection.wifi.EnvironmentAnalysis
import com.bp22intel.edgesentinel.detection.wifi.ObservedAp
import com.bp22intel.edgesentinel.detection.wifi.ProbePrivacyStatus
import com.bp22intel.edgesentinel.detection.wifi.ProbeLeakRisk
import com.bp22intel.edgesentinel.detection.wifi.WifiDetectionResult
import com.bp22intel.edgesentinel.detection.wifi.WifiEnvironmentAnalyzer
import com.bp22intel.edgesentinel.detection.wifi.WifiMonitor
import com.bp22intel.edgesentinel.detection.wifi.WifiProbeProtector
import com.bp22intel.edgesentinel.detection.wifi.WifiThreatDetector
import com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao
import com.bp22intel.edgesentinel.data.local.entity.TrustedNetworkEntity
import com.bp22intel.edgesentinel.domain.model.WifiThreatType
import com.bp22intel.edgesentinel.fusion.SensorFusionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WifiUiState(
    val isScanning: Boolean = false,
    val accessPoints: List<ObservedAp> = emptyList(),
    val threats: List<WifiDetectionResult> = emptyList(),
    val environmentAnalysis: EnvironmentAnalysis? = null,
    val probeStatus: ProbePrivacyStatus? = null,
    val healthScore: Int = 100,
    val trustedBssids: Set<String> = emptySet(),
    val trustedSsids: Set<String> = emptySet(),
    val trustedNetworks: List<TrustedNetworkEntity> = emptyList(),
    val ssidTrustConfirmation: String? = null,
    val error: String? = null
)

@HiltViewModel
class WifiViewModel @Inject constructor(
    private val wifiMonitor: WifiMonitor,
    private val threatDetector: WifiThreatDetector,
    private val environmentAnalyzer: WifiEnvironmentAnalyzer,
    private val probeProtector: WifiProbeProtector,
    private val trustedNetworkDao: TrustedNetworkDao,
    private val sensorFusionEngine: SensorFusionEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(WifiUiState())
    val uiState: StateFlow<WifiUiState> = _uiState.asStateFlow()

    private val disconnectTimestamps = mutableListOf<Long>()

    init {
        startMonitoring()
        assessProbePrivacy()
        loadTrustedNetworks()
    }

    private fun loadTrustedNetworks() {
        viewModelScope.launch {
            trustedNetworkDao.getAllTrusted().collect { trusted ->
                _uiState.update { state ->
                    state.copy(
                        trustedBssids = trusted.map { it.bssid }.toSet(),
                        trustedSsids = trusted.map { it.ssid }.toSet(),
                        trustedNetworks = trusted
                    )
                }
            }
        }
    }

    fun trustNetwork(bssid: String, ssid: String, label: String? = null) {
        viewModelScope.launch {
            trustedNetworkDao.insert(
                TrustedNetworkEntity(
                    bssid = bssid,
                    ssid = ssid,
                    label = label ?: ssid
                )
            )
        }
    }

    fun untrustNetwork(bssid: String) {
        viewModelScope.launch {
            trustedNetworkDao.removeByBssid(bssid)
        }
    }

    /**
     * Trust an entire SSID — all current and future BSSIDs broadcasting this
     * SSID will be considered trusted. This is the right approach for mesh
     * networks where many APs share one SSID.
     *
     * Inserts a trust entry for every currently-visible AP with this SSID,
     * plus an SSID-level marker so new BSSIDs are auto-trusted.
     */
    fun trustSsid(ssid: String) {
        viewModelScope.launch {
            // Insert trust entries for all currently-visible APs with this SSID
            val visibleAps = _uiState.value.accessPoints.filter { it.ssid == ssid }
            val entities = visibleAps.map { ap ->
                TrustedNetworkEntity(
                    bssid = ap.bssid,
                    ssid = ssid,
                    label = "$ssid (auto-trusted)"
                )
            }
            if (entities.isNotEmpty()) {
                trustedNetworkDao.insertAll(entities)
            } else {
                // No visible APs yet — insert an SSID-level marker
                trustedNetworkDao.insert(
                    TrustedNetworkEntity(
                        bssid = "ssid-trust:$ssid",
                        ssid = ssid,
                        label = "$ssid (SSID-level trust)"
                    )
                )
            }

            val apCount = visibleAps.size
            _uiState.update { state ->
                state.copy(
                    ssidTrustConfirmation = "\"$ssid\" trusted" +
                        if (apCount > 0) " ($apCount access points)" else ""
                )
            }

            // Dismiss WiFi and related network detections from fusion
            sensorFusionEngine.dismissDetection(WifiThreatType.EVIL_TWIN.name)
            sensorFusionEngine.dismissDetection("ROGUE_AP")
            sensorFusionEngine.dismissDetection("DEAUTH_FLOOD")
            sensorFusionEngine.dismissDetection("KARMA_ATTACK")
            sensorFusionEngine.recalculateClean()
        }
    }

    /**
     * Remove trust for an entire SSID.
     */
    fun untrustSsid(ssid: String) {
        viewModelScope.launch {
            trustedNetworkDao.removeBySsid(ssid)
            sensorFusionEngine.recalculateClean()
        }
    }

    fun isNetworkTrusted(bssid: String): Boolean {
        return _uiState.value.trustedBssids.contains(bssid)
    }

    fun isSsidTrusted(ssid: String): Boolean {
        return _uiState.value.trustedSsids.contains(ssid)
    }

    fun clearSsidTrustConfirmation() {
        _uiState.update { it.copy(ssidTrustConfirmation = null) }
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            wifiMonitor.scanFlow()
                .catch { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
                .collect { snapshot ->
                    val history = wifiMonitor.getHistory()

                    // Run threat detection with trusted network awareness
                    val trustedBssids = _uiState.value.trustedBssids
                    val trustedSsids = _uiState.value.trustedSsids
                    val threats = threatDetector.analyze(snapshot, history, disconnectTimestamps, trustedBssids, trustedSsids)

                    // Run environment analysis
                    val baseline = environmentAnalyzer.buildBaseline(history)
                    val envAnalysis = baseline?.let { environmentAnalyzer.analyze(snapshot, it) }

                    // Add probe leak detection
                    val probeLeak = probeProtector.detectProbeLeak()
                    val allThreatsRaw = if (probeLeak != null) threats + probeLeak else threats

                    // Filter out threats involving only trusted networks (by BSSID or SSID)
                    val allThreats = allThreatsRaw.filter { result ->
                        // Keep threat if ANY involved AP is NOT trusted
                        result.involvedAps.any { ap ->
                            !trustedBssids.contains(ap.bssid) && !trustedSsids.contains(ap.ssid)
                        }
                    }

                    _uiState.update { state ->
                        state.copy(
                            isScanning = true,
                            accessPoints = snapshot.accessPoints,
                            threats = allThreats,
                            environmentAnalysis = envAnalysis,
                            healthScore = envAnalysis?.healthScore ?: 100,
                            error = null
                        )
                    }
                }
        }
    }

    private fun assessProbePrivacy() {
        viewModelScope.launch {
            val status = probeProtector.assessPrivacy()
            _uiState.update { it.copy(probeStatus = status) }
        }
    }

    fun recordDisconnect() {
        disconnectTimestamps.add(System.currentTimeMillis())
        // Keep last 20 events
        if (disconnectTimestamps.size > 20) {
            disconnectTimestamps.removeAt(0)
        }
    }

    /**
     * Get the threat level for a specific AP based on current detections.
     */
    fun threatLevelForAp(ap: ObservedAp): ApThreatLevel {
        val involvedThreats = _uiState.value.threats.filter { result ->
            result.involvedAps.any { it.bssid == ap.bssid }
        }
        return when {
            involvedThreats.any { it.threatType == WifiThreatType.EVIL_TWIN || it.threatType == WifiThreatType.KARMA_ATTACK } ->
                ApThreatLevel.DANGEROUS
            involvedThreats.isNotEmpty() -> ApThreatLevel.SUSPICIOUS
            else -> ApThreatLevel.SAFE
        }
    }
}

enum class ApThreatLevel(val label: String) {
    SAFE("Safe"),
    SUSPICIOUS("Suspicious"),
    DANGEROUS("Dangerous")
}
