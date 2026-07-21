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
 * User feedback on an alert — drives the false-positive learning system.
 *
 * Each row captures the user's assessment of an alert together with enough
 * context (tower identity, location, signal) to let [FalsePositiveFilter]
 * generalise: "alerts of this type at this tower are usually false positives."
 */
@Entity(tableName = "alert_feedback")
data class AlertFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** References the original alert's [AlertEntity.id]. */
    @ColumnInfo(name = "alert_id")
    val alertId: Long,

    /** [ThreatType] enum name (e.g. "FAKE_BTS", "NETWORK_DOWNGRADE"). */
    @ColumnInfo(name = "threat_type")
    val threatType: String,

    /** One of "FALSE_POSITIVE", "CONFIRMED_THREAT", "UNSURE". */
    val feedback: String,

    /** Cell tower CID that triggered the alert (nullable for WiFi-only alerts). */
    @ColumnInfo(name = "cell_id")
    val cellId: Long?,

    val lac: Int?,
    val mcc: Int?,
    val mnc: Int?,

    /** WiFi BSSID if the alert was WiFi-related. */
    val bssid: String?,

    /** WiFi SSID if the alert was WiFi-related (for SSID-level trust). */
    val ssid: String? = null,

    @ColumnInfo(name = "signal_strength")
    val signalStrength: Int?,

    /** User's latitude when feedback was given. */
    val latitude: Double?,

    /** User's longitude when feedback was given. */
    val longitude: Double?,

    /** Unix epoch millis when the feedback was recorded. */
    val timestamp: Long,

    /** Snapshot of the original alert's detailsJson for replay/audit. */
    @ColumnInfo(name = "details_snapshot")
    val detailsSnapshot: String
)
