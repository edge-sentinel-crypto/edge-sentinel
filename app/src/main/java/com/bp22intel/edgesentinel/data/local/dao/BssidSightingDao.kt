/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 */

package com.bp22intel.edgesentinel.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bp22intel.edgesentinel.data.local.entity.BssidSightingEntity

/** Round 4 (D2): persisted Wi-Fi BSSID sighting records. */
@Dao
interface BssidSightingDao {
    @Query("SELECT * FROM wifi_bssid_sightings")
    suspend fun getAll(): List<BssidSightingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<BssidSightingEntity>)

    @Query("DELETE FROM wifi_bssid_sightings WHERE record_key IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    @Query("DELETE FROM wifi_bssid_sightings")
    suspend fun deleteAll()
}
