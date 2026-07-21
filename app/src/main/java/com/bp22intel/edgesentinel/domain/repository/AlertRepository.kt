/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.repository

import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import kotlinx.coroutines.flow.Flow

interface AlertRepository {
    suspend fun insertAlert(alert: Alert): Long
    fun getAllAlerts(): Flow<List<Alert>>
    fun getRecentAlerts(limit: Int): Flow<List<Alert>>
    fun getAlertsByThreatLevel(level: ThreatLevel): Flow<List<Alert>>
    suspend fun getAlertById(id: Long): Alert?
    suspend fun acknowledgeAlert(id: Long)
    suspend fun deleteOldAlerts(beforeTimestamp: Long)
    fun getUnacknowledgedCount(): Flow<Int>
    /** Get active (recent, unacknowledged) alerts as a snapshot for cooperative sharing. */
    suspend fun getActiveAlerts(): List<Alert>
    /** Bulk-acknowledge all alerts for a cell tower CID. */
    suspend fun acknowledgeAllForCellId(cellId: Long)
    /** Bulk-acknowledge all alerts matching an SSID. */
    suspend fun acknowledgeAllForSsid(ssid: String)
}
