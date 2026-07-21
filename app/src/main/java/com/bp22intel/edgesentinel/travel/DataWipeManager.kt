/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.travel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.security.SecureRandom

private val Context.travelWipeDataStore by preferencesDataStore(name = "travel_wipe")

enum class WipeScope {
    TRAVEL_DATA,    // Baselines, alert history, and scan data from the trip
    ALL_DATA        // All Edge Sentinel data (panic wipe)
}

data class WipeResult(
    val success: Boolean,
    val filesDeleted: Int,
    val errors: List<String> = emptyList()
)

class DataWipeManager(private val context: Context) {

    companion object {
        private const val OVERWRITE_PASSES = 3
    }

    suspend fun wipeTravelData(): WipeResult {
        return performWipe(WipeScope.TRAVEL_DATA)
    }

    suspend fun exportThenWipe(passphrase: String): Pair<Uri?, WipeResult> {
        val exporter = TravelExporter(context)
        val exportUri = exporter.exportEncrypted(passphrase)
        val wipeResult = performWipe(WipeScope.TRAVEL_DATA)
        return Pair(exportUri, wipeResult)
    }

    suspend fun panicWipe(): WipeResult {
        return performWipe(WipeScope.ALL_DATA)
    }

    private suspend fun performWipe(scope: WipeScope): WipeResult {
        val errors = mutableListOf<String>()
        var filesDeleted = 0

        try {
            // Clear DataStore preferences
            clearDataStores(scope)

            // Clear database tables
            filesDeleted += clearDatabaseTables(scope)

            // Securely delete files in internal storage
            val travelDir = File(context.filesDir, "travel")
            if (travelDir.exists()) {
                filesDeleted += secureDeleteDirectory(travelDir)
            }

            if (scope == WipeScope.ALL_DATA) {
                // Delete all app databases
                for (dbName in context.databaseList()) {
                    try {
                        context.deleteDatabase(dbName)
                        filesDeleted++
                    } catch (e: Exception) {
                        errors.add("Failed to delete database $dbName: ${e.message}")
                    }
                }

                // Clear all shared preferences
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                if (prefsDir.exists()) {
                    filesDeleted += secureDeleteDirectory(prefsDir)
                }

                // Clear DataStore directory
                val dataStoreDir = File(context.filesDir, "datastore")
                if (dataStoreDir.exists()) {
                    filesDeleted += secureDeleteDirectory(dataStoreDir)
                }

                // Clear cache
                context.cacheDir.listFiles()?.forEach { file ->
                    if (secureDeleteFile(file)) filesDeleted++
                }

                // Clear external cache if available
                context.externalCacheDir?.listFiles()?.forEach { file ->
                    if (secureDeleteFile(file)) filesDeleted++
                }
            }
        } catch (e: Exception) {
            errors.add("Wipe error: ${e.message}")
        }

        return WipeResult(
            success = errors.isEmpty(),
            filesDeleted = filesDeleted,
            errors = errors
        )
    }

    private suspend fun clearDataStores(scope: WipeScope) {
        context.travelWipeDataStore.edit { it.clear() }
        if (scope == WipeScope.ALL_DATA) {
            // Clear all known DataStore instances
            val dataStoreDir = File(context.filesDir, "datastore")
            if (dataStoreDir.exists()) {
                dataStoreDir.listFiles()?.forEach { file ->
                    secureDeleteFile(file)
                }
            }
        }
    }

    private fun clearDatabaseTables(scope: WipeScope): Int {
        var cleared = 0
        try {
            val dbFile = context.getDatabasePath("edge_sentinel_database")
            if (dbFile.exists()) {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                )
                db.use {
                    if (scope == WipeScope.TRAVEL_DATA) {
                        // Only clear travel-period data
                        it.execSQL("DELETE FROM alerts WHERE timestamp > (SELECT COALESCE(MIN(entry_timestamp), 0) FROM travel_sessions)")
                        it.execSQL("DELETE FROM scans WHERE timestamp > (SELECT COALESCE(MIN(entry_timestamp), 0) FROM travel_sessions)")
                        it.execSQL("DELETE FROM cell_towers WHERE last_seen > (SELECT COALESCE(MIN(entry_timestamp), 0) FROM travel_sessions)")
                        cleared += 3
                    } else {
                        // Clear everything
                        val cursor = it.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name != 'room_master_table'",
                            null
                        )
                        cursor.use { c ->
                            while (c.moveToNext()) {
                                val tableName = c.getString(0)
                                it.execSQL("DELETE FROM $tableName")
                                cleared++
                            }
                        }
                    }
                    it.execSQL("VACUUM")
                }
            }
        } catch (_: Exception) {
            // Database may not exist yet or have different schema
        }
        return cleared
    }

    private fun secureDeleteDirectory(dir: File): Int {
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                count += secureDeleteDirectory(file)
            } else {
                if (secureDeleteFile(file)) count++
            }
        }
        dir.delete()
        return count
    }

    private fun secureDeleteFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            // Overwrite with random data multiple passes before deletion
            val length = file.length()
            if (length > 0) {
                val random = SecureRandom()
                val buffer = ByteArray(minOf(length, 8192L).toInt())
                repeat(OVERWRITE_PASSES) {
                    file.outputStream().use { out ->
                        var remaining = length
                        while (remaining > 0) {
                            val toWrite = minOf(remaining, buffer.size.toLong()).toInt()
                            random.nextBytes(buffer)
                            out.write(buffer, 0, toWrite)
                            remaining -= toWrite
                        }
                        out.fd.sync()
                    }
                }
            }
            file.delete()
        } catch (_: Exception) {
            // Fallback to simple delete
            file.delete()
        }
    }
}
