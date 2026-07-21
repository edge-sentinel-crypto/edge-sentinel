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
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownTowerDao {
    @Query("SELECT * FROM known_towers WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid LIMIT 1")
    suspend fun findTower(mcc: Int, mnc: Int, lac: Int, cid: Int): KnownTowerEntity?

    @Query("SELECT * FROM known_towers WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid")
    fun observeTower(mcc: Int, mnc: Int, lac: Int, cid: Int): Flow<KnownTowerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTowers(towers: List<KnownTowerEntity>)

    @Query("SELECT COUNT(*) FROM known_towers")
    suspend fun getTowerCount(): Int

    @Query("SELECT COUNT(*) FROM known_towers WHERE mcc = :mcc")
    suspend fun getTowerCountByCountry(mcc: Int): Int

    @Query("SELECT COUNT(*) FROM known_towers WHERE mcc IN (:mccs)")
    suspend fun getTowerCountByCountries(mccs: List<Int>): Int

    @Query("DELETE FROM known_towers WHERE mcc = :mcc")
    suspend fun deleteTowersByCountry(mcc: Int)

    @Query("DELETE FROM known_towers WHERE mcc IN (:mccs)")
    suspend fun deleteTowersByCountries(mccs: List<Int>)

    @Query("SELECT DISTINCT mcc FROM known_towers ORDER BY mcc")
    suspend fun getInstalledCountries(): List<Int>

    @Query("SELECT * FROM known_towers WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng")
    suspend fun findTowersInArea(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<KnownTowerEntity>
}