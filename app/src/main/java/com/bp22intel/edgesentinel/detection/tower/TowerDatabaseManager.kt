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
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    companion object {
        // OpenCelliD download URL pattern
        // Users need to register for a free API token at opencellid.org
        // Or use the community mirror at https://opencellid.org/downloads.php
        private const val OPENCELLID_BASE = "https://opencellid.org/ocid/downloads"
        
        // MCC to country name mapping for UI
        val MCC_COUNTRIES = mapOf(
            310 to "United States", 311 to "United States", 312 to "United States",
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
        return MCC_COUNTRIES.map { (mcc, name) ->
            CountryDatabaseInfo(
                mcc = mcc,
                countryName = name,
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
        withContext(Dispatchers.IO) {
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

    /**
     * Auto-import bundled tower data from APK assets on first launch.
     * The asset is a gzipped CSV pre-filtered to high-confidence US towers (≥10 samples).
     * Only runs once — checks if towers already exist before importing.
     */
    suspend fun autoImportBundledData() {
        withContext(Dispatchers.IO) {
            // Skip if towers are already loaded
            if (knownTowerDao.getTowerCount() > 0) {
                android.util.Log.d("TowerDB", "Towers already loaded, skipping auto-import")
                return@withContext
            }

            try {
                android.util.Log.d("TowerDB", "Auto-importing bundled US tower database...")
                val assetStream = context.assets.open("us_towers.csv.gz")
                val gzipStream = java.util.zip.GZIPInputStream(java.io.BufferedInputStream(assetStream))

                // The bundled CSV has slim format: radio,mcc,mnc,lac,cid,lon,lat,range,samples
                importFromCsv(gzipStream, null)
                android.util.Log.d("TowerDB", "Auto-import complete: ${knownTowerDao.getTowerCount()} towers")
            } catch (e: Exception) {
                android.util.Log.e("TowerDB", "Auto-import from assets failed", e)
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
        knownTowerDao.deleteTowersByCountry(mcc)
    }
}