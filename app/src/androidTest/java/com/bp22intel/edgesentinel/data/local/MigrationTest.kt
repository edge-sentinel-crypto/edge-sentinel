/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 * Proprietary and confidential. See LICENSE.
 */

package com.bp22intel.edgesentinel.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Room migration tests. Builds a v8 database with representative alerts,
 * baseline stats, cell/travel data, migrates to v9, and asserts that old
 * rows survive and the new estimated_tower_positions table exists with the
 * correct schema. Also exercises the full 1..9 chain.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EdgeSentinelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate8To9_preservesDataAndAddsTable() {
        val now = System.currentTimeMillis()

        helper.createDatabase(dbName, 8).apply {
            // Representative alert (travel-period threat history)
            execSQL(
                "INSERT INTO alerts (id, timestamp, threat_type, severity, confidence, summary, details_json, cell_id, acknowledged) " +
                    "VALUES (1, $now, 'FAKE_BTS', 'HIGH', 'HIGH', 'Suspected IMSI catcher', '{}', 42, 0)"
            )
            // Representative baseline (learned environment)
            execSQL(
                "INSERT INTO baselines (id, latitude, longitude, radius, label, cell_towers_json, wifi_aps_json, " +
                    "ble_count_min, ble_count_max, network_type_dist_json, observation_count, confidence, created_at, updated_at, day_profile_json, night_profile_json) " +
                    "VALUES (1, 40.0, -74.0, 100.0, 'Home', '[]', '[]', 0, 5, '{}', 12, 'HIGH', $now, $now, NULL, NULL)"
            )
            // Representative cell (travel data)
            execSQL(
                "INSERT INTO cells (id, cid, lac_tac, mcc, mnc, signal_strength, network_type, latitude, longitude, first_seen, last_seen, times_seen, earfcn, pci) " +
                    "VALUES (1, 12345, 678, 310, 260, -85, 'LTE', 40.0, -74.0, $now, $now, 3, 100, 55)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, Migrations.MIGRATION_8_9)

        // Old rows survive
        db.query("SELECT summary FROM alerts WHERE id = 1").use {
            assertTrue("alert row should survive migration", it.moveToFirst())
            assertEquals("Suspected IMSI catcher", it.getString(0))
        }
        db.query("SELECT label FROM baselines WHERE id = 1").use {
            assertTrue("baseline row should survive migration", it.moveToFirst())
            assertEquals("Home", it.getString(0))
        }
        db.query("SELECT cid FROM cells WHERE id = 1").use {
            assertTrue("cell row should survive migration", it.moveToFirst())
            assertEquals(12345, it.getInt(0))
        }

        // New table exists and is usable with the correct schema
        db.execSQL(
            "INSERT INTO estimated_tower_positions " +
                "(tower_id, mcc, mnc, lac, cid, estimated_lat, estimated_lon, accuracy_meters, reading_count, last_updated, techniques, confidence_score) " +
                "VALUES ('310_260_678_12345', 310, 260, 678, 12345, 40.0, -74.0, 120.0, 5, $now, 'trilateration', 0.7)"
        )
        db.query("SELECT COUNT(*) FROM estimated_tower_positions").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrateAll_1To9() {
        helper.createDatabase(dbName, 1).close()
        // Validate the full registered chain applies cleanly end to end.
        helper.runMigrationsAndValidate(dbName, 9, true, *Migrations.ALL).close()
    }
}
