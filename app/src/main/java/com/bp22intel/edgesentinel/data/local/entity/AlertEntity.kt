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

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "threat_type")
    val threatType: String,
    val severity: String,
    val confidence: String,
    val summary: String,
    @ColumnInfo(name = "details_json")
    val detailsJson: String,
    @ColumnInfo(name = "cell_id")
    val cellId: Long?,
    val acknowledged: Boolean = false
)
