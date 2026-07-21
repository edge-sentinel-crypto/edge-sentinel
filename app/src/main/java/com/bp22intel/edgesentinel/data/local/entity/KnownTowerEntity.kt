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
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "known_towers", indices = [
    Index(value = ["mcc", "mnc", "lac", "cid"], unique = true)
])
data class KnownTowerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "mcc") val mcc: Int,           // Mobile Country Code
    @ColumnInfo(name = "mnc") val mnc: Int,           // Mobile Network Code
    @ColumnInfo(name = "lac") val lac: Int,            // Location Area Code
    @ColumnInfo(name = "cid") val cid: Int,            // Cell ID
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "range") val range: Int,        // estimated range in meters
    @ColumnInfo(name = "radio") val radio: String,     // GSM, UMTS, LTE, NR
    @ColumnInfo(name = "samples") val samples: Int = 0,              // observation count (trust signal)
    @ColumnInfo(name = "source") val source: String = "opencellid", // data source
    @ColumnInfo(name = "updated") val updated: Long = System.currentTimeMillis()
) {
    /**
     * Trust score based on observation count.
     * More observations = more independent users confirmed this tower exists.
     * Scale: 0.0 (untrusted) to 1.0 (highly trusted)
     *
     * Rationale: An attacker would need to generate many independent
     * observations over time to achieve a high trust score. A freshly
     * added fake tower will have few samples and low trust.
     */
    val trustScore: Float get() = when {
        samples >= 100 -> 1.0f    // High confidence — many observers
        samples >= 50  -> 0.85f
        samples >= 20  -> 0.7f
        samples >= 10  -> 0.5f    // Moderate — enough to be plausible
        samples >= 5   -> 0.3f    // Low — could be legitimate or planted
        else           -> 0.1f    // Minimal trust
    }
}