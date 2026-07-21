/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.data.sensor.CellInfoCollector
import com.bp22intel.edgesentinel.detection.engine.DemoDataGenerator
import com.bp22intel.edgesentinel.export.AlertExporter
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.FusedThreatAssessment
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import com.bp22intel.edgesentinel.domain.repository.ScanRepository
import com.bp22intel.edgesentinel.fusion.DashboardPosture
import com.bp22intel.edgesentinel.fusion.OverallThreatDashboard
import com.bp22intel.edgesentinel.fusion.SensorFusionEngine
import com.bp22intel.edgesentinel.sensor.MotionDetector
import com.bp22intel.edgesentinel.sensor.MotionState
import com.bp22intel.edgesentinel.service.MonitoringService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
    private val cellRepository: CellRepository,
    private val scanRepository: ScanRepository,
    private val demoDataGenerator: DemoDataGenerator,
    private val cellInfoCollector: CellInfoCollector,
    private val sensorFusionEngine: SensorFusionEngine,
    private val overallThreatDashboard: OverallThreatDashboard,
    private val threatAnalyst: com.bp22intel.edgesentinel.analysis.ThreatAnalyst,
    private val motionDetector: MotionDetector,
    val alertExporter: AlertExporter
) : ViewModel() {

    val currentThreatLevel: StateFlow<ThreatLevel> = MonitoringService.threatLevel

    val fusedAssessment: StateFlow<FusedThreatAssessment> = sensorFusionEngine.currentAssessment

    val dashboardPosture: StateFlow<DashboardPosture> = sensorFusionEngine.currentAssessment
        .map { assessment -> overallThreatDashboard.computePosture(assessment) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            overallThreatDashboard.computePosture(FusedThreatAssessment.clear())
        )

    private val _currentCell = MutableStateFlow<CellTower?>(null)
    val currentCell: StateFlow<CellTower?> = _currentCell.asStateFlow()

    val recentAlerts: StateFlow<List<Alert>> = alertRepository.getRecentAlerts(20)
        .map { alerts -> alerts.filter { !it.acknowledged }.take(10) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isMonitoring: StateFlow<Boolean> = MonitoringService.isRunning

    private val _monitoringStartTime = MutableStateFlow(0L)
    val monitoringStartTime: StateFlow<Long> = _monitoringStartTime.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Current motion state from accelerometer-based motion detector. */
    val motionState: StateFlow<MotionState> = motionDetector.motionState

    /** AI situation brief — real analysis from ThreatAnalyst engine */
    val situationBrief: StateFlow<com.bp22intel.edgesentinel.analysis.SituationBrief> =
        kotlinx.coroutines.flow.combine(
            recentAlerts,
            _currentCell,
            motionDetector.motionState,
            com.bp22intel.edgesentinel.mesh.CooperativeLocalizationManager.globalTrilaterations
        ) { alerts, cell, motion, coopTrilaterations ->
            val brief = threatAnalyst.analyzeSituation(
                alerts = alerts,
                cellInfo = cell,
                isMoving = motion == MotionState.WALKING || motion == MotionState.DRIVING
            )
            // Inject cooperative localization context if available
            val locatedTrilaterations = coopTrilaterations.filter { it.hasEstimate }
            if (locatedTrilaterations.isNotEmpty()) {
                val coopText = locatedTrilaterations.joinToString(" ") { trilat ->
                    "Tower CID ${trilat.cellId} has been cooperatively located by " +
                    "${trilat.participatingDevices} Edge Sentinel devices. " +
                    "Estimated accuracy: ${trilat.accuracyLabel}."
                }
                brief.copy(cooperativeContext = coopText)
            } else brief
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.bp22intel.edgesentinel.analysis.SituationBrief(
                summary = "Initializing analysis...",
                overallRisk = com.bp22intel.edgesentinel.analysis.RiskLevel.LOW,
                topConcerns = emptyList(),
                recommendations = emptyList(),
                allClear = true
            )
        )

    init {
        viewModelScope.launch {
            MonitoringService.isRunning.collect { running ->
                if (running && _monitoringStartTime.value == 0L) {
                    _monitoringStartTime.value = System.currentTimeMillis()
                } else if (!running) {
                    _monitoringStartTime.value = 0L
                }
            }
        }

        viewModelScope.launch {
            cellRepository.getAllCells().collect { cells ->
                if (cells.isNotEmpty()) {
                    _currentCell.value = cells.maxByOrNull { it.lastSeen }
                }
            }
        }

        // Try to get current cell info on launch
        viewModelScope.launch {
            try {
                val cells = cellInfoCollector.getCurrentCellInfo()
                if (cells.isNotEmpty()) {
                    _currentCell.value = cells.first()
                }
            } catch (_: Exception) {
                // Permission may not be granted yet
            }
        }

        // Hydrate fusion engine from existing alerts on startup (one-shot).
        // This ensures fusion reflects historical alerts, not just new ones.
        viewModelScope.launch {
            try {
                val alerts = alertRepository.getRecentAlerts(50).first()
                if (alerts.isNotEmpty()) {
                    val detections = alerts.map { alert ->
                        val category = when (alert.threatType) {
                            com.bp22intel.edgesentinel.domain.model.ThreatType.FAKE_BTS,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.NETWORK_DOWNGRADE,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.SILENT_SMS,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.CIPHER_ANOMALY,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.SIGNAL_ANOMALY,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.NR_ANOMALY,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.REGISTRATION_FAILURE,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.TEMPORAL_ANOMALY,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.KNOWN_TOWER_ANOMALY,
                            com.bp22intel.edgesentinel.domain.model.ThreatType.COMPOUND_PATTERN ->
                                com.bp22intel.edgesentinel.domain.model.SensorCategory.CELLULAR
                            com.bp22intel.edgesentinel.domain.model.ThreatType.TRACKING_PATTERN ->
                                com.bp22intel.edgesentinel.domain.model.SensorCategory.BLUETOOTH
                        }
                        com.bp22intel.edgesentinel.fusion.ActiveDetection(
                            sensorCategory = category,
                            detectionType = alert.threatType.name,
                            description = alert.summary,
                            score = when (alert.severity) {
                                com.bp22intel.edgesentinel.domain.model.ThreatLevel.THREAT -> 0.9
                                com.bp22intel.edgesentinel.domain.model.ThreatLevel.SUSPICIOUS -> 0.5
                                com.bp22intel.edgesentinel.domain.model.ThreatLevel.CLEAR -> 0.1
                            },
                            timestamp = alert.timestamp
                        )
                    }
                    sensorFusionEngine.ingestDetections(detections)
                }
            } catch (_: Exception) { /* non-critical */ }
        }
    }

    fun toggleMonitoring(context: Context) {
        if (isMonitoring.value) {
            MonitoringService.stop(context)
        } else {
            _monitoringStartTime.value = System.currentTimeMillis()
            MonitoringService.start(context)
        }
    }

    fun forceScan() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val cells = cellInfoCollector.getCurrentCellInfo()
                if (cells.isNotEmpty()) {
                    _currentCell.value = cells.first()
                    for (cell in cells) {
                        cellRepository.insertOrUpdateCell(cell)
                    }
                }
            } catch (_: Exception) {
                // Permission may not be granted yet
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadDemoData() {
        viewModelScope.launch {
            _isDemoMode.value = true

            val demoCells = demoDataGenerator.generateDemoCells()
            for (cell in demoCells) {
                cellRepository.insertOrUpdateCell(cell)
            }
            _currentCell.value = demoCells.firstOrNull()

            val demoAlerts = demoDataGenerator.generateDemoAlerts()
            for (alert in demoAlerts) {
                alertRepository.insertAlert(alert)
            }
        }
    }
}
