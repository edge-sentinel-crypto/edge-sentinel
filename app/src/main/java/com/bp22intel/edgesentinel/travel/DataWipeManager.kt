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
) {
    companion object {
        /** success is true only when [errors] is empty. */
        fun of(filesDeleted: Int, errors: List<String>): WipeResult =
            WipeResult(success = errors.isEmpty(), filesDeleted = filesDeleted, errors = errors.toList())
    }
}

/**
 * Overwrites and deletes files. Every failed delete is recorded in [errors];
 * nothing is swallowed. [deleter] is a seam for tests.
 */
class SecureDeleter(
    val errors: MutableList<String> = mutableListOf(),
    private val passes: Int = 3,
    private val deleter: (File) -> Boolean = { it.delete() }
) {
    /** Deletes [targets] (files or directories) and returns a result. */
    fun wipe(targets: List<File>): WipeResult {
        var count = 0
        for (t in targets) {
            if (!t.exists()) continue
            if (t.isDirectory) count += deleteDirectory(t) else if (deleteFile(t)) count++
        }
        return WipeResult.of(count, errors)
    }

    fun deleteDirectory(dir: File): Int {
        var count = 0
        val children = dir.listFiles()
        if (children == null) {
            if (dir.exists()) errors.add("Cannot list directory ${dir.name}")
        } else {
            children.forEach { file ->
                if (file.isDirectory) count += deleteDirectory(file)
                else if (deleteFile(file)) count++
            }
        }
        if (!deleter(dir) && dir.exists()) errors.add("Failed to delete directory ${dir.name}")
        return count
    }

    fun deleteFile(file: File): Boolean {
        if (!file.exists()) return false
        if (!file.isFile) {
            errors.add("Not a regular file, not deleted: ${file.name}")
            return false
        }
        try {
            val length = file.length()
            if (length > 0) {
                val random = SecureRandom()
                val buffer = ByteArray(minOf(length, 8192L).toInt())
                repeat(passes) {
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
        } catch (e: Exception) {
            errors.add("Overwrite failed for ${file.name}: ${e.message ?: e.javaClass.simpleName}")
        }
        var threw = false
        val deleted = try { deleter(file) } catch (e: Exception) {
            threw = true
            errors.add("Delete threw for ${file.name}: ${e.message ?: e.javaClass.simpleName}"); false
        }
        if (!deleted || file.exists()) {
            if (!threw) errors.add("Failed to delete file ${file.name}")
            return false
        }
        return true
    }
}

class DataWipeManager(private val context: Context) {

    companion object {
        private const val OVERWRITE_PASSES = 3

        /**
         * Round 4 (D3): travel-period rows to delete. Matches the confirm dialog
         * ("baselines, alert history, and scan data collected during this trip").
         * Cells use last_seen, so pre-trip cells re-seen on the trip are removed too.
         */
        val TRAVEL_WIPE_SQL: List<String> = listOf(
            "DELETE FROM alerts WHERE timestamp >= ?",
            "DELETE FROM scans WHERE timestamp >= ?",
            "DELETE FROM cells WHERE last_seen >= ?",
            "DELETE FROM estimated_tower_positions WHERE last_updated >= ?",
            "DELETE FROM baselines WHERE updated_at >= ?",
            "DELETE FROM ble_devices WHERE last_seen >= ?",
            "DELETE FROM wifi_bssid_sightings WHERE last_seen >= ?"
        )

        /** Runs every [TRAVEL_WIPE_SQL] statement; a failure is recorded and the rest still run. */
        fun runTravelWipe(
            entryTimestamp: Long,
            errors: MutableList<String>,
            exec: (String, Array<Any>) -> Unit
        ): Int {
            if (entryTimestamp <= 0) return 0
            var ok = 0
            for (sql in TRAVEL_WIPE_SQL) {
                try {
                    exec(sql, arrayOf(entryTimestamp)); ok++
                } catch (e: Exception) {
                    errors.add("Travel wipe failed ($sql): ${e.message ?: e.javaClass.simpleName}")
                }
            }
            return ok
        }
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
        val deleter = SecureDeleter(errors, OVERWRITE_PASSES)
        var filesDeleted = 0

        try {
            // 1. Clear DataStores through their live instances (before file delete)
            clearDataStores(scope, dataStores, errors)

            // 2. Clear database tables
            filesDeleted += clearDatabaseTables(scope, entryTimestamp, errors)

            // Securely delete files in internal storage
            val travelDir = File(context.filesDir, "travel")
            if (travelDir.exists()) {
                filesDeleted += deleter.deleteDirectory(travelDir)
            }

            if (scope == WipeScope.ALL_DATA) {
                // Clear explicit SharedPreferences known to hold sensitive credentials
                listOf("edge_sentinel", "travel_checklist", "trusted_mitm_services", "network_integrity_history").forEach { name ->
                    try {
                        val ok = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                        if (!ok) errors.add("Failed to clear SharedPreferences $name (commit returned false)")
                    } catch (e: Exception) {
                        errors.add("Failed to clear SharedPreferences $name: ${e.message ?: e.javaClass.simpleName}")
                    }
                }

                // Delete all app databases and their directory (-wal, -shm files)
                val dbNames = (context.databaseList().toList() + listOf("edge_sentinel.db", "edge_sentinel_database")).distinct()
                for (dbName in dbNames) {
                    try {
                        val existed = context.getDatabasePath(dbName).exists()
                        val deleted = context.deleteDatabase(dbName)
                        if (deleted) filesDeleted++
                        else if (existed || context.getDatabasePath(dbName).exists()) {
                            errors.add("Failed to delete database $dbName (deleteDatabase returned false)")
                        }
                    } catch (e: Exception) {
                        errors.add("Failed to delete database $dbName: ${e.message}")
                    }
                }
                val dbDir = context.getDatabasePath("edge_sentinel.db").parentFile
                if (dbDir != null && dbDir.exists()) {
                    filesDeleted += deleter.deleteDirectory(dbDir)
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
                    filesDeleted += deleter.deleteDirectory(prefsDir)
                }

                // Clear DataStore directory
                val dataStoreDir = File(context.filesDir, "datastore")
                if (dataStoreDir.exists()) {
                    filesDeleted += deleter.deleteDirectory(dataStoreDir)
                }

                // Clear all files in filesDir (offline towers, exports, calibration, etc.)
                context.filesDir.listFiles()?.forEach { file ->
                    if (file.isDirectory) filesDeleted += deleter.deleteDirectory(file)
                    else if (deleter.deleteFile(file)) filesDeleted++
                }

                // Clear cache
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.isDirectory) filesDeleted += deleter.deleteDirectory(file)
                    else if (deleter.deleteFile(file)) filesDeleted++
                }

                // Clear external cache if available
                context.externalCacheDir?.listFiles()?.forEach { file ->
                    if (file.isDirectory) filesDeleted += deleter.deleteDirectory(file)
                    else if (deleter.deleteFile(file)) filesDeleted++
                }
            }
        } catch (e: Exception) {
            errors.add("Wipe error: ${e.message}")
        }

        // success is true only when no error was recorded.
        return WipeResult.of(filesDeleted, errors)
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
                        // Round 4 (D3): every table the confirm dialog promises.
                        cleared += runTravelWipe(entryTimestamp, errors) { sql, args -> it.execSQL(sql, args) }
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
}
