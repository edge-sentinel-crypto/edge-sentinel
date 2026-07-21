/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "baselines")
data class BaselineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    val label: String? = null,
    @ColumnInfo(name = "cell_towers_json")
    val cellTowersJson: String,
    @ColumnInfo(name = "wifi_aps_json")
    val wifiApsJson: String,
    @ColumnInfo(name = "ble_count_min")
    val bleCountMin: Int,
    @ColumnInfo(name = "ble_count_max")
    val bleCountMax: Int,
    @ColumnInfo(name = "network_type_dist_json")
    val networkTypeDistJson: String,
    @ColumnInfo(name = "observation_count")
    val observationCount: Int,
    val confidence: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "day_profile_json")
    val dayProfileJson: String?,
    @ColumnInfo(name = "night_profile_json")
    val nightProfileJson: String?
)
