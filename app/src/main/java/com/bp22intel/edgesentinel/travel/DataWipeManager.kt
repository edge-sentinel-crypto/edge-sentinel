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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    /**
     * Clears travel-period rows. [entryTimestamp] is the travel-mode entry time;
     * when it is not positive, no database rows are deleted.
     */
    suspend fun wipeTravelData(entryTimestamp: Long): WipeResult {
        return performWipe(WipeScope.TRAVEL_DATA, entryTimestamp = entryTimestamp)
    }

    suspend fun exportThenWipe(passphrase: String, entryTimestamp: Long): Pair<Uri?, WipeResult> {
        val exporter = TravelExporter(context)
        val exportUri = exporter.exportEncrypted(passphrase)
        val wipeResult = performWipe(WipeScope.TRAVEL_DATA, entryTimestamp = entryTimestamp)
        return Pair(exportUri, wipeResult)
    }

    /**
     * Wipes all app data. [dataStores] are the live (injected) DataStore
     * instances; they are cleared through their own API BEFORE any file is
     * deleted, so the in-memory singletons cannot rewrite old values later.
     * The caller must close Room first and restart the process afterwards.
     */
    suspend fun panicWipe(dataStores: List<DataStore<Preferences>> = emptyList()): WipeResult {
        return performWipe(WipeScope.ALL_DATA, dataStores = dataStores)
    }

    private suspend fun performWipe(
        scope: WipeScope,
        entryTimestamp: Long = 0L,
        dataStores: List<DataStore<Preferences>> = emptyList()
    ): WipeResult {
        val errors = mutableListOf<String>()
        var filesDeleted = 0

        try {
            // 1. Clear DataStores through their live instances (before file delete)
            clearDataStores(scope, dataStores, errors)

            // 2. Clear database tables
            filesDeleted += clearDatabaseTables(scope, entryTimestamp, errors)

            // Securely delete files in internal storage
            val travelDir = File(context.filesDir, "travel")
            if (travelDir.exists()) {
                filesDeleted += secureDeleteDirectory(travelDir)
            }

            if (scope == WipeScope.ALL_DATA) {
                // Clear explicit SharedPreferences known to hold sensitive credentials
                listOf("edge_sentinel", "travel_checklist", "trusted_mitm_services", "network_integrity_history").forEach { name ->
                    try {
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                    } catch (_: Exception) {}
                }

                // Delete all app databases and their directory (-wal, -shm files)
                val dbNames = (context.databaseList().toList() + listOf("edge_sentinel.db", "edge_sentinel_database")).distinct()
                for (dbName in dbNames) {
                    try {
                        context.deleteDatabase(dbName)
                        filesDeleted++
                    } catch (e: Exception) {
                        errors.add("Failed to delete database $dbName: ${e.message}")
                    }
                }
                val dbDir = context.getDatabasePath("edge_sentinel.db").parentFile
                if (dbDir != null && dbDir.exists()) {
                    filesDeleted += secureDeleteDirectory(dbDir)
                }

                // N3: confirm that no database file survived (main, -wal, -shm, -journal).
                val mainDb = context.getDatabasePath("edge_sentinel.db")
                val leftovers = listOf("", "-wal", "-shm", "-journal")
                    .map { File(mainDb.path + it) }
                    .filter { it.exists() }
                if (leftovers.isNotEmpty()) {
                    errors.add("Database files still exist after wipe: " + leftovers.joinToString { it.name })
                }

                // Clear all shared preferences files
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                if (prefsDir.exists()) {
                    filesDeleted += secureDeleteDirectory(prefsDir)
                }

                // Clear DataStore directory
                val dataStoreDir = File(context.filesDir, "datastore")
                if (dataStoreDir.exists()) {
                    filesDeleted += secureDeleteDirectory(dataStoreDir)
                }

                // Clear all files in filesDir (offline towers, exports, calibration, etc.)
                context.filesDir.listFiles()?.forEach { file ->
                    if (file.isDirectory) filesDeleted += secureDeleteDirectory(file)
                    else if (secureDeleteFile(file)) filesDeleted++
                }

                // Clear cache
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.isDirectory) filesDeleted += secureDeleteDirectory(file)
                    else if (secureDeleteFile(file)) filesDeleted++
                }

                // Clear external cache if available
                context.externalCacheDir?.listFiles()?.forEach { file ->
                    if (file.isDirectory) filesDeleted += secureDeleteDirectory(file)
                    else if (secureDeleteFile(file)) filesDeleted++
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

    private suspend fun clearDataStores(
        scope: WipeScope,
        dataStores: List<DataStore<Preferences>>,
        errors: MutableList<String>
    ) {
        try {
            context.travelWipeDataStore.edit { it.clear() }
        } catch (e: Exception) {
            errors.add("Failed to clear travel_wipe DataStore: ${e.message}")
        }
        if (scope == WipeScope.ALL_DATA) {
            for (store in dataStores) {
                try {
                    store.edit { it.clear() }
                } catch (e: Exception) {
                    errors.add("Failed to clear DataStore: ${e.message}")
                }
            }
            // Files under filesDir/datastore are deleted later in performWipe,
            // after every live instance has been cleared.
        }
    }

    private fun clearDatabaseTables(
        scope: WipeScope,
        entryTimestamp: Long,
        errors: MutableList<String>
    ): Int {
        var cleared = 0
        try {
            val candidates = listOf(
                context.getDatabasePath("edge_sentinel.db"),
                context.getDatabasePath("edge_sentinel_database")
            )
            val dbFile = candidates.firstOrNull { it.exists() }
            if (dbFile != null && dbFile.exists()) {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                )
                db.use {
                    if (scope == WipeScope.TRAVEL_DATA) {
                        // Only clear travel-period data. Real table names are
                        // `alerts`, `scans`, `cells` (there is no travel_sessions
                        // table; the entry time comes from the travel_mode DataStore).
                        if (entryTimestamp > 0) {
                            val args = arrayOf<Any>(entryTimestamp)
                            it.execSQL("DELETE FROM alerts WHERE timestamp >= ?", args)
                            it.execSQL("DELETE FROM scans WHERE timestamp >= ?", args)
                            it.execSQL("DELETE FROM cells WHERE first_seen >= ?", args)
                            cleared += 3
                        }
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
        } catch (e: Exception) {
            // NF4: a failed table wipe is a failed wipe. Report it; never swallow it.
            errors.add("Failed to clear database tables: ${e.message ?: e.javaClass.simpleName}")
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
