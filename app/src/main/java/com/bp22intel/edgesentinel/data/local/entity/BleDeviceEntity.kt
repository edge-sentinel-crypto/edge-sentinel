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

@Entity(tableName = "ble_devices")
data class BleDeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "mac_address")
    val macAddress: String,
    @ColumnInfo(name = "advertising_data_hash")
    val advertisingDataHash: String,
    @ColumnInfo(name = "manufacturer_id")
    val manufacturerId: Int?,
    @ColumnInfo(name = "device_name")
    val deviceName: String?,
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,
    @ColumnInfo(name = "location_clusters")
    val locationClusters: String,
    @ColumnInfo(name = "seen_count")
    val seenCount: Int,
    @ColumnInfo(name = "is_tracker_type")
    val isTrackerType: Boolean,
    @ColumnInfo(name = "tracker_protocol")
    val trackerProtocol: String?
)
