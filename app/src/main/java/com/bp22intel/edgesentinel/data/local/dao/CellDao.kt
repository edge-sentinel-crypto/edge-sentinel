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
import com.bp22intel.edgesentinel.data.local.entity.CellTowerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CellDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cell: CellTowerEntity): Long

    @Update
    suspend fun update(cell: CellTowerEntity)

    @Query("SELECT * FROM cells ORDER BY last_seen DESC")
    fun getAll(): Flow<List<CellTowerEntity>>

    @Query("SELECT * FROM cells WHERE cid = :cid LIMIT 1")
    suspend fun getByCid(cid: Int): CellTowerEntity?

    @Query("SELECT * FROM cells WHERE lac_tac = :lacTac")
    suspend fun getByLacTac(lacTac: Int): List<CellTowerEntity>
}
