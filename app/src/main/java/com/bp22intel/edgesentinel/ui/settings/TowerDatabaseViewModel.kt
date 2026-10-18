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
import com.bp22intel.edgesentinel.detection.tower.CountryManifestEntry
import com.bp22intel.edgesentinel.detection.tower.DefaultMirrorClient
import com.bp22intel.edgesentinel.detection.tower.DefaultOpenCelliDApi
import com.bp22intel.edgesentinel.detection.tower.DownloadResult
import com.bp22intel.edgesentinel.detection.tower.MirrorClient
import com.bp22intel.edgesentinel.detection.tower.MirrorDownloadResult
import com.bp22intel.edgesentinel.detection.tower.MirrorManifest
import com.bp22intel.edgesentinel.detection.tower.OpenCelliDApi
import com.bp22intel.edgesentinel.detection.tower.TowerDatabaseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.inject.Inject

enum class CountryInstallStatus {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE
}

data class CountryItemUiState(
    val iso2: String,
    val name: String,
    val mccs: List<Int>,
    val towerCount: Int,
    val bytes: Long,
    val manifestDataDate: String,
    val installedDataDate: String?,
    val isBase: Boolean,
    val isInstalled: Boolean,
    val status: CountryInstallStatus,
    val isSelected: Boolean = false
) {
    val statusDisplay: String
        get() = when {
            isBase -> if (installedDataDate != null) "Built in, data from $installedDataDate" else "Built in"
            status == CountryInstallStatus.NOT_INSTALLED -> "Not installed"
            status == CountryInstallStatus.UPDATE_AVAILABLE -> "Update available"
            else -> if (installedDataDate != null) "Installed, data from $installedDataDate" else "Installed"
        }
}

