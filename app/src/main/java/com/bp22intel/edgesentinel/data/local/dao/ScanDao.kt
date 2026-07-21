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
import com.bp22intel.edgesentinel.data.local.entity.ScanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Query("SELECT * FROM scans ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<ScanEntity>>
}
