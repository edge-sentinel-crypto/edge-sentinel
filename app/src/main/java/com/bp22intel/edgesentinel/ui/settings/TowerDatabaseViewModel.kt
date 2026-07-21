/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.detection.tower.TowerDatabaseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.inject.Inject

data class CountryGroup(
    val name: String,
    val mccs: List<Int>,
    val flag: String, // 2-letter country code for display
    val isInstalled: Boolean = false,
    val towerCount: Int = 0
)

@HiltViewModel
class TowerDatabaseViewModel @Inject constructor(
    private val towerDatabaseManager: TowerDatabaseManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _countries = MutableStateFlow<List<TowerDatabaseManager.CountryDatabaseInfo>>(emptyList())
    val countries: StateFlow<List<TowerDatabaseManager.CountryDatabaseInfo>> = _countries.asStateFlow()

    private val _totalTowerCount = MutableStateFlow(0)
    val totalTowerCount: StateFlow<Int> = _totalTowerCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _importSuccess = MutableStateFlow<String?>(null)
    val importSuccess: StateFlow<String?> = _importSuccess.asStateFlow()

    private val _downloadingCountry = MutableStateFlow<String?>(null)
    val downloadingCountry: StateFlow<String?> = _downloadingCountry.asStateFlow()

    private val _downloadProgress = MutableStateFlow("")
    val downloadProgress: StateFlow<String> = _downloadProgress.asStateFlow()

    val importProgress = towerDatabaseManager.importProgress

    // Countries available for one-tap install, grouped by country (multi-MCC)
    companion object {
        val AVAILABLE_COUNTRIES = listOf(
            CountryGroup("United States", listOf(310, 311), "US"),
            CountryGroup("United Kingdom", listOf(234, 235), "GB"),
            CountryGroup("Canada", listOf(302), "CA"),
            CountryGroup("Germany", listOf(262), "DE"),
            CountryGroup("France", listOf(208), "FR"),
            CountryGroup("Japan", listOf(440), "JP"),
            CountryGroup("Australia", listOf(505), "AU"),
            CountryGroup("South Korea", listOf(450), "KR"),
            CountryGroup("Israel", listOf(425), "IL"),
            CountryGroup("Turkey", listOf(286), "TR"),
            CountryGroup("UAE", listOf(424), "AE"),
            CountryGroup("Saudi Arabia", listOf(420), "SA"),
        )

        private const val KAGGLE_DATASET_URL = "https://www.kaggle.com/datasets/aaborochin/cell-towers-from-opencellid"
    }

    init {
        loadCountryList()
        loadTotalTowerCount()
    }

    private fun loadCountryList() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _countries.value = towerDatabaseManager.getInstalledCountries()
            } catch (e: Exception) {
                android.util.Log.e("TowerDBVM", "Failed to load countries", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadTotalTowerCount() {
        viewModelScope.launch {
            try {
                _totalTowerCount.value = towerDatabaseManager.getTotalTowerCount()
            } catch (e: Exception) {
                android.util.Log.e("TowerDBVM", "Failed to load tower count", e)
            }
        }
    }

    /**
     * One-tap install: downloads tower data for a country and imports it.
     * Tries OpenCelliD download for each MCC in the group.
     * Falls back to a helpful message pointing to Kaggle if download fails.
     */
    fun installCountry(mccs: List<Int>, countryName: String) {
        if (_downloadingCountry.value != null) return // Already downloading

        viewModelScope.launch {
            try {
                _downloadingCountry.value = countryName
                _error.value = null
                _downloadProgress.value = "Downloading $countryName towers (trying Mozilla Location Services first, no key needed)..."

                var anySuccess = false
                for (mcc in mccs) {
                    val inputStream = downloadTowerData(mcc)
                    if (inputStream != null) {
                        _downloadProgress.value = "Importing MCC $mcc..."
                        towerDatabaseManager.importFromCsv(inputStream, mcc)
                        anySuccess = true
                    }
                }

                if (anySuccess) {
                    loadCountryList()
                    loadTotalTowerCount()
                    _importSuccess.value = "$countryName towers installed"
                } else {
                    _error.value = "Download failed — Mozilla Location Services and OpenCelliD both returned errors. Check your internet connection, or use 'Import from CSV' below with data from Kaggle.\n\nDataset: $KAGGLE_DATASET_URL"
                }
            } catch (e: Exception) {
                _error.value = "Install failed: ${e.message}\n\nTry downloading from Kaggle instead: $KAGGLE_DATASET_URL"
                android.util.Log.e("TowerDBVM", "Country install failed", e)
            } finally {
                _downloadingCountry.value = null
                _downloadProgress.value = ""
            }
        }
    }

    private val _apiToken = MutableStateFlow<String?>(null)
    val apiToken: StateFlow<String?> = _apiToken.asStateFlow()

    fun setApiToken(token: String) {
        _apiToken.value = token.trim()
        // Persist token
        context.getSharedPreferences("edge_sentinel", Context.MODE_PRIVATE)
            .edit().putString("opencellid_token", token.trim()).apply()
    }

    private fun loadApiToken(): String? {
        return context.getSharedPreferences("edge_sentinel", Context.MODE_PRIVATE)
            .getString("opencellid_token", null)
    }

    /**
     * Downloads tower data for a given MCC. Tries multiple sources in order:
     * 1. Mozilla Location Services (MLS) — free, no key needed, full global dataset
     * 2. OpenCelliD with user token (if configured)
     *
     * MLS downloads the full global file then filters by MCC during import.
     * OpenCelliD downloads per-MCC files directly.
     */
    private suspend fun downloadTowerData(mcc: Int): InputStream? = withContext(Dispatchers.IO) {
        // Try 1: Mozilla Location Services (no API key needed)
        val mlsStream = downloadFromMLS()
        if (mlsStream != null) {
            android.util.Log.d("TowerDBVM", "Using Mozilla Location Services (no key required)")
            return@withContext mlsStream
        }

        // Try 2: OpenCelliD with user-provided token
        val token = _apiToken.value ?: loadApiToken()
        if (!token.isNullOrBlank()) {
            val ociStream = downloadFromOpenCelliD(mcc, token)
            if (ociStream != null) {
                android.util.Log.d("TowerDBVM", "Using OpenCelliD with user token")
                return@withContext ociStream
            }
        }

        android.util.Log.w("TowerDBVM", "All download sources failed for MCC $mcc")
        null
    }

    /**
     * Mozilla Location Services — free bulk download, no API key.
     * Downloads full global dataset (gzipped CSV), filtered by MCC during import.
     * Format: radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal
     */
    private suspend fun downloadFromMLS(): InputStream? = withContext(Dispatchers.IO) {
        try {
            // MLS daily export — use today's date
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val mlsUrl = "https://d2koia3g127518.cloudfront.net/export/MLS-full-cell-export-${dateStr}T000000.csv.gz"

            android.util.Log.d("TowerDBVM", "Trying MLS: $mlsUrl")
            val connection = URL(mlsUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000 // 5 min — full global file is large
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "EdgeSentinel/2.0")
            connection.instanceFollowRedirects = true

            if (connection.responseCode == 200 && connection.contentLength > 1000) {
                GZIPInputStream(BufferedInputStream(connection.inputStream, 65536))
            } else {
                // Try yesterday's date as fallback (MLS export may lag)
                connection.disconnect()
                val yesterdayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
                val fallbackUrl = "https://d2koia3g127518.cloudfront.net/export/MLS-full-cell-export-${yesterdayStr}T000000.csv.gz"

                android.util.Log.d("TowerDBVM", "Trying MLS fallback: $fallbackUrl")
                val conn2 = URL(fallbackUrl).openConnection() as HttpURLConnection
                conn2.connectTimeout = 30_000
                conn2.readTimeout = 300_000
                conn2.requestMethod = "GET"
                conn2.setRequestProperty("User-Agent", "EdgeSentinel/2.0")
                conn2.instanceFollowRedirects = true

                if (conn2.responseCode == 200 && conn2.contentLength > 1000) {
                    GZIPInputStream(BufferedInputStream(conn2.inputStream, 65536))
                } else {
                    conn2.disconnect()
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TowerDBVM", "MLS download failed: ${e.message}")
            null
        }
    }

    /**
     * OpenCelliD — requires user-provided API token.
     * Downloads per-MCC gzipped CSV.
     */
    private suspend fun downloadFromOpenCelliD(mcc: Int, token: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://opencellid.org/ocid/downloads?token=$token&type=mcc&file=${mcc}.csv.gz")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "EdgeSentinel/2.0")

            if (connection.responseCode == 200) {
                val contentType = connection.contentType ?: ""
                val contentLength = connection.contentLength

                if (contentLength in 1..499 || contentType.contains("json", ignoreCase = true)) {
                    val errorBody = try {
                        connection.inputStream.bufferedReader().readText()
                    } catch (_: Exception) { "unknown error" }
                    android.util.Log.e("TowerDBVM", "OpenCelliD error: $errorBody")
                    connection.disconnect()
                    null
                } else {
                    GZIPInputStream(BufferedInputStream(connection.inputStream))
                }
            } else {
                android.util.Log.w("TowerDBVM", "OpenCelliD returned ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("TowerDBVM", "OpenCelliD download failed: ${e.message}")
            null
        }
    }

    fun importFromCsv(inputStream: InputStream, filterMcc: Int? = null) {
        viewModelScope.launch {
            try {
                _error.value = null
                _isLoading.value = true
                towerDatabaseManager.importFromCsv(inputStream, filterMcc)
                loadCountryList()
                loadTotalTowerCount()
                _importSuccess.value = "Import complete"
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
                android.util.Log.e("TowerDBVM", "CSV import failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setError(msg: String) { _error.value = msg }
    fun clearError() { _error.value = null }
    fun clearSuccess() { _importSuccess.value = null }

    fun deleteCountry(mccs: List<Int>) {
        viewModelScope.launch {
            try {
                mccs.forEach { towerDatabaseManager.deleteCountry(it) }
                loadCountryList()
                loadTotalTowerCount()
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun refreshData() {
        loadCountryList()
        loadTotalTowerCount()
    }
}
