/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.travel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.service.MonitoringService
import com.bp22intel.edgesentinel.service.ScanWorker
import com.bp22intel.edgesentinel.travel.DataWipeManager
import com.bp22intel.edgesentinel.travel.TravelAdvisor
import com.bp22intel.edgesentinel.travel.TravelExporter
import com.bp22intel.edgesentinel.travel.TravelMode
import com.bp22intel.edgesentinel.travel.TravelModeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TravelModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val database: EdgeSentinelDatabase
) : ViewModel() {

    companion object {
        private const val PREFS_NAME = "travel_checklist"
        private const val KEY_CHECKED_ITEMS = "checked_items"
        private val KEY_DETECTION_SENSITIVITY = stringPreferencesKey("detection_sensitivity")
        private val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    private val travelMode = TravelMode(context)
    private val checklistPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val travelState: StateFlow<TravelModeState> = travelMode.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TravelModeState())

    private val _checkedItems = MutableStateFlow<Set<String>>(emptySet())
    val checkedItems: StateFlow<Set<String>> = _checkedItems.asStateFlow()

    init {
        _checkedItems.value = checklistPrefs.getStringSet(KEY_CHECKED_ITEMS, emptySet()) ?: emptySet()
        viewModelScope.launch {
            travelMode.initialize()
        }
    }

    fun toggleChecklistItem(itemId: String, checked: Boolean) {
        val updated = if (checked) {
            _checkedItems.value + itemId
        } else {
            _checkedItems.value - itemId
        }
        _checkedItems.value = updated
        checklistPrefs.edit().putStringSet(KEY_CHECKED_ITEMS, updated).apply()
    }

    fun activate() {
        viewModelScope.launch {
            travelMode.activate()
            dataStore.edit { prefs ->
                prefs[KEY_DETECTION_SENSITIVITY] = DetectionSensitivity.HIGH.name
            }
        }
    }

    fun deactivate() {
        viewModelScope.launch {
            travelMode.deactivate()
            travelMode.confirmExit()
            dataStore.edit { prefs ->
                prefs[KEY_DETECTION_SENSITIVITY] = DetectionSensitivity.MEDIUM.name
            }
        }
    }

    private val _exportError = MutableStateFlow<String?>(null)
    /** Export error shown in the UI; null when there is none. */
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun clearExportError() {
        _exportError.value = null
    }

    fun exportData(passphrase: String) {
        // F5: never write plaintext. A blank passphrase is rejected.
        if (passphrase.isBlank()) {
            _exportError.value = "Export cancelled: a passphrase is required. " +
                "Travel data is only exported encrypted."
            return
        }
        viewModelScope.launch {
            try {
                val exporter = TravelExporter(context)
                val uri = withContext(Dispatchers.IO) { exporter.exportEncrypted(passphrase) }

                if (uri == null) {
                    _exportError.value = "Export failed: the encrypted file could not be created."
                } else {
                    val shareIntent = exporter.createShareIntent(uri, encrypted = true).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Export Travel Report").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                _exportError.value = "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private val _wipeError = MutableStateFlow<String?>(null)
    /** Wipe error shown in the UI; null when there is none. */
    val wipeError: StateFlow<String?> = _wipeError.asStateFlow()

    /** Set when a panic wipe failed; the process restarts after the user acknowledges. */
    @Volatile private var restartAfterWipeErrorAck = false

    /** Called when the user dismisses the wipe-error dialog. */
    fun acknowledgeWipeError() {
        _wipeError.value = null
        if (restartAfterWipeErrorAck) {
            restartAfterWipeErrorAck = false
            restartProcess()
        }
    }

    fun wipeTravelData() {
        viewModelScope.launch {
            val entryTimestamp = travelState.value.entryTimestamp
            // NF1: DataWipeManager owns travel-data deletion (alerts, scans and
            // cells since the travel-mode entry time). The ViewModel does not
            // delete tables itself.
            val result = withContext(Dispatchers.IO) {
                DataWipeManager(context).wipeTravelData(entryTimestamp)
            }
            if (!result.success) {
                // NF4: report the failure; keep travel mode so the user can retry.
                _wipeError.value = "Travel data wipe failed. Some data may remain.\n" +
                    result.errors.joinToString("\n")
                return@launch
            }
            // Clear travel checklist
            _checkedItems.value = emptySet()
            checklistPrefs.edit().remove(KEY_CHECKED_ITEMS).apply()
            // Deactivate travel mode
            travelMode.confirmExit()
        }
    }

    fun panicWipe() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // N4: disable monitoring auto-start BEFORE anything else, so
                // neither the app nor the system restarts monitoring after the wipe.
                MonitoringService.setAutoStartDisabled(context, true)
                try { dataStore.edit { it[KEY_MONITORING_ENABLED] = false } } catch (_: Exception) {}

                // N3: stop background scanning and WAIT until it has stopped,
                // so nothing writes to Room during or after the wipe.
                try { MonitoringService.stopAndAwait(context, STOP_TIMEOUT_MS) } catch (_: Exception) {}
                try { ScanWorker.cancelAndAwait(context, STOP_TIMEOUT_MS) } catch (_: Exception) {}

                // F6: clear the live DataStore singletons BEFORE files are deleted,
                // so they cannot rewrite old values from memory afterwards.
                try { travelMode.confirmExit() } catch (_: Exception) {}  // travel_mode store
                _checkedItems.value = emptySet()

                val closeError = try {
                    database.close()
                    null
                } catch (e: Exception) {
                    "Failed to close database: ${e.message}"
                }
                val wipe = DataWipeManager(context).panicWipe(dataStores = listOf(dataStore))
                // The settings store was cleared; keep auto-start disabled.
                MonitoringService.setAutoStartDisabled(context, true)
                if (closeError != null) wipe.copy(success = false, errors = wipe.errors + closeError) else wipe
            }

            if (result.success) {
                // F6: Room is closed and its files are confirmed gone. Restart the
                // process so no code touches the closed database.
                restartProcess()
            } else {
                // NF4: show the failure. Restart only after the user acknowledges it.
                restartAfterWipeErrorAck = true
                _wipeError.value = "Panic wipe did not complete. Some data may remain.\n" +
                    result.errors.joinToString("\n")
            }
        }
    }

    private fun restartProcess() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val component = launch?.component
        if (component != null) {
            context.startActivity(Intent.makeRestartActivityTask(component))
        }
        Runtime.getRuntime().exit(0)
    }
}
