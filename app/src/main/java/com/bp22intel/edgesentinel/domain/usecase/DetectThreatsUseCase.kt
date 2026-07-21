/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.usecase

import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.ScanResult
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import com.bp22intel.edgesentinel.domain.repository.ScanRepository
import javax.inject.Inject

/**
 * Orchestrates threat detection by running detection against observed cells,
 * persisting alerts, and recording scan results.
 */
class DetectThreatsUseCase @Inject constructor(
    private val cellRepository: CellRepository,
    private val alertRepository: AlertRepository,
    private val scanRepository: ScanRepository
) {

    /**
     * Runs threat detection on a list of observed cells using the provided
     * detection function. Persists each cell, creates alerts for any detected
     * threats, and records the scan result.
     *
     * @param cells The currently observed cell towers.
     * @param detect A function that analyzes a cell and its known neighbors
     *               to produce detection results.
     * @return The list of detection results from this scan.
     */
    suspend operator fun invoke(
        cells: List<CellTower>,
        detect: suspend (cell: CellTower, knownCells: List<CellTower>) -> List<DetectionResult>
    ): List<DetectionResult> {
        val startTime = System.currentTimeMillis()
        val allResults = mutableListOf<DetectionResult>()

        for (cell in cells) {
            cellRepository.insertOrUpdateCell(cell)

            val knownCells = cellRepository.getKnownCellsForLac(cell.lacTac)
            val results = detect(cell, knownCells)
            allResults.addAll(results)

            for (result in results) {
                val severity = when {
                    result.score >= 0.8 -> ThreatLevel.THREAT
                    result.score >= 0.5 -> ThreatLevel.SUSPICIOUS
                    else -> ThreatLevel.CLEAR
                }

                if (severity != ThreatLevel.CLEAR) {
                    alertRepository.insertAlert(
                        Alert(
                            timestamp = System.currentTimeMillis(),
                            threatType = result.threatType,
                            severity = severity,
                            confidence = result.confidence,
                            summary = result.summary,
                            detailsJson = result.details.entries.joinToString(",", "{", "}") { (k, v) ->
                                "\"$k\":\"$v\""
                            },
                            cellId = cell.id
                        )
                    )
                }
            }
        }

        val overallThreat = when {
            allResults.any { it.score >= 0.8 } -> ThreatLevel.THREAT
            allResults.any { it.score >= 0.5 } -> ThreatLevel.SUSPICIOUS
            else -> ThreatLevel.CLEAR
        }

        scanRepository.insertScan(
            ScanResult(
                timestamp = startTime,
                cellCount = cells.size,
                threatLevel = overallThreat,
                durationMs = System.currentTimeMillis() - startTime
            )
        )

        return allResults
    }
}
