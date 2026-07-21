/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.alerts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.analysis.AlertAnalysis
import com.bp22intel.edgesentinel.analysis.FilterRecommendation
import com.bp22intel.edgesentinel.analysis.ThreatAnalyst
import com.bp22intel.edgesentinel.data.local.dao.AlertFeedbackDao
import com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao
import com.bp22intel.edgesentinel.data.local.entity.AlertFeedbackEntity
import com.bp22intel.edgesentinel.data.local.entity.TrustedNetworkEntity
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import com.bp22intel.edgesentinel.fusion.SensorFusionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

data class AlertDetailUiState(
    val alert: Alert? = null,
    val analysis: AlertAnalysis? = null,
    val filterRecommendation: FilterRecommendation? = null,
    val isLoading: Boolean = true,
    val isAcknowledged: Boolean = false,
    val feedbackGiven: String? = null,  // "FALSE_POSITIVE", "CONFIRMED_THREAT", "UNSURE", or null
    val feedbackConfirmation: String? = null,  // Transient confirmation message
    val towerLatitude: Double? = null,
    val towerLongitude: Double? = null,
    val towerAccuracyMeters: Double? = null,
    val towerReadingCount: Int = 0,
    val towerConfidenceScore: Float = 0f,
    val towerTechniques: String = "",
    val userLatitude: Double? = null,
    val userLongitude: Double? = null
)

