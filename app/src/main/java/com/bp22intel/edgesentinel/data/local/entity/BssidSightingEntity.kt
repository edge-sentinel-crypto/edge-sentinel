/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 */

package com.bp22intel.edgesentinel.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Round 4 (D2): persisted per-(SSID, BSSID) Wi-Fi sighting record. Lives in
 * edge_sentinel.db, so it is excluded from backup (backup_rules.xml,
 * data_extraction_rules.xml) and deleted by the panic wipe.
 */
@Entity(tableName = "wifi_bssid_sightings")
data class BssidSightingEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_key")
    val recordKey: String,
    @ColumnInfo(name = "ssid")
    val ssid: String,
    @ColumnInfo(name = "bssid")
    val bssid: String,
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,
    @ColumnInfo(name = "sightings")
    val sightings: Int,
    @ColumnInfo(name = "sessions")
    val sessions: Int,
    @ColumnInfo(name = "security_type")
    val securityType: String,
    @ColumnInfo(name = "last_counted_scan")
    val lastCountedScan: Long,
    @ColumnInfo(name = "last_session_id")
    val lastSessionId: Long
)
