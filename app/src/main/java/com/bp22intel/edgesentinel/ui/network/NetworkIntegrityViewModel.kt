/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.detection.network.CaptivePortalDetector
import com.bp22intel.edgesentinel.detection.network.CaptivePortalResult
import com.bp22intel.edgesentinel.detection.network.DnsIntegrityChecker
import com.bp22intel.edgesentinel.detection.network.DnsIntegrityResult
import com.bp22intel.edgesentinel.detection.network.NetworkIntegritySnapshot
import com.bp22intel.edgesentinel.detection.network.TlsIntegrityChecker
import com.bp22intel.edgesentinel.detection.network.TlsIntegrityResult
import com.bp22intel.edgesentinel.detection.network.VpnMonitor
import com.bp22intel.edgesentinel.detection.network.VpnStatusResult
import com.bp22intel.edgesentinel.domain.model.NetworkThreatType
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class NetworkIntegrityViewModel @Inject constructor(
    private val vpnMonitor: VpnMonitor,
    private val dnsChecker: DnsIntegrityChecker,
    private val tlsChecker: TlsIntegrityChecker,
    private val captivePortalDetector: CaptivePortalDetector,
    private val sensorFusionEngine: com.bp22intel.edgesentinel.fusion.SensorFusionEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PREFS_NAME = "trusted_mitm_services"
        private const val KEY_TRUSTED = "trusted_endpoints"
        private const val HISTORY_PREFS_NAME = "network_integrity_history"
        private const val KEY_HISTORY = "check_history_json"
        private const val MAX_HISTORY_ENTRIES = 50
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val historyPrefs: SharedPreferences =
        context.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)

    val vpnStatus = vpnMonitor.vpnStatus

    private val _snapshot = MutableStateFlow<NetworkIntegritySnapshot?>(null)
    val snapshot: StateFlow<NetworkIntegritySnapshot?> = _snapshot.asStateFlow()

    private val _trustedMitmServices = MutableStateFlow<Set<String>>(emptySet())
    val trustedMitmServices: StateFlow<Set<String>> = _trustedMitmServices.asStateFlow()

    private val _checkHistory = MutableStateFlow<List<NetworkIntegritySnapshot>>(emptyList())
    val checkHistory: StateFlow<List<NetworkIntegritySnapshot>> = _checkHistory.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    init {
        vpnMonitor.startMonitoring()
        _trustedMitmServices.value = prefs.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet()
        _checkHistory.value = loadHistory()
    }

    fun trustMitmService(endpoint: String) {
        val updated = _trustedMitmServices.value + endpoint
        _trustedMitmServices.value = updated
        prefs.edit().putStringSet(KEY_TRUSTED, updated).apply()
        // Dismiss TLS_MITM from fusion engine if all MITM endpoints are now trusted
        val snapshot = _snapshot.value?.tlsIntegrity
        if (snapshot != null) {
            val untrusted = snapshot.mitmEndpoints.filter { it !in updated }
            if (untrusted.isEmpty()) {
                sensorFusionEngine.dismissDetection("TLS_MITM")
                sensorFusionEngine.recalculateClean()
            }
        }
    }

    fun untrustMitmService(endpoint: String) {
        val updated = _trustedMitmServices.value - endpoint
        _trustedMitmServices.value = updated
        prefs.edit().putStringSet(KEY_TRUSTED, updated).apply()
    }

    /**
     * Runs all network integrity checks concurrently and produces a composite snapshot.
     */
    fun runFullCheck() {
        if (_isChecking.value) return

        viewModelScope.launch {
            _isChecking.value = true
            try {
                val vpnResult = vpnMonitor.refreshVpnState()
                val dnsDeferred = async { dnsChecker.runFullCheck() }
                val tlsDeferred = async { tlsChecker.runFullCheck() }
                val portalDeferred = async { captivePortalDetector.runCheck() }

                val dnsResult = dnsDeferred.await()
                val tlsResult = tlsDeferred.await()
                val portalResult = portalDeferred.await()

                // Build threat list
                val threats = buildList {
                    if (vpnResult.vpnDropDetected) add(NetworkThreatType.VPN_DROP)
                    if (dnsResult.hijackedDomains.isNotEmpty()) add(NetworkThreatType.DNS_HIJACK)
                    if (dnsResult.nxdomainHijacked) add(NetworkThreatType.DNS_NXDOMAIN_HIJACK)
                    if (tlsResult.mitmEndpoints.isNotEmpty()) add(NetworkThreatType.TLS_MITM)
                    if (portalResult.jsInjectionDetected) add(NetworkThreatType.CAPTIVE_PORTAL_INJECT)
                }

                // Compute trust score (start at 100, deduct per threat type)
                var score = 100
                if (vpnResult.vpnDropDetected) score -= 20
                if (vpnResult.bypassLeakDetected) score -= 15
                if (dnsResult.hijackedDomains.isNotEmpty()) score -= 25
                if (dnsResult.nxdomainHijacked) score -= 10
                val untrustedMitm = tlsResult.mitmEndpoints.filter { it !in _trustedMitmServices.value }
                if (untrustedMitm.isNotEmpty()) score -= 30
                if (portalResult.captivePortalDetected) score -= 10
                if (portalResult.jsInjectionDetected) score -= 15

                val snapshot = NetworkIntegritySnapshot(
                    vpnStatus = vpnResult,
                    dnsIntegrity = dnsResult,
                    tlsIntegrity = tlsResult,
                    captivePortal = portalResult,
                    trustScore = score.coerceIn(0, 100),
                    threats = threats
                )

                _snapshot.value = snapshot
                val updated = (listOf(snapshot) + _checkHistory.value).take(MAX_HISTORY_ENTRIES)
                _checkHistory.value = updated
                saveHistory(updated)
            } finally {
                _isChecking.value = false
            }
        }
    }

    private fun saveHistory(history: List<NetworkIntegritySnapshot>) {
        try {
            val jsonArray = JSONArray()
            for (snap in history) {
                val obj = JSONObject().apply {
                    put("timestamp", snap.timestamp)
                    put("trustScore", snap.trustScore)
                    put("threats", JSONArray(snap.threats.map { it.name }))
                    // VPN status
                    snap.vpnStatus?.let { vpn ->
                        put("vpn", JSONObject().apply {
                            put("isVpnActive", vpn.isVpnActive)
                            put("vpnDropDetected", vpn.vpnDropDetected)
                            put("bypassLeakDetected", vpn.bypassLeakDetected)
                        })
                    }
                    // DNS integrity
                    snap.dnsIntegrity?.let { dns ->
                        put("dns", JSONObject().apply {
                            put("overallClean", dns.overallClean)
                            put("hijackedDomains", JSONArray(dns.hijackedDomains))
                            put("nxdomainHijacked", dns.nxdomainHijacked)
                        })
                    }
                    // TLS integrity
                    snap.tlsIntegrity?.let { tls ->
                        put("tls", JSONObject().apply {
                            put("overallClean", tls.overallClean)
                            put("mitmEndpoints", JSONArray(tls.mitmEndpoints))
                        })
                    }
                    // Captive portal
                    snap.captivePortal?.let { portal ->
                        put("portal", JSONObject().apply {
                            put("captivePortalDetected", portal.captivePortalDetected)
                            put("jsInjectionDetected", portal.jsInjectionDetected)
                        })
                    }
                }
                jsonArray.put(obj)
            }
            historyPrefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (_: Exception) {
            // Serialization failure — don't crash
        }
    }

    private fun loadHistory(): List<NetworkIntegritySnapshot> {
        return try {
            val json = historyPrefs.getString(KEY_HISTORY, null) ?: return emptyList()
            val jsonArray = JSONArray(json)
            val result = mutableListOf<NetworkIntegritySnapshot>()
            for (i in 0 until jsonArray.length().coerceAtMost(MAX_HISTORY_ENTRIES)) {
                val obj = jsonArray.getJSONObject(i)
                val threats = buildList {
                    val arr = obj.optJSONArray("threats")
                    if (arr != null) {
                        for (j in 0 until arr.length()) {
                            try {
                                add(NetworkThreatType.valueOf(arr.getString(j)))
                            } catch (_: Exception) { /* skip unknown threats */ }
                        }
                    }
                }
                val vpnStatus = obj.optJSONObject("vpn")?.let { vpn ->
                    VpnStatusResult(
                        timestamp = obj.optLong("timestamp", 0L),
                        isVpnActive = vpn.optBoolean("isVpnActive", false),
                        wasVpnActive = false,
                        vpnDropDetected = vpn.optBoolean("vpnDropDetected", false),
                        bypassLeakDetected = vpn.optBoolean("bypassLeakDetected", false)
                    )
                }
                val dnsIntegrity = obj.optJSONObject("dns")?.let { dns ->
                    DnsIntegrityResult(
                        timestamp = obj.optLong("timestamp", 0L),
                        domainResults = emptyList(),
                        overallClean = dns.optBoolean("overallClean", true),
                        hijackedDomains = buildList {
                            val arr = dns.optJSONArray("hijackedDomains")
                            if (arr != null) {
                                for (j in 0 until arr.length()) add(arr.getString(j))
                            }
                        },
                        nxdomainHijacked = dns.optBoolean("nxdomainHijacked", false)
                    )
                }
                val tlsIntegrity = obj.optJSONObject("tls")?.let { tls ->
                    TlsIntegrityResult(
                        timestamp = obj.optLong("timestamp", 0L),
                        endpointResults = emptyList(),
                        overallClean = tls.optBoolean("overallClean", true),
                        mitmEndpoints = buildList {
                            val arr = tls.optJSONArray("mitmEndpoints")
                            if (arr != null) {
                                for (j in 0 until arr.length()) add(arr.getString(j))
                            }
                        }
                    )
                }
                val captivePortal = obj.optJSONObject("portal")?.let { portal ->
                    CaptivePortalResult(
                        timestamp = obj.optLong("timestamp", 0L),
                        captivePortalDetected = portal.optBoolean("captivePortalDetected", false),
                        jsInjectionDetected = portal.optBoolean("jsInjectionDetected", false)
                    )
                }
                result.add(
                    NetworkIntegritySnapshot(
                        timestamp = obj.optLong("timestamp", 0L),
                        vpnStatus = vpnStatus,
                        dnsIntegrity = dnsIntegrity,
                        tlsIntegrity = tlsIntegrity,
                        captivePortal = captivePortal,
                        trustScore = obj.optInt("trustScore", 100),
                        threats = threats
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        vpnMonitor.stopMonitoring()
    }
}