@HiltViewModel
class AlertDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val alertRepository: AlertRepository,
    private val threatAnalyst: ThreatAnalyst,
    private val feedbackDao: AlertFeedbackDao,
    private val trustedNetworkDao: TrustedNetworkDao,
    private val sensorFusionEngine: SensorFusionEngine,
    private val towerDatabaseManager: com.bp22intel.edgesentinel.detection.tower.TowerDatabaseManager,
    private val threatGeolocation: com.bp22intel.edgesentinel.detection.geo.ThreatGeolocation,
    private val towerPositionTracker: com.bp22intel.edgesentinel.detection.geo.TowerPositionTracker,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    private val alertId: Long = savedStateHandle["alertId"] ?: 0L

    private val _uiState = MutableStateFlow(AlertDetailUiState())
    val uiState: StateFlow<AlertDetailUiState> = _uiState.asStateFlow()

    init {
        loadAlert()
    }

    private fun loadAlert() {
        viewModelScope.launch {
            val alert = alertRepository.getAlertById(alertId)
            if (alert != null) {
                val (analysis, filterRec) = threatAnalyst.analyzeAlertWithLearning(alert)

                // Check if user already gave feedback on this alert.
                val existingFeedback = feedbackDao.getFeedbackForAlert(alertId)

                // Get user's current GPS
                var userLat: Double? = null
                var userLon: Double? = null
                try {
                    val locationManager = appContext.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    @Suppress("MissingPermission")
                    val lastLoc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    if (lastLoc != null) {
                        userLat = lastLoc.latitude
                        userLon = lastLoc.longitude
                    }
                } catch (_: Exception) { /* permission denied or unavailable */ }

                // Try persistent estimated position first (continuous tracking)
                val details = try { org.json.JSONObject(alert.detailsJson) } catch (_: Exception) { org.json.JSONObject() }
                val alertMcc = details.optInt("mcc", 0)
                val alertMnc = details.optInt("mnc", 0)
                val alertLac = details.optInt("lac", 0)
                val alertCid = details.optInt("cellId", details.optInt("cid", 0))

                var towerLat: Double? = null
                var towerLon: Double? = null
                var towerAccuracy: Double? = null
                var towerReadings = 0
                var towerConfidence = 0f
                var towerTechniques = ""

                // 1) Check persistent estimated positions (from continuous tracker)
                if (alertMcc > 0 && alertCid > 0) {
                    val estimated = towerPositionTracker.getEstimatedPosition(
                        alertMcc, alertMnc, alertLac, alertCid
                    )
                    if (estimated != null) {
                        towerLat = estimated.estimatedLat
                        towerLon = estimated.estimatedLon
                        towerAccuracy = estimated.accuracyMeters
                        towerReadings = estimated.readingCount
                        towerConfidence = estimated.confidenceScore
                        towerTechniques = estimated.techniques
                    }
                }

                // 2) Fallback: run the one-shot geolocation engine
                if (towerLat == null && userLat != null && userLon != null) {
                    try {
                        val geoResults = threatGeolocation.geolocateThreats(
                            alerts = listOf(alert),
                            userLat = userLat,
                            userLng = userLon
                        )
                        val geoResult = geoResults.firstOrNull()
                        if (geoResult != null) {
                            towerLat = geoResult.latitude
                            towerLon = geoResult.longitude
                            towerAccuracy = geoResult.accuracyMeters
                            towerReadings = geoResult.observationCount
                        }
                    } catch (_: Exception) { /* geolocation engine failed */ }
                }

                // 3) Fallback: OpenCelliD DB lookup
                if (towerLat == null && alertMcc > 0 && alertCid > 0) {
                    try {
                        val tower = towerDatabaseManager.lookupTower(alertMcc, alertMnc, alertLac, alertCid)
                        if (tower != null) {
                            towerLat = tower.latitude
                            towerLon = tower.longitude
                        }
                    } catch (_: Exception) { }
                }

                _uiState.value = AlertDetailUiState(
                    alert = alert,
                    analysis = analysis,
                    filterRecommendation = filterRec,
                    isLoading = false,
                    isAcknowledged = alert.acknowledged,
                    feedbackGiven = existingFeedback?.feedback,
                    towerLatitude = towerLat,
                    towerLongitude = towerLon,
                    towerAccuracyMeters = towerAccuracy,
                    towerReadingCount = towerReadings,
                    towerConfidenceScore = towerConfidence,
                    towerTechniques = towerTechniques,
                    userLatitude = userLat,
                    userLongitude = userLon
                )

                // Start observing live position updates for this tower
                if (alertMcc > 0 && alertCid > 0) {
                    observeTowerPositionUpdates(alertMcc, alertMnc, alertLac, alertCid)
                }
            } else {
                _uiState.value = AlertDetailUiState(isLoading = false)
            }
        }
    }

    /** Job for the live tower position observation — cancelled when no longer needed. */
    private var towerObserveJob: Job? = null

    /**
     * Observe live updates to this tower's estimated position.
     * Each new scan cycle that sees this tower will emit a refined estimate.
     * The map marker moves and the accuracy circle shrinks in real-time.
     */
    private fun observeTowerPositionUpdates(mcc: Int, mnc: Int, lac: Int, cid: Int) {
        towerObserveJob?.cancel()
        towerObserveJob = viewModelScope.launch {
            towerPositionTracker.observeTowerPosition(mcc, mnc, lac, cid)
                .filterNotNull()
                .collectLatest { estimated ->
                    val current = _uiState.value
                    // Only update if position actually changed
                    if (current.towerLatitude != estimated.estimatedLat ||
                        current.towerLongitude != estimated.estimatedLon ||
                        current.towerAccuracyMeters != estimated.accuracyMeters ||
                        current.towerReadingCount != estimated.readingCount
                    ) {
                        _uiState.value = current.copy(
                            towerLatitude = estimated.estimatedLat,
                            towerLongitude = estimated.estimatedLon,
                            towerAccuracyMeters = estimated.accuracyMeters,
                            towerReadingCount = estimated.readingCount,
                            towerConfidenceScore = estimated.confidenceScore,
                            towerTechniques = estimated.techniques
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        towerObserveJob?.cancel()
    }

    fun acknowledgeAlert() {
        viewModelScope.launch {
            alertRepository.acknowledgeAlert(alertId)
            _uiState.value = _uiState.value.copy(isAcknowledged = true)
        }
    }

    /**
     * Record user feedback for this alert.
     *
     * @param feedback One of "FALSE_POSITIVE", "CONFIRMED_THREAT", "KNOWN_DEVICE", "UNSURE".
     */
    fun submitFeedback(feedback: String) {
        val alert = _uiState.value.alert ?: return

        viewModelScope.launch {
            val details = try { JSONObject(alert.detailsJson) } catch (_: Exception) { JSONObject() }

            val ssid = details.optString("ssid", "").takeIf { it.isNotEmpty() }
            val bssid = details.optString("bssid", "").takeIf { it.isNotEmpty() }

            val entity = AlertFeedbackEntity(
                alertId = alert.id,
                threatType = alert.threatType.name,
                feedback = feedback,
                cellId = details.optLong("cellId", -1L).takeIf { it > 0 },
                lac = details.optInt("lac", -1).takeIf { it > 0 },
                mcc = details.optInt("mcc", -1).takeIf { it > 0 },
                mnc = details.optInt("mnc", -1).takeIf { it >= 0 },
                bssid = bssid,
                ssid = ssid,
                signalStrength = details.optInt("signalStrength", Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE },
                latitude = details.optDouble("latitude", Double.NaN)
                    .takeIf { !it.isNaN() },
                longitude = details.optDouble("longitude", Double.NaN)
                    .takeIf { !it.isNaN() },
                timestamp = System.currentTimeMillis(),
                detailsSnapshot = alert.detailsJson
            )

            feedbackDao.insertFeedback(entity)

            // For KNOWN_DEVICE on WiFi alerts: trust the SSID (all APs broadcasting it)
            val isWifiAlert = ssid != null || bssid != null
            if (feedback == "KNOWN_DEVICE" && isWifiAlert && ssid != null) {
                // Add the SSID as a trusted network. Insert a representative entry
                // with the BSSID from this alert; the trust is SSID-level.
                trustedNetworkDao.insert(
                    TrustedNetworkEntity(
                        bssid = bssid ?: "ssid-trust:$ssid",
                        ssid = ssid,
                        label = "$ssid (trusted via alert feedback)"
                    )
                )
            }

            val confirmationMsg = when {
                feedback == "FALSE_POSITIVE" && isWifiAlert ->
                    "Got it. Future alerts for this network will be adjusted."
                feedback == "FALSE_POSITIVE" ->
                    "Got it. Future alerts from this tower will be adjusted."
                feedback == "CONFIRMED_THREAT" ->
                    "Confirmed. We'll stay extra alert for this threat type here."
                feedback == "KNOWN_DEVICE" && ssid != null ->
                    "\"$ssid\" and all its access points are now trusted. Future evil-twin alerts for this network will be suppressed."
                feedback == "KNOWN_DEVICE" ->
                    "Good catch! This tower is now marked as a known device (booster/femtocell). We'll suppress future alerts for this tower but the detection was correct."
                else -> "Noted. We'll keep monitoring."
            }

            _uiState.value = _uiState.value.copy(
                feedbackGiven = feedback,
                feedbackConfirmation = confirmationMsg
            )

            // Auto-acknowledge this alert + ALL matching alerts for the same tower/network.
            if (feedback == "FALSE_POSITIVE" || feedback == "KNOWN_DEVICE") {
                // Acknowledge this specific alert
                alertRepository.acknowledgeAlert(alertId)
                _uiState.value = _uiState.value.copy(isAcknowledged = true)

                // Bulk-acknowledge ALL alerts for this tower CID or SSID.
                // For cell towers: the anomalous CID may not be in the "cellId"
                // field (which stores the serving cell). The flagged CID appears
                // in indicator keys like "duplicate_cid_245249855" or "band_change_245249855".
                // So we search for the CID number anywhere in the JSON string.
                if (isWifiAlert && ssid != null) {
                    alertRepository.acknowledgeAllForSsid(ssid)
                } else {
                    // Try the cellId field first (covers serving cell alerts)
                    val cid = details.optLong("cellId", -1L)
                    if (cid > 0) {
                        alertRepository.acknowledgeAllForSsid("$cid")
                    }
                    // Also extract CIDs from indicator keys (e.g. "duplicate_cid_pci_245249855")
                    val allCids = mutableSetOf<String>()
                    details.keys().forEach { key ->
                        // Keys like band_change_12345, duplicate_cid_12345, signal_anomaly_12345
                        val parts = key.split("_")
                        val lastPart = parts.lastOrNull() ?: ""
                        if (lastPart.toLongOrNull() != null && lastPart.length >= 3) {
                            allCids.add(lastPart)
                        }
                    }
                    allCids.forEach { cidStr ->
                        alertRepository.acknowledgeAllForSsid(cidStr)
                    }
                }

                // Dismiss from fusion engine + recalculate.
                // Dismiss the specific type AND related types (a trusted tower
                // may have triggered multiple detector types).
                sensorFusionEngine.dismissDetection(alert.threatType.name)
                if (!isWifiAlert) {
                    // Cell tower trust: also dismiss related cellular detections
                    listOf(
                        "FAKE_BTS", "KNOWN_TOWER_ANOMALY", "SIGNAL_ANOMALY",
                        "NETWORK_DOWNGRADE", "REGISTRATION_FAILURE",
                        "TEMPORAL_ANOMALY", "COMPOUND_PATTERN", "NR_ANOMALY"
                    ).forEach { sensorFusionEngine.dismissDetection(it) }
                }
                sensorFusionEngine.recalculateClean()
            }
        }
    }
}
