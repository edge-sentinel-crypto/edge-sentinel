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

@Entity(tableName = "trusted_networks")
data class TrustedNetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bssid") val bssid: String,        // MAC address of the AP
    @ColumnInfo(name = "ssid") val ssid: String,           // Network name
    @ColumnInfo(name = "label") val label: String? = null,  // User-friendly label ("Kitchen AP", "Guest Network")
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis()
)
