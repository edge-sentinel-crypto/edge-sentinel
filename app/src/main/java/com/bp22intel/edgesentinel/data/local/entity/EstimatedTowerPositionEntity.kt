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

/**
 * Persistent estimated tower position, continuously refined as new
 * signal readings arrive from different user locations.
 *
 * Accuracy improves over time:
 * - 1 reading:  RSSI-only estimate, ~500m
 * - 5 readings: trilateration possible, ~200m
 * - 20+ readings: full NLS + particle filter convergence, ~50-100m
 */
@Entity(tableName = "estimated_tower_positions")
data class EstimatedTowerPositionEntity(
    /** Composite key: "mcc_mnc_lac_cid" */
    @PrimaryKey
    @ColumnInfo(name = "tower_id")
    val towerId: String,

    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,

    @ColumnInfo(name = "estimated_lat")
    val estimatedLat: Double,

    @ColumnInfo(name = "estimated_lon")
    val estimatedLon: Double,

    @ColumnInfo(name = "accuracy_meters")
    val accuracyMeters: Double,

    /** How many signal readings contributed to this estimate. */
    @ColumnInfo(name = "reading_count")
    val readingCount: Int,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long,

    /** Comma-separated list of techniques that contributed. */
    val techniques: String,

    /** 0.0 to 1.0, improves with more readings. */
    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Float
)
