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
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Provides filtered access to alerts from the repository.
 */
class GetAlertsUseCase @Inject constructor(
    private val alertRepository: AlertRepository
) {

    /** Returns all alerts ordered by recency. */
    fun all(): Flow<List<Alert>> = alertRepository.getAllAlerts()

    /** Returns the most recent [limit] alerts. */
    fun recent(limit: Int = 50): Flow<List<Alert>> = alertRepository.getRecentAlerts(limit)

    /** Returns alerts filtered by [level]. */
    fun byThreatLevel(level: ThreatLevel): Flow<List<Alert>> =
        alertRepository.getAlertsByThreatLevel(level)

    /** Retrieves a single alert by its [id]. */
    suspend fun byId(id: Long): Alert? = alertRepository.getAlertById(id)

    /** Marks an alert as acknowledged. */
    suspend fun acknowledge(id: Long) = alertRepository.acknowledgeAlert(id)
}
