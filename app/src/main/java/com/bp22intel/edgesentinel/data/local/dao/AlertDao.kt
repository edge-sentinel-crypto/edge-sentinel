/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bp22intel.edgesentinel.data.local.entity.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    @Insert
    suspend fun insert(alert: AlertEntity): Long

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC")
    fun getAll(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE severity = :severity ORDER BY timestamp DESC")
    fun getBySeverity(severity: String): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AlertEntity?

    @Query("UPDATE alerts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("DELETE FROM alerts WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    /** Delete alerts within a time range (e.g., travel period). */
    @Query("DELETE FROM alerts WHERE timestamp >= :from AND timestamp <= :to")
    suspend fun deleteBetween(from: Long, to: Long)

    @Query("SELECT COUNT(*) FROM alerts WHERE acknowledged = 0")
    fun getUnacknowledgedCount(): Flow<Int>

    @Query("SELECT * FROM alerts WHERE acknowledged = 0 AND timestamp > :since ORDER BY timestamp DESC LIMIT 20")
    suspend fun getActiveSince(since: Long): List<AlertEntity>

    /** Bulk-acknowledge all unacknowledged alerts for a specific cell tower CID. */
    @Query("UPDATE alerts SET acknowledged = 1 WHERE acknowledged = 0 AND cell_id = :cellId")
    suspend fun acknowledgeByCellId(cellId: Long)

    /** Bulk-acknowledge all unacknowledged alerts whose detailsJson contains the given SSID. */
    @Query("UPDATE alerts SET acknowledged = 1 WHERE acknowledged = 0 AND details_json LIKE '%' || :ssid || '%'")
    suspend fun acknowledgeBySsid(ssid: String)

    /** Get all unacknowledged alerts. */
    @Query("SELECT * FROM alerts WHERE acknowledged = 0 ORDER BY timestamp DESC")
    suspend fun getAllUnacknowledged(): List<AlertEntity>
}
