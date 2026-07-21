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
    private val towerPositionTracker: com.bp22intel.edgesentinel.detection.geo.TowerPositionTracker
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
            val detectionResults = threatDetectionEngine.runScan(
                cells = currentCells,
                history = history
            )

            // Determine overall threat level
            val scanThreatLevel = if (detectionResults.isEmpty()) {
                ThreatLevel.CLEAR
            } else {
                val maxScore = detectionResults.maxOf { it.score }
                when {
                    maxScore >= 0.7 -> ThreatLevel.THREAT
                    maxScore >= 0.4 -> ThreatLevel.SUSPICIOUS
                    else -> ThreatLevel.CLEAR
                }
            }

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
                val severity = when {
                    result.score >= 0.7 -> ThreatLevel.THREAT
                    result.score >= 0.4 -> ThreatLevel.SUSPICIOUS
                    else -> ThreatLevel.CLEAR
                }

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
