/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.sweep

import com.bp22intel.edgesentinel.ui.utils.TimeFormatUtils
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.detection.geo.HeatMapPoint
import com.bp22intel.edgesentinel.detection.geo.ThreatGeolocation
import com.bp22intel.edgesentinel.export.AlertExporter
import com.bp22intel.edgesentinel.mesh.CooperativeLocalizationManager
import com.bp22intel.edgesentinel.mesh.CooperativeObservation
import com.bp22intel.edgesentinel.mesh.CooperativeTrilateration
import com.bp22intel.edgesentinel.mesh.MeshUiState
import com.bp22intel.edgesentinel.mesh.MeshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Sweep state for the full-screen tactical sweep mode.
 */
data class SweepState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val startTimestamp: Long = 0L
)

/**
 * Represents a peer device visible during the sweep.
 */
data class SweepPeer(
    val deviceId: String,
    val displayLabel: String,
    val latCoarse: Double,
    val lngCoarse: Double,
    val lastSeen: Long,
    val status: PeerStatus
)

enum class PeerStatus {
    /** Contributing observations to the sweep. */
    CONTRIBUTING,
    /** Connected but hasn't seen any threats. */
    CONNECTED_IDLE,
    /** Out of BLE range or stale. */
    OUT_OF_RANGE
}

/**
 * A suspicious tower target tracked during the sweep.
 */
data class SweepTarget(
    val cid: Long,
    val mcc: Int,
    val mnc: Int,
    val observationCount: Int,
    val totalDevices: Int,
    val participatingDevices: Int,
    val estimatedLat: Double?,
    val estimatedLng: Double?,
    val accuracyMeters: Double?,
    val firstDetectedMs: Long,
    val threatType: String,
    val observations: List<CooperativeObservation> = emptyList()
) {
    val hasEstimate: Boolean get() = estimatedLat != null && estimatedLng != null
    val accuracyColor: AccuracyTier
        get() = when {
            accuracyMeters == null -> AccuracyTier.UNKNOWN
            accuracyMeters > 500 -> AccuracyTier.LOW
            accuracyMeters > 200 -> AccuracyTier.MEDIUM
            else -> AccuracyTier.HIGH
        }
}

enum class AccuracyTier { UNKNOWN, LOW, MEDIUM, HIGH }

/**
 * A location pin dropped during the sweep.
 */
data class SweepMarker(
    val lat: Double,
    val lng: Double,
    val note: String,
    val timestamp: Long
)

/**
 * Complete UI state for SweepModeScreen.
 */
data class SweepUiState(
    val sweep: SweepState = SweepState(),
    val userLat: Double = 0.0,
    val userLng: Double = 0.0,
    val userHeading: Float = 0f,
    val peers: List<SweepPeer> = emptyList(),
    val targets: List<SweepTarget> = emptyList(),
    val markers: List<SweepMarker> = emptyList(),
    val meshActive: Boolean = false,
    val cooperativeEnabled: Boolean = false,
    val flashObservationDeviceId: String? = null,
    val targetLocatedCid: Long? = null
)

