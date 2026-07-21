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
import com.bp22intel.edgesentinel.data.local.entity.EstimatedTowerPositionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EstimatedTowerPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: EstimatedTowerPositionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(positions: List<EstimatedTowerPositionEntity>)

    @Query("SELECT * FROM estimated_tower_positions WHERE tower_id = :towerId LIMIT 1")
    suspend fun getByTowerId(towerId: String): EstimatedTowerPositionEntity?

    @Query("SELECT * FROM estimated_tower_positions WHERE tower_id = :towerId LIMIT 1")
    fun observeByTowerId(towerId: String): Flow<EstimatedTowerPositionEntity?>

    @Query("SELECT * FROM estimated_tower_positions WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid LIMIT 1")
    suspend fun findByCell(mcc: Int, mnc: Int, lac: Int, cid: Int): EstimatedTowerPositionEntity?

    @Query("SELECT * FROM estimated_tower_positions WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid LIMIT 1")
    fun observeByCell(mcc: Int, mnc: Int, lac: Int, cid: Int): Flow<EstimatedTowerPositionEntity?>

    @Query("SELECT * FROM estimated_tower_positions ORDER BY last_updated DESC")
    fun observeAll(): Flow<List<EstimatedTowerPositionEntity>>

    @Query("SELECT * FROM estimated_tower_positions ORDER BY last_updated DESC")
    suspend fun getAll(): List<EstimatedTowerPositionEntity>

    @Query("DELETE FROM estimated_tower_positions WHERE last_updated < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)

    /** Delete estimated tower positions observed within a time range (e.g. a travel period). */
    @Query("DELETE FROM estimated_tower_positions WHERE last_updated >= :from AND last_updated <= :to")
    suspend fun deleteBetween(from: Long, to: Long)

    @Query("SELECT COUNT(*) FROM estimated_tower_positions")
    suspend fun count(): Int
}
