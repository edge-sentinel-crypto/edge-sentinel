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
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bp22intel.edgesentinel.data.local.entity.BaselineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BaselineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(baseline: BaselineEntity): Long

    @Update
    suspend fun update(baseline: BaselineEntity)

    @Query("SELECT * FROM baselines ORDER BY updated_at DESC")
    fun getAll(): Flow<List<BaselineEntity>>

    @Query("SELECT * FROM baselines WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BaselineEntity?

    @Query("SELECT * FROM baselines")
    suspend fun getAllSync(): List<BaselineEntity>

    @Query("DELETE FROM baselines WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM baselines")
    suspend fun deleteAll()
}