@HiltViewModel
class SweepModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertExporter: AlertExporter,
    private val threatGeolocation: ThreatGeolocation
) : ViewModel() {

    private val _uiState = MutableStateFlow(SweepUiState())
    val uiState: StateFlow<SweepUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var updateJob: Job? = null
    private var sweepStartTime: Long = 0L

    /** External references set by the screen after composition. */
    private var meshUiStateProvider: (() -> MeshUiState)? = null
    private var cooperativeEnabledProvider: (() -> Boolean)? = null
    private var userLocationProvider: (() -> Pair<Double, Double>)? = null

    /**
     * Flattened heat map points (local + peer) for sweep map overlay.
     */
    val heatMapPoints: StateFlow<List<HeatMapPoint>> = threatGeolocation.heatMapPoints
        .let { flow ->
            val mapped = MutableStateFlow<List<HeatMapPoint>>(emptyList())
            viewModelScope.launch {
                flow.collectLatest { map ->
                    mapped.value = map.values.flatten()
                }
            }
            mapped.asStateFlow()
        }

    // Track previously known accuracy per CID to detect improvements
    private val previousAccuracy = mutableMapOf<Long, Double>()

    // Sweep history for report
    private val sweepHistory = mutableListOf<SweepTarget>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val timeOnlyFormat = SimpleDateFormat("HH:mm z", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    /**
     * Bind external state providers. Called from the composable to bridge
     * MeshViewModel data into SweepMode without a direct Hilt dependency.
     */
    fun bindProviders(
        meshUiState: () -> MeshUiState,
        cooperativeEnabled: () -> Boolean,
        userLocation: () -> Pair<Double, Double>
    ) {
        meshUiStateProvider = meshUiState
        cooperativeEnabledProvider = cooperativeEnabled
        userLocationProvider = userLocation
    }

    fun startSweep() {
        if (_uiState.value.sweep.isActive) return

        sweepStartTime = System.currentTimeMillis()
        previousAccuracy.clear()
        sweepHistory.clear()

        _uiState.update {
            it.copy(
                sweep = SweepState(
                    isActive = true,
                    isPaused = false,
                    elapsedMs = 0L,
                    startTimestamp = sweepStartTime
                )
            )
        }

        // Elapsed timer
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (!_uiState.value.sweep.isPaused) {
                    _uiState.update {
                        it.copy(
                            sweep = it.sweep.copy(
                                elapsedMs = System.currentTimeMillis() - sweepStartTime
                            )
                        )
                    }
                }
            }
        }

        // Periodic data refresh from mesh
        updateJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refreshFromMesh()
            }
        }
    }

    fun stopSweep() {
        timerJob?.cancel()
        updateJob?.cancel()
        timerJob = null
        updateJob = null

        _uiState.update {
            it.copy(sweep = it.sweep.copy(isActive = false))
        }
    }

    fun togglePause() {
        _uiState.update {
            it.copy(sweep = it.sweep.copy(isPaused = !it.sweep.isPaused))
        }
    }

    /**
     * Drop a pin marker at the user's current position.
     */
    fun markLocation(note: String) {
        val state = _uiState.value
        val marker = SweepMarker(
            lat = state.userLat,
            lng = state.userLng,
            note = note,
            timestamp = System.currentTimeMillis()
        )
        _uiState.update { it.copy(markers = it.markers + marker) }
    }

    /**
     * Clear the "target located" toast after it's been shown.
     */
    fun clearTargetLocated() {
        _uiState.update { it.copy(targetLocatedCid = null) }
    }

    /**
     * Clear observation flash after animation completes.
     */
    fun clearObservationFlash() {
        _uiState.update { it.copy(flashObservationDeviceId = null) }
    }

    /**
     * Refresh sweep state from MeshViewModel + cooperative data.
     */
    private fun refreshFromMesh() {
        val meshState = meshUiStateProvider?.invoke() ?: return
        val coopEnabled = cooperativeEnabledProvider?.invoke() ?: false
        val (userLat, userLng) = userLocationProvider?.invoke() ?: Pair(0.0, 0.0)

        val trilaterations = meshState.cooperativeMode.trilaterations
        val discoveredPeers = meshState.discoveredPeers

        // Build peer list
        val peers = discoveredPeers
            .filter { it.isEdgeSentinel }
            .mapIndexed { index, peer ->
                val peerLabel = "Peer ${('A' + index).toChar()}"
                val isContributing = trilaterations.any { trilat ->
                    trilat.observations.any { obs -> obs.deviceId == peer.deviceAddress.takeLast(8) }
                }
                val isRecent = (System.currentTimeMillis() - peer.lastSeen) < 60_000

                SweepPeer(
                    deviceId = peer.deviceAddress,
                    displayLabel = peer.deviceName ?: peerLabel,
                    latCoarse = 0.0, // Peers don't share exact position to us
                    lngCoarse = 0.0,
                    lastSeen = peer.lastSeen,
                    status = when {
                        !isRecent -> PeerStatus.OUT_OF_RANGE
                        isContributing -> PeerStatus.CONTRIBUTING
                        else -> PeerStatus.CONNECTED_IDLE
                    }
                )
            }

        // Build target list from trilaterations
        val totalDevices = peers.size + 1 // +1 for self
        val targets = trilaterations.map { trilat ->
            val firstObs = trilat.observations.minByOrNull { it.timestamp }
            SweepTarget(
                cid = trilat.cellId,
                mcc = firstObs?.mcc ?: 0,
                mnc = firstObs?.mnc ?: 0,
                observationCount = trilat.observations.size,
                totalDevices = totalDevices,
                participatingDevices = trilat.participatingDevices,
                estimatedLat = trilat.estimatedLat,
                estimatedLng = trilat.estimatedLng,
                accuracyMeters = trilat.estimatedAccuracyM,
                firstDetectedMs = trilat.observations.minOfOrNull { it.timestamp }
                    ?: System.currentTimeMillis(),
                threatType = firstObs?.threatType ?: "UNKNOWN",
                observations = trilat.observations
            )
        }

        // Detect accuracy improvements → trigger haptic + toast
        var newlyLocatedCid: Long? = null
        targets.forEach { target ->
            val prevAcc = previousAccuracy[target.cid]
            if (target.accuracyMeters != null) {
                if (prevAcc != null && prevAcc > 200.0 && target.accuracyMeters <= 200.0) {
                    newlyLocatedCid = target.cid
                }
                previousAccuracy[target.cid] = target.accuracyMeters
            }
        }

        // Detect new observations → flash
        val latestObs = trilaterations
            .flatMap { it.observations }
            .maxByOrNull { it.timestamp }
        val flashDevice = if (latestObs != null &&
            System.currentTimeMillis() - latestObs.timestamp < 6_000
        ) {
            latestObs.deviceId
        } else null

        // Update sweep history
        sweepHistory.clear()
        sweepHistory.addAll(targets)

        _uiState.update {
            it.copy(
                userLat = userLat,
                userLng = userLng,
                peers = peers,
                targets = targets,
                meshActive = meshState.isActive,
                cooperativeEnabled = coopEnabled,
                flashObservationDeviceId = flashDevice ?: it.flashObservationDeviceId,
                targetLocatedCid = newlyLocatedCid ?: it.targetLocatedCid
            )
        }
    }

    /**
     * Generate a plain-text sweep report.
     */
    fun generateSweepReport(): String {
        val state = _uiState.value
        val duration = TimeFormatUtils.formatDurationClock(state.sweep.elapsedMs)
        val now = dateFormat.format(Date())

        return buildString {
            appendLine("EDGE SENTINEL SWEEP REPORT")
            appendLine("===========================")
            appendLine("Date: $now")
            appendLine("Duration: $duration")
            appendLine("Devices: ${state.peers.size + 1} (You${state.peers.joinToString("") { ", ${it.displayLabel}" }})")

            // Estimate coverage area from peer spread
            val allLats = state.targets.mapNotNull { it.estimatedLat } +
                listOf(state.userLat)
            val allLngs = state.targets.mapNotNull { it.estimatedLng } +
                listOf(state.userLng)
            if (allLats.size > 1) {
                val latSpread = (allLats.max() - allLats.min()) * 111.0 // km
                val lngSpread = (allLngs.max() - allLngs.min()) * 85.0 // km at ~30° lat
                val areaSqKm = latSpread * lngSpread
                appendLine("Coverage Area: ~${"%.2f".format(areaSqKm)} km²")
            }
            appendLine()

            if (state.targets.isEmpty()) {
                appendLine("NO TARGETS DETECTED")
                appendLine("The sweep area appears clear of suspicious cellular infrastructure.")
            } else {
                appendLine("TARGETS LOCATED:")
                state.targets.forEachIndexed { index, target ->
                    appendLine()
                    appendLine("${index + 1}. CID ${target.cid} (MCC ${target.mcc}/MNC ${target.mnc})")
                    if (target.hasEstimate) {
                        appendLine("   Estimated Position: ${"%.4f".format(target.estimatedLat)}°N, ${"%.4f".format(target.estimatedLng)}°W")
                        appendLine("   Accuracy: ±${target.accuracyMeters?.toInt()}m")
                    } else {
                        appendLine("   Position: Insufficient data for estimate")
                    }
                    appendLine("   Observations: ${target.participatingDevices} devices")
                    appendLine("   First Detected: ${timeOnlyFormat.format(Date(target.firstDetectedMs))}")
                    appendLine("   Threat Type: ${target.threatType}")
                }
            }

            if (state.markers.isNotEmpty()) {
                appendLine()
                appendLine("MARKED LOCATIONS:")
                state.markers.forEachIndexed { index, marker ->
                    appendLine("${index + 1}. ${"%.4f".format(marker.lat)}°N, ${"%.4f".format(marker.lng)}°W — ${marker.note}")
                    appendLine("   Marked at: ${timeOnlyFormat.format(Date(marker.timestamp))}")
                }
            }

            appendLine()
            appendLine("PEER SUMMARY:")
            state.peers.forEach { peer ->
                val statusSymbol = when (peer.status) {
                    PeerStatus.CONTRIBUTING -> "✓"
                    PeerStatus.CONNECTED_IDLE -> "⏳"
                    PeerStatus.OUT_OF_RANGE -> "✗"
                }
                appendLine("  [$statusSymbol] ${peer.displayLabel}")
            }

            appendLine()
            appendLine("---")
            appendLine("Report generated by Edge Sentinel")
            appendLine("Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.")
        }
    }

    /**
     * Generate report and return a Share intent.
     */
    fun shareSweepReport(): Intent {
        val report = generateSweepReport()
        val fileName = "edge_sentinel_sweep_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.txt"
        val cacheDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val reportFile = File(cacheDir, fileName).apply { writeText(report) }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            reportFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Edge Sentinel Sweep Report")
            putExtra(Intent.EXTRA_TEXT, report)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // formatDuration removed — use TimeFormatUtils.formatDurationClock()

    override fun onCleared() {
        stopSweep()
        super.onCleared()
    }
}