@HiltViewModel
class TowerDatabaseViewModel @Inject constructor(
    val towerDatabaseManager: TowerDatabaseManager,
    @ApplicationContext private val context: Context,
    var mirrorClient: MirrorClient = DefaultMirrorClient(),
    var openCelliDApi: OpenCelliDApi = DefaultOpenCelliDApi()
) : ViewModel() {

    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    constructor(
        towerDatabaseManager: TowerDatabaseManager,
        context: Context,
        mirrorClient: MirrorClient,
        openCelliDApi: OpenCelliDApi,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(towerDatabaseManager, context, mirrorClient, openCelliDApi) {
        this.ioDispatcher = ioDispatcher
    }

    private val _rawCountries = MutableStateFlow<List<CountryItemUiState>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedIso2s = MutableStateFlow<Set<String>>(emptySet())
    val selectedIso2s: StateFlow<Set<String>> = _selectedIso2s.asStateFlow()

    val countries: StateFlow<List<CountryItemUiState>> = combine(
        _rawCountries,
        _searchQuery,
        _selectedIso2s
    ) { raw, query, selected ->
        val trimmed = query.trim()
        val mapped = raw.map { item ->
            item.copy(isSelected = selected.contains(item.iso2))
        }
        if (trimmed.isEmpty()) {
            mapped
        } else {
            mapped.filter { item ->
                item.name.contains(trimmed, ignoreCase = true) ||
                        item.iso2.contains(trimmed, ignoreCase = true) ||
                        item.mccs.any { it.toString().contains(trimmed) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _totalTowerCount = MutableStateFlow(0)
    val totalTowerCount: StateFlow<Int> = _totalTowerCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _importSuccess = MutableStateFlow<String?>(null)
    val importSuccess: StateFlow<String?> = _importSuccess.asStateFlow()

    private val _downloadingCountry = MutableStateFlow<String?>(null)
    val downloadingCountry: StateFlow<String?> = _downloadingCountry.asStateFlow()

    private val _downloadProgress = MutableStateFlow("")
    val downloadProgress: StateFlow<String> = _downloadProgress.asStateFlow()

    private val _apiToken = MutableStateFlow<String?>(null)
    val apiToken: StateFlow<String?> = _apiToken.asStateFlow()

    private var currentManifest: MirrorManifest? = null
    private val downloadRunGuard = AtomicBoolean(false)

    companion object {
        private const val PREFS_KEY_TOKEN = "opencellid_token"
        private const val KAGGLE_DATASET_URL =
            "https://www.kaggle.com/datasets/aaborochin/cell-towers-from-opencellid"

        fun computeCountryStatus(
            isInstalled: Boolean,
            installedDate: String?,
            manifestDataDate: String?
        ): CountryInstallStatus {
            if (!isInstalled) return CountryInstallStatus.NOT_INSTALLED
            if (installedDate != null && manifestDataDate != null && manifestDataDate.isNotBlank() && manifestDataDate > installedDate) {
                return CountryInstallStatus.UPDATE_AVAILABLE
            }
            return CountryInstallStatus.INSTALLED
        }
    }

    init {
        _apiToken.value = loadApiToken()
        loadCountryList()
        loadTotalTowerCount()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSelection(iso2: String) {
        if (TowerDatabaseManager.isBaseCountry(iso2)) return // Base countries are not selectable
        val current = _selectedIso2s.value.toMutableSet()
        if (current.contains(iso2)) {
            current.remove(iso2)
        } else {
            current.add(iso2)
        }
        _selectedIso2s.value = current
    }

    fun clearSelection() {
        _selectedIso2s.value = emptySet()
    }

    fun loadCountryList(forceRefreshManifest: Boolean = false) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val installedMccs = withContext(ioDispatcher) {
                    towerDatabaseManager.getInstalledCountries().filter { it.isInstalled }.map { it.mcc }.toSet()
                }

                val manifestResult = withContext(ioDispatcher) {
                    mirrorClient.getManifest(forceRefreshManifest)
                }

                if (manifestResult.isSuccess) {
                    _isOffline.value = false
                    val manifest = manifestResult.getOrThrow()
                    currentManifest = manifest

                    val baseItems = mutableListOf<CountryItemUiState>()
                    val otherItems = mutableListOf<CountryItemUiState>()

                    for (entry in manifest.countries) {
                        val isBase = TowerDatabaseManager.isBaseCountry(entry.iso2)
                        val isInstalled = isBase || entry.mccs.any { it in installedMccs }
                        val installedDate = towerDatabaseManager.getInstalledDate(entry.iso2)
                        val status = if (isBase) {
                            CountryInstallStatus.INSTALLED
                        } else {
                            computeCountryStatus(isInstalled, installedDate, entry.dataDate)
                        }

                        val item = CountryItemUiState(
                            iso2 = entry.iso2,
                            name = entry.name,
                            mccs = entry.mccs,
                            towerCount = entry.rows,
                            bytes = entry.bytes,
                            manifestDataDate = entry.dataDate,
                            installedDataDate = installedDate,
                            isBase = isBase,
                            isInstalled = isInstalled,
                            status = status,
                            isSelected = false
                        )

                        if (isBase) {
                            baseItems.add(item)
                        } else {
                            otherItems.add(item)
                        }
                    }

                    // Sort base countries so US and Canada appear at top (e.g. US first, Canada second or alphabetical)
                    baseItems.sortBy { it.name }
                    otherItems.sortBy { it.name }

                    _rawCountries.value = baseItems + otherItems
                } else {
                    _isOffline.value = true
                    // Keep the complete manifest-backed list when a refresh fails after a
                    // successful load. This preserves real ISO codes and installed dates.
                    if (_rawCountries.value.isNotEmpty()) return@launch

                    // Fallback to installed countries when offline
                    val installedDb = withContext(ioDispatcher) {
                        towerDatabaseManager.getInstalledCountries().filter { it.isInstalled }
                    }
                    val grouped = installedDb.groupBy { c ->
                        when (c.mcc) {
                            in TowerDatabaseManager.US_MCCS -> "US"
                            in TowerDatabaseManager.CA_MCCS -> "CA"
                            else -> c.countryName
                        }
                    }
                    val items = grouped.map { (groupKey, entries) ->
                        val mccs = entries.map { it.mcc }.sorted()
                        val iso2 = when (groupKey) {
                            "US", "CA" -> groupKey
                            else -> "MCC-${mccs.joinToString("-")}"
                        }
                        val isBase = TowerDatabaseManager.isBaseCountry(iso2)
                        val installedDate = towerDatabaseManager.getInstalledDate(iso2)
                        CountryItemUiState(
                            iso2 = iso2,
                            name = entries.first().countryName,
                            mccs = mccs,
                            towerCount = entries.sumOf { it.towerCount },
                            bytes = 0L,
                            manifestDataDate = "",
                            installedDataDate = installedDate,
                            isBase = isBase,
                            isInstalled = true,
                            status = CountryInstallStatus.INSTALLED
                        )
                    }
                    _rawCountries.value = items.sortedBy { if (it.isBase) 0 else 1 }
                }
            } catch (e: Exception) {
                android.util.Log.e("TowerDBVM", "Failed to load countries", e)
                _error.value = "Failed to load country list: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadTotalTowerCount() {
        viewModelScope.launch {
            try {
                _totalTowerCount.value = withContext(ioDispatcher) {
                    towerDatabaseManager.getTotalTowerCount()
                }
            } catch (e: Exception) {
                android.util.Log.e("TowerDBVM", "Failed to load tower count", e)
            }
        }
    }

    fun downloadSelected() {
        if (_downloadingCountry.value != null) return
        val selected = _selectedIso2s.value
        if (selected.isEmpty()) return

        val manifest = currentManifest
        if (manifest == null) {
            _error.value = "Cannot download: mirror manifest not loaded"
            return
        }

        val targets = manifest.countries.filter {
            selected.contains(it.iso2) && !TowerDatabaseManager.isBaseCountry(it.iso2)
        }
        if (targets.isEmpty()) return
        if (!downloadRunGuard.compareAndSet(false, true)) return
        _downloadingCountry.value = "selected"

        viewModelScope.launch {
            try {
                runBatchDownload(targets)
                _selectedIso2s.value = emptySet()
            } catch (e: Exception) {
                _error.value = "Download failed: ${e.message}"
            } finally {
                _downloadingCountry.value = null
                _downloadProgress.value = ""
                downloadRunGuard.set(false)
            }
        }
    }

    fun updateAllInstalledCountries() {
        if (_downloadingCountry.value != null) return
        if (!downloadRunGuard.compareAndSet(false, true)) return
        _downloadingCountry.value = "all"

        viewModelScope.launch {
            try {
                _error.value = null

                val installedMccs = withContext(ioDispatcher) {
                    towerDatabaseManager.getInstalledCountries()
                        .filter { it.isInstalled }
                        .map { it.mcc }
                        .toSet()
                }

                val manifest = currentManifest ?: withContext(ioDispatcher) {
                    mirrorClient.getManifest().getOrNull()
                }
                currentManifest = manifest

                if (manifest == null) {
                    _error.value = "Cannot update: mirror manifest not loaded"
                    return@launch
                }

                // Targets: all non-base installed countries
                val targets = manifest.countries.filter { entry ->
                    !TowerDatabaseManager.isBaseCountry(entry.iso2) &&
                            entry.mccs.any { it in installedMccs }
                }

                if (targets.isEmpty()) {
                    _downloadProgress.value = ""
                    return@launch
                }

                runBatchDownload(targets)
            } catch (e: Exception) {
                _error.value = "Update all failed: ${e.message}"
                android.util.Log.e("TowerDBVM", "Update all failed", e)
            } finally {
                _downloadingCountry.value = null
                _downloadProgress.value = ""
                downloadRunGuard.set(false)
            }
        }
    }

    fun installCountry(iso2: String) {
        if (_downloadingCountry.value != null) return
        if (TowerDatabaseManager.isBaseCountry(iso2)) return

        val manifest = currentManifest
        val entry = manifest?.countries?.find { it.iso2 == iso2 }
        if (entry == null) {
            _error.value = "Country not found in manifest"
            return
        }
        if (!downloadRunGuard.compareAndSet(false, true)) return
        _downloadingCountry.value = entry.name

        viewModelScope.launch {
            try {
                _error.value = null
                _downloadProgress.value = "Downloading ${entry.name} towers..."

                val result = downloadAndImportCountry(entry)
                if (result.isSuccess) {
                    loadCountryList()
                    loadTotalTowerCount()
                    _importSuccess.value = "${entry.name} towers installed"
                } else {
                    val failMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                    _error.value = "Download failed for ${entry.name}: $failMsg\n\nDataset: $KAGGLE_DATASET_URL"
                }
            } catch (e: Exception) {
                _error.value = "Install failed: ${e.message}"
            } finally {
                _downloadingCountry.value = null
                _downloadProgress.value = ""
                downloadRunGuard.set(false)
            }
        }
    }

    fun deleteCountry(iso2: String, mccs: List<Int>) {
        if (_downloadingCountry.value != null) return
        if (TowerDatabaseManager.isBaseCountry(iso2)) return

        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    towerDatabaseManager.deleteCountries(mccs)
                    towerDatabaseManager.removeInstalledDate(iso2)
                }
                loadCountryList()
                loadTotalTowerCount()
                _importSuccess.value = "Towers removed"
            } catch (e: Exception) {
                _error.value = "Failed to remove towers: ${e.message}"
            }
        }
    }

    private suspend fun runBatchDownload(targets: List<CountryManifestEntry>) {
        val total = targets.size
        var updatedCount = 0
        val failedList = mutableListOf<String>()

        for ((index, country) in targets.withIndex()) {
            val groupNumber = index + 1
            _downloadProgress.value = "Downloading ${country.name} ($groupNumber of $total)..."

            val result = downloadAndImportCountry(country)
            if (result.isSuccess) {
                updatedCount++
            } else {
                val reason = result.exceptionOrNull()?.message ?: "Failed"
                failedList.add("${country.name}: $reason")
            }
        }

        loadCountryList()
        loadTotalTowerCount()

        val failedCount = failedList.size
        if (failedCount == 0) {
            _importSuccess.value = if (updatedCount == 1) "1 updated" else "$updatedCount updated"
        } else {
            _error.value = "$updatedCount updated, $failedCount failed: ${failedList.joinToString("; ")}"
        }
    }

    suspend fun downloadAndImportCountry(entry: CountryManifestEntry): Result<Unit> {
        // 1. Try Mirror first (primary source, no API key)
        val mirrorResult = withContext(ioDispatcher) {
            mirrorClient.downloadCountry(entry, context.cacheDir)
        }

        when (mirrorResult) {
            is MirrorDownloadResult.Success -> {
                val tempFile = mirrorResult.tempFile
                try {
                    withContext(ioDispatcher) {
                        val gzipStream = GZIPInputStream(BufferedInputStream(FileInputStream(tempFile)))
                        towerDatabaseManager.importFromCsv(gzipStream, null)
                    }
                    towerDatabaseManager.setInstalledDate(entry.iso2, entry.dataDate)
                    return Result.success(Unit)
                } catch (e: Exception) {
                    return Result.failure(Exception("Import failed: ${e.message}"))
                } finally {
                    tempFile.delete()
                }
            }
            is MirrorDownloadResult.Failure -> {
                if (mirrorResult.isSha256Mismatch) {
                    return Result.failure(Exception(mirrorResult.message))
                }

                // 2. Fall back to OpenCelliD only when a token is configured
                val token = _apiToken.value ?: loadApiToken()
                if (token.isNullOrBlank()) {
                    return Result.failure(Exception("Mirror download failed: ${mirrorResult.message}"))
                }

                var anySuccess = false
                var lastOciFailure: DownloadResult.Failure? = null

                for (mcc in entry.mccs) {
                    when (val ociResult = withContext(ioDispatcher) { openCelliDApi.download(mcc, token) }) {
                        is DownloadResult.Success -> {
                            withContext(ioDispatcher) {
                                towerDatabaseManager.importFromCsv(ociResult.stream, mcc)
                            }
                            anySuccess = true
                        }
                        is DownloadResult.Failure -> {
                            lastOciFailure = ociResult
                        }
                    }
                }

                if (anySuccess) {
                    towerDatabaseManager.setInstalledDate(entry.iso2, entry.dataDate)
                    return Result.success(Unit)
                } else {
                    val ociMsg = lastOciFailure?.message ?: "OpenCelliD fallback failed"
                    return Result.failure(Exception("Mirror failed (${mirrorResult.message}), OpenCelliD fallback failed: $ociMsg"))
                }
            }
        }
    }

    fun setApiToken(token: String) {
        _apiToken.value = token.trim()
        val prefs = context.getSharedPreferences("edge_sentinel", Context.MODE_PRIVATE)
        prefs.edit().putString(PREFS_KEY_TOKEN, token.trim()).apply()
    }

    private fun loadApiToken(): String? {
        val prefs = context.getSharedPreferences("edge_sentinel", Context.MODE_PRIVATE)
        return prefs.getString(PREFS_KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun importFromCsv(inputStream: InputStream, filterMcc: Int? = null) {
        viewModelScope.launch {
            try {
                _error.value = null
                _isLoading.value = true
                withContext(ioDispatcher) {
                    towerDatabaseManager.importFromCsv(inputStream, filterMcc)
                }
                loadCountryList()
                loadTotalTowerCount()
                _importSuccess.value = "Import complete"
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearSuccess() { _importSuccess.value = null }
    fun setError(msg: String) { _error.value = msg }
}
