/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bp22intel.edgesentinel.data.sensor.CellInfoCollector
import com.bp22intel.edgesentinel.detection.engine.ThreatDetectionEngine
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.ScanResult
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import com.bp22intel.edgesentinel.domain.repository.ScanRepository
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.bp22intel.edgesentinel.sensor.MotionDetector
import com.bp22intel.edgesentinel.sensor.MotionState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val cellInfoCollector: CellInfoCollector,
    private val threatDetectionEngine: ThreatDetectionEngine,
    private val cellRepository: CellRepository,
    private val alertRepository: AlertRepository,
    private val scanRepository: ScanRepository,
    private val towerPositionTracker: com.bp22intel.edgesentinel.detection.geo.TowerPositionTracker,
    private val settingsDataStore: DataStore<Preferences>,
    private val motionDetector: MotionDetector
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "edge_sentinel_periodic_scan"
        private const val SCAN_INTERVAL_MINUTES = 15L

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScanWorker>(
                SCAN_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        /**
         * N3: cancels the unique work, waits for WorkManager to record the
         * cancel, then waits until no instance is RUNNING (bounded by [timeoutMs]).
         * Blocking; call from Dispatchers.IO.
         */
        fun cancelAndAwait(context: Context, timeoutMs: Long) {
            val wm = WorkManager.getInstance(context)
            val deadline = System.currentTimeMillis() + timeoutMs
            wm.cancelUniqueWork(UNIQUE_WORK_NAME).result.get(timeoutMs, TimeUnit.MILLISECONDS)
            while (System.currentTimeMillis() < deadline) {
                val infos = wm.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
                if (infos.none { it.state == androidx.work.WorkInfo.State.RUNNING }) return
                Thread.sleep(100)
            }
        }
    }

    override suspend fun doWork(): Result {
        // Skip if the foreground MonitoringService is already running —
        // it handles scanning on its own schedule.
        if (MonitoringService.isRunning.value) {
            return Result.success()
        }

        val startTime = System.currentTimeMillis()

        return try {
            val currentCells = cellInfoCollector.getCurrentCellInfo()
            val history = cellRepository.getAllCells().first()

            // Save observed cells
            for (cell in currentCells) {
                cellRepository.insertOrUpdateCell(cell)
            }

            // Feed towers into continuous position tracker
            val userLocation = try {
                val locMgr = applicationContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                @Suppress("MissingPermission")
                locMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locMgr.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) { null }

            if (userLocation != null) {
                towerPositionTracker.processScanResults(
                    cells = currentCells,
                    userLat = userLocation.latitude,
                    userLng = userLocation.longitude
                )
            }

            // Run threat detection
            val sensitivity = ScanThreatLevels.readSensitivity(settingsDataStore)
            val motionKnown = motionDetector.motionState.value != MotionState.UNKNOWN
            val outcome = threatDetectionEngine.runScanWithScore(
                cells = currentCells,
                history = history,
                sensitivity = sensitivity,
                latitude = userLocation?.latitude,
                longitude = userLocation?.longitude,
                isMoving = if (motionKnown) motionDetector.isMoving else null
            )
            val detectionResults = outcome.results

            // Overall level comes from the ThreatScorer result.
            val scanThreatLevel = ScanThreatLevels.overallLevel(outcome.scoringResult, detectionResults, sensitivity)

            // Save scan result
            val durationMs = System.currentTimeMillis() - startTime
            val scanResult = ScanResult(
                timestamp = startTime,
                cellCount = currentCells.size,
                threatLevel = scanThreatLevel,
                durationMs = durationMs
            )
            scanRepository.insertScan(scanResult)

            // Create alerts for suspicious or threatening results
            for (result in detectionResults) {
                val severity = ScanThreatLevels.alertSeverity(result.score, sensitivity)

                if (severity == ThreatLevel.SUSPICIOUS || severity == ThreatLevel.THREAT) {
                    val alert = Alert(
                        timestamp = startTime,
                        threatType = result.threatType,
                        severity = severity,
                        confidence = result.confidence,
                        summary = result.summary,
                        detailsJson = result.details.entries.joinToString(",") {
                            "\"${it.key}\":\"${it.value}\""
                        }.let { "{$it}" },
                        cellId = currentCells.firstOrNull()?.id,
                        acknowledged = false
                    )
                    alertRepository.insertAlert(alert)
                }
            }

            Result.success()
        } catch (e: Exception) {
            // Save a failed scan record
            val durationMs = System.currentTimeMillis() - startTime
            try {
                scanRepository.insertScan(
                    ScanResult(
                        timestamp = startTime,
                        cellCount = 0,
                        threatLevel = ThreatLevel.CLEAR,
                        durationMs = durationMs
                    )
                )
            } catch (_: Exception) {
                // Best effort
            }
            Result.retry()
        }
    }
}
