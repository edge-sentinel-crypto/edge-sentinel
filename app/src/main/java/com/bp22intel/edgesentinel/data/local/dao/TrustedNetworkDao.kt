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
import com.bp22intel.edgesentinel.data.local.entity.TrustedNetworkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedNetworkDao {
    @Query("SELECT * FROM trusted_networks ORDER BY ssid")
    fun getAllTrusted(): Flow<List<TrustedNetworkEntity>>

    @Query("SELECT * FROM trusted_networks WHERE bssid = :bssid LIMIT 1")
    suspend fun findByBssid(bssid: String): TrustedNetworkEntity?

    @Query("SELECT bssid FROM trusted_networks")
    suspend fun getAllTrustedBssids(): List<String>

    @Query("SELECT DISTINCT ssid FROM trusted_networks")
    suspend fun getAllTrustedSsids(): List<String>

    @Query("SELECT * FROM trusted_networks WHERE ssid = :ssid")
    suspend fun findBySsid(ssid: String): List<TrustedNetworkEntity>

    @Query("SELECT COUNT(*) FROM trusted_networks WHERE ssid = :ssid")
    suspend fun isSsidTrusted(ssid: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(network: TrustedNetworkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(networks: List<TrustedNetworkEntity>)

    @Query("DELETE FROM trusted_networks WHERE bssid = :bssid")
    suspend fun removeByBssid(bssid: String)

    @Query("DELETE FROM trusted_networks WHERE ssid = :ssid")
    suspend fun removeBySsid(ssid: String)

    @Query("DELETE FROM trusted_networks WHERE id = :id")
    suspend fun removeById(id: Long)

    @Query("SELECT COUNT(*) FROM trusted_networks")
    suspend fun getCount(): Int
}
