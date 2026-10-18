/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 * Proprietary and confidential. See LICENSE.
 */

package com.bp22intel.edgesentinel.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit Room migrations for the Edge Sentinel database.
 *
 * Every schema step from v1..v9 that involves a structural change has an
 * explicit migration here. There is intentionally NO destructive fallback in
 * the Room builder: a missing migration must fail loudly in QA rather than
 * silently wiping a user's threat history, learned baselines and tower cache.
 *
 * DDL is kept byte-for-byte consistent with the exported schemas under
 * app/schemas so migrated databases validate against the Room schema.
 */
object Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `baselines` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `radius` REAL NOT NULL,
                    `label` TEXT,
                    `cell_towers_json` TEXT NOT NULL,
                    `wifi_aps_json` TEXT NOT NULL,
                    `ble_count_min` INTEGER NOT NULL,
                    `ble_count_max` INTEGER NOT NULL,
                    `network_type_dist_json` TEXT NOT NULL,
                    `observation_count` INTEGER NOT NULL,
                    `confidence` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `day_profile_json` TEXT,
                    `night_profile_json` TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ble_devices` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `mac_address` TEXT NOT NULL,
                    `advertising_data_hash` TEXT NOT NULL,
                    `manufacturer_id` INTEGER,
                    `device_name` TEXT,
                    `first_seen` INTEGER NOT NULL,
                    `last_seen` INTEGER NOT NULL,
                    `location_clusters` TEXT NOT NULL,
                    `seen_count` INTEGER NOT NULL,
                    `is_tracker_type` INTEGER NOT NULL,
                    `tracker_protocol` TEXT
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `known_towers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `mcc` INTEGER NOT NULL,
                    `mnc` INTEGER NOT NULL,
                    `lac` INTEGER NOT NULL,
                    `cid` INTEGER NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `range` INTEGER NOT NULL,
                    `radio` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `updated` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_known_towers_mcc_mnc_lac_cid` " +
                    "ON `known_towers` (`mcc`, `mnc`, `lac`, `cid`)"
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trusted_networks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bssid` TEXT NOT NULL,
                    `ssid` TEXT NOT NULL,
                    `label` TEXT,
                    `added_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add samples column to known_towers (default 1 for existing rows).
            db.execSQL("ALTER TABLE known_towers ADD COLUMN samples INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `alert_feedback` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `alert_id` INTEGER NOT NULL,
                    `threat_type` TEXT NOT NULL,
                    `feedback` TEXT NOT NULL,
                    `cell_id` INTEGER,
                    `lac` INTEGER,
                    `mcc` INTEGER,
                    `mnc` INTEGER,
                    `bssid` TEXT,
                    `signal_strength` INTEGER,
                    `latitude` REAL,
                    `longitude` REAL,
                    `timestamp` INTEGER NOT NULL,
                    `details_snapshot` TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cells ADD COLUMN earfcn INTEGER NOT NULL DEFAULT ${Int.MAX_VALUE}")
            db.execSQL("ALTER TABLE cells ADD COLUMN pci INTEGER NOT NULL DEFAULT ${Int.MAX_VALUE}")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add ssid column to alert_feedback for SSID-level known network tracking
            db.execSQL("ALTER TABLE alert_feedback ADD COLUMN ssid TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add estimated_tower_positions table (schema v9). DDL matches the
            // exported Room schema 9.json exactly, including the tower_id PK.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `estimated_tower_positions` (
                    `tower_id` TEXT NOT NULL,
                    `mcc` INTEGER NOT NULL,
                    `mnc` INTEGER NOT NULL,
                    `lac` INTEGER NOT NULL,
                    `cid` INTEGER NOT NULL,
                    `estimated_lat` REAL NOT NULL,
                    `estimated_lon` REAL NOT NULL,
                    `accuracy_meters` REAL NOT NULL,
                    `reading_count` INTEGER NOT NULL,
                    `last_updated` INTEGER NOT NULL,
                    `techniques` TEXT NOT NULL,
                    `confidence_score` REAL NOT NULL,
                    PRIMARY KEY(`tower_id`)
                )
                """.trimIndent()
            )
        }
    }

    /** Round 4 (D2): persisted per-(SSID, BSSID) Wi-Fi sighting records. DDL matches 10.json. */
    const val CREATE_WIFI_BSSID_SIGHTINGS_V10 =
        "CREATE TABLE IF NOT EXISTS `wifi_bssid_sightings` (`record_key` TEXT NOT NULL, " +
            "`ssid` TEXT NOT NULL, `bssid` TEXT NOT NULL, `first_seen` INTEGER NOT NULL, " +
            "`last_seen` INTEGER NOT NULL, `sightings` INTEGER NOT NULL, `sessions` INTEGER NOT NULL, " +
            "`security_type` TEXT NOT NULL, `last_counted_scan` INTEGER NOT NULL, " +
            "`last_session_id` INTEGER NOT NULL, PRIMARY KEY(`record_key`))"

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_WIFI_BSSID_SIGHTINGS_V10)
        }
    }

    /** All migrations, in order, for registration with the Room builder. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10
    )
}
