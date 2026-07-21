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

@Entity(tableName = "cells")
data class CellTowerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cid: Int,
    @ColumnInfo(name = "lac_tac")
    val lacTac: Int,
    val mcc: Int,
    val mnc: Int,
    @ColumnInfo(name = "signal_strength")
    val signalStrength: Int,
    @ColumnInfo(name = "network_type")
    val networkType: String,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,
    @ColumnInfo(name = "times_seen")
    val timesSeen: Int,
    @ColumnInfo(name = "earfcn", defaultValue = "${Int.MAX_VALUE}")
    val earfcn: Int = Int.MAX_VALUE,
    @ColumnInfo(name = "pci", defaultValue = "${Int.MAX_VALUE}")
    val pci: Int = Int.MAX_VALUE
)
