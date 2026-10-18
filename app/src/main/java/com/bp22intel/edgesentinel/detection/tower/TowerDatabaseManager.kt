/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.tower

import android.content.Context
import android.content.SharedPreferences
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TowerDatabaseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knownTowerDao: KnownTowerDao
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    constructor(
        context: Context,
        knownTowerDao: KnownTowerDao,
        ioDispatcher: CoroutineDispatcher
    ) : this(context, knownTowerDao) {
        this.ioDispatcher = ioDispatcher
    }
    companion object {
        const val BUNDLED_TOWER_DATA_VERSION = 3
        const val KEY_BUNDLED_TOWER_DATA_VERSION = "bundled_tower_data_version"
        const val PREFS_NAME = "tower_database_prefs"
        const val BUNDLED_DATA_DATE = "2026-10-17"

        /**
         * Mobile Country Codes for the United States (MCC 310 to 316) bundled in us_towers.csv.gz.
         */
        val US_MCCS = (310..316).toList()

        /**
         * Mobile Country Codes for Canada (MCC 302) bundled in ca_towers.csv.gz.
         */
        val CA_MCCS = listOf(302)

        /**
         * All MCCs owned by the bundled base data.
         */
        val BUNDLED_MCCS = US_MCCS + CA_MCCS

        fun isBaseMcc(mcc: Int): Boolean = mcc in BUNDLED_MCCS

        fun isBaseCountry(iso2: String): Boolean =
            iso2.equals("US", ignoreCase = true) || iso2.equals("CA", ignoreCase = true)

        // MCC to country name mapping for UI
        val MCC_COUNTRIES = mapOf(
            310 to "United States", 311 to "United States", 312 to "United States",
            313 to "United States", 314 to "United States", 315 to "United States",
            316 to "United States",
            302 to "Canada",
            234 to "United Kingdom", 235 to "United Kingdom",
            262 to "Germany",
            208 to "France",
            222 to "Italy",
            440 to "Japan", 441 to "Japan",
            450 to "South Korea",
            460 to "China",
            520 to "Thailand",
            510 to "Indonesia",
            515 to "Philippines",
            425 to "Israel",
            420 to "Saudi Arabia",
            424 to "United Arab Emirates",
            286 to "Turkey",
            602 to "Egypt",
            250 to "Russia",
            257 to "Belarus",
            432 to "Iran",
            467 to "North Korea",
            734 to "Venezuela",
            414 to "Myanmar"
        )

        // Countries from travel mode profiles (high priority)
        val PRIORITY_MCCS = listOf(460, 250, 432, 424, 420, 467, 286, 602, 425, 257, 734, 414)
    }

    private val autoImportMutex = Mutex()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    data class ImportProgress(
        val mcc: Int,
        val countryName: String,
        val totalRows: Int,
        val importedRows: Int,
        val status: ImportStatus
    )

    enum class ImportStatus { DOWNLOADING, PARSING, IMPORTING, COMPLETE, ERROR }

    data class CountryDatabaseInfo(
        val mcc: Int,
        val countryName: String,
        val towerCount: Int,
        val isInstalled: Boolean
    )

    suspend fun getInstalledCountries(): List<CountryDatabaseInfo> {
        val installed = knownTowerDao.getInstalledCountries().toSet()
        val allMccs = (MCC_COUNTRIES.keys + installed).distinct()
        return allMccs.map { mcc ->
            CountryDatabaseInfo(
                mcc = mcc,
                countryName = MCC_COUNTRIES[mcc] ?: "MCC $mcc",
                towerCount = if (installed.contains(mcc)) knownTowerDao.getTowerCountByCountry(mcc) else 0,
                isInstalled = installed.contains(mcc)
            )
        }.sortedBy { it.countryName }
    }

    suspend fun getTotalTowerCount(): Int = knownTowerDao.getTowerCount()

    /**
     * Import towers from a CSV file.
     *
     * Supports two formats:
     * - Full OpenCelliD (14 cols): radio,mcc,mnc,lac,cid,unit,lon,lat,range,samples,...
     * - Slim bundled (9 cols):     radio,mcc,mnc,lac,cid,lon,lat,range,samples
     *
     * Auto-detects format by checking if column 5 parses as a double (lon in slim)
     * or an int (unit in full format).
     */
    suspend fun importFromCsv(inputStream: InputStream, filterMcc: Int? = null) {
        withContext(ioDispatcher) {
            val reader = BufferedReader(InputStreamReader(inputStream))
            val batch = mutableListOf<KnownTowerEntity>()
            var lineCount = 0
            var importedCount = 0
            var isSlimFormat: Boolean? = null // Auto-detect on first data row

            reader.useLines { lines ->
                var firstLine = true
                var separator = ','

                lines.forEach { line ->
                    // Detect separator and skip header on first line
                    if (firstLine) {
                        firstLine = false
                        separator = if (line.count { it == '\t' } > line.count { it == ',' }) '\t' else ','
                        val firstField = line.split(separator)[0].trim().lowercase()
                        if (firstField == "radio" || firstField == "\"radio\"") {
                            return@forEach // skip header row
                        }
                        // Not a header — fall through to parse as data
                    }

                    lineCount++
                    try {
                        val parts = line.split(separator)
                        if (parts.size < 9) return@forEach

                        // Auto-detect format on first row
                        if (isSlimFormat == null) {
                            // In slim format, parts[5] is longitude (a double like -73.858)
                            // In full format, parts[5] is unit (an int or empty)
                            val col5 = parts[5]
                            isSlimFormat = col5.contains(".") && col5.toDoubleOrNull() != null
                        }

                        val radio = parts[0]
                        val mcc = parts[1].toIntOrNull() ?: return@forEach
                        val mnc = parts[2].toIntOrNull() ?: return@forEach
                        val lac = parts[3].toIntOrNull() ?: return@forEach
                        val cid = parts[4].toIntOrNull() ?: return@forEach

                        val lon: Double
                        val lat: Double
                        val range: Int
                        val samples: Int

                        if (isSlimFormat == true) {
                            // Slim: radio,mcc,mnc,lac,cid,lon,lat,range,samples
                            lon = parts[5].toDoubleOrNull() ?: return@forEach
                            lat = parts[6].toDoubleOrNull() ?: return@forEach
                            range = parts[7].toIntOrNull() ?: 1000
                            samples = parts[8].toIntOrNull() ?: 0
                        } else {
                            // Full: radio,mcc,mnc,lac,cid,unit,lon,lat,range,samples,...
                            lon = parts[6].toDoubleOrNull() ?: return@forEach
                            lat = parts[7].toDoubleOrNull() ?: return@forEach
                            range = parts[8].toIntOrNull() ?: 1000
                            samples = parts.getOrNull(9)?.toIntOrNull() ?: 0
                        }

                        if (filterMcc != null && mcc != filterMcc) return@forEach

                        batch.add(KnownTowerEntity(
                            mcc = mcc, mnc = mnc, lac = lac, cid = cid,
                            latitude = lat, longitude = lon,
                            range = range, radio = radio,
                            samples = samples
                        ))

                        if (batch.size >= 5000) {
                            knownTowerDao.insertTowers(batch.toList())
                            importedCount += batch.size
                            batch.clear()
                            _importProgress.value = _importProgress.value?.copy(
                                importedRows = importedCount
                            )
                        }
                    } catch (_: Exception) { /* skip malformed lines */ }
                }
            }

            // Insert remaining
            if (batch.isNotEmpty()) {
                knownTowerDao.insertTowers(batch.toList())
                importedCount += batch.size
            }

            _importProgress.value = _importProgress.value?.copy(
                importedRows = importedCount,
                status = ImportStatus.COMPLETE
            )
        }
    }

    internal var assetOpener: ((String) -> InputStream)? = null

    internal fun getPrefs(): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_BUNDLED_TOWER_DATA_VERSION)) return prefs
        val fallback = context.getSharedPreferences("edge_sentinel", Context.MODE_PRIVATE)
        if (fallback.contains(KEY_BUNDLED_TOWER_DATA_VERSION)) return fallback
        return prefs
    }

    fun getInstalledDate(iso2: String): String? {
        val prefs = getPrefs()
        val key = "installed_date_${iso2.uppercase()}"
        if (prefs.contains(key)) {
            return prefs.getString(key, null)
        }
        if (isBaseCountry(iso2)) {
            return BUNDLED_DATA_DATE
        }
        return null
    }

    fun setInstalledDate(iso2: String, dataDate: String) {
        getPrefs().edit().putString("installed_date_${iso2.uppercase()}", dataDate).apply()
    }

    fun removeInstalledDate(iso2: String) {
        getPrefs().edit().remove("installed_date_${iso2.uppercase()}").apply()
    }

    private fun openBundledAsset(fileName: String = "us_towers.csv.gz"): InputStream {
        return assetOpener?.invoke(fileName) ?: context.assets.open(fileName)
    }

    /**
     * Returns the currently stored bundled tower database version, or 0 if none is stored.
     */
    fun getStoredBundledVersion(): Int {
        val prefs = getPrefs()
        return prefs.getInt(KEY_BUNDLED_TOWER_DATA_VERSION, 0)
    }

    /**
     * Returns the effective bundled tower database version:
     * - Stored version in SharedPreferences if present.
     * - Version 1 if towers are loaded but no version was recorded (legacy install).
     * - Version 0 if no towers are loaded and no version was recorded (fresh install).
     */
    suspend fun getEffectiveBundledVersion(): Int {
        val prefs = getPrefs()
        return if (prefs.contains(KEY_BUNDLED_TOWER_DATA_VERSION)) {
            prefs.getInt(KEY_BUNDLED_TOWER_DATA_VERSION, 0)
        } else if (knownTowerDao.getTowerCount() > 0) {
            1
        } else {
            0
        }
    }

    private fun logD(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (_: RuntimeException) {
            // Android stub in unit tests
        }
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) android.util.Log.e(tag, msg, tr) else android.util.Log.e(tag, msg)
        } catch (_: RuntimeException) {
            // Android stub in unit tests
        }
    }

    /**
     * Auto-import bundled tower data from APK assets on first launch or update.
     * The bundle owns MCCs 310 to 316 (US) and 302 (CA).
     * If an update is available (bundled version > stored version), previous bundle-owned
     * towers are removed and replaced with the updated bundle.
     * Non-base towers imported for other countries are preserved.
     * Guarded with a Mutex to ensure single-flight execution.
     */
    suspend fun autoImportBundledData() {
        autoImportMutex.withLock {
            withContext(ioDispatcher) {
                val prefs = getPrefs()
                val totalTowers = knownTowerDao.getTowerCount()
                val storedVersion = if (prefs.contains(KEY_BUNDLED_TOWER_DATA_VERSION)) {
                    prefs.getInt(KEY_BUNDLED_TOWER_DATA_VERSION, 0)
                } else if (totalTowers > 0) {
                    // An existing install with towers loaded but no stored version counts as version 1
                    1
                } else {
                    0
                }

                if (storedVersion >= BUNDLED_TOWER_DATA_VERSION) {
                    logD(
                        "TowerDB",
                        "Bundled tower database is up to date (version $storedVersion), skipping auto-import"
                    )
                    return@withContext
                }

                try {
                    logD(
                        "TowerDB",
                        "Auto-importing bundled tower database (current=$storedVersion, target=$BUNDLED_TOWER_DATA_VERSION)..."
                    )
                    // Delete only bundle-owned rows (US 310 to 316 and CA 302).
                    // Do not delete towers the user imported for other countries.
                    knownTowerDao.deleteTowersByCountries(BUNDLED_MCCS)

                    // Import US towers
                    val usStream = openBundledAsset("us_towers.csv.gz")
                    val usGzipStream = java.util.zip.GZIPInputStream(java.io.BufferedInputStream(usStream))
                    importFromCsv(usGzipStream, null)

                    // Import Canada towers
                    val caStream = openBundledAsset("ca_towers.csv.gz")
                    val caGzipStream = java.util.zip.GZIPInputStream(java.io.BufferedInputStream(caStream))
                    importFromCsv(caGzipStream, null)

                    // Crash-safe: write the new version and dates only after both imports complete successfully
                    prefs.edit()
                        .putInt(KEY_BUNDLED_TOWER_DATA_VERSION, BUNDLED_TOWER_DATA_VERSION)
                        .putString("installed_date_US", BUNDLED_DATA_DATE)
                        .putString("installed_date_CA", BUNDLED_DATA_DATE)
                        .apply()

                    logD(
                        "TowerDB",
                        "Auto-import complete: ${knownTowerDao.getTowerCount()} towers (version $BUNDLED_TOWER_DATA_VERSION)"
                    )
                } catch (e: Exception) {
                    logE("TowerDB", "Auto-import from assets failed", e)
                    throw e
                }
            }
        }
    }

    /**
     * Look up a tower and return its trust score.
     * Returns null if tower is not in the database.
     * Trust score range: 0.0 (low confidence) to 1.0 (high confidence).
     *
     * IMPORTANT: A known tower with high trust REDUCES false positive risk
     * but should NEVER suppress alerts when behavioral evidence (cipher anomaly,
     * downgrade, signal anomaly) is present. The trust score is a soft signal.
     */
    suspend fun lookupTrust(mcc: Int, mnc: Int, lac: Int, cid: Int): Float? {
        val tower = knownTowerDao.findTower(mcc, mnc, lac, cid) ?: return null
        return tower.trustScore
    }

    /**
     * Look up a tower's full record from the bundled OpenCelliD database.
     * Returns null if the tower is not found. Use this to obtain geographic
     * coordinates (latitude / longitude) for display on the tower location map.
     */
    suspend fun lookupTower(mcc: Int, mnc: Int, lac: Int, cid: Int): KnownTowerEntity? {
        return knownTowerDao.findTower(mcc, mnc, lac, cid)
    }

    suspend fun deleteCountry(mcc: Int) {
        if (isBaseMcc(mcc)) return // Base countries are not removable
        knownTowerDao.deleteTowersByCountry(mcc)
    }

    suspend fun deleteCountries(mccs: List<Int>) {
        val nonBase = mccs.filterNot { isBaseMcc(it) }
        if (nonBase.isNotEmpty()) {
            knownTowerDao.deleteTowersByCountries(nonBase)
        }
    }
}
