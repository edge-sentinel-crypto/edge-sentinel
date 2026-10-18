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
import com.bp22intel.edgesentinel.data.WipeInProgress
import com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.service.MonitoringService
import com.bp22intel.edgesentinel.service.ScanWorker
import com.bp22intel.edgesentinel.travel.DataWipeManager
import com.bp22intel.edgesentinel.travel.DefaultProcessRestarter
import com.bp22intel.edgesentinel.travel.LeftoverDatabaseCheck
import com.bp22intel.edgesentinel.travel.PanicWipeSequence
import com.bp22intel.edgesentinel.travel.ProcessRestarter
import com.bp22intel.edgesentinel.travel.WipeResult
import com.bp22intel.edgesentinel.travel.TravelAdvisor
import com.bp22intel.edgesentinel.travel.TravelExporter
import com.bp22intel.edgesentinel.travel.TravelMode
import com.bp22intel.edgesentinel.travel.TravelModeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class TravelModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val database: EdgeSentinelDatabase,
    private val applicationScope: CoroutineScope,
    private val processRestarter: ProcessRestarter = DefaultProcessRestarter(context)
) : ViewModel() {

    companion object {
        private const val PREFS_NAME = "travel_checklist"
        private const val KEY_CHECKED_ITEMS = "checked_items"
        private val KEY_DETECTION_SENSITIVITY = stringPreferencesKey("detection_sensitivity")
        private val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        private const val STOP_TIMEOUT_MS = 5_000L
        internal var wipeErrorDisplayMs = 4_000L
        private val wipeActive = AtomicBoolean(false)

        internal fun resetWipeActiveForTests() {
            wipeActive.set(false)
        }
    }

    private val travelMode = TravelMode(context)
    private val checklistPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val travelState: StateFlow<TravelModeState> = travelMode.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TravelModeState())

    private val _checkedItems = MutableStateFlow<Set<String>>(emptySet())
    val checkedItems: StateFlow<Set<String>> = _checkedItems.asStateFlow()

    private val _isWiping = MutableStateFlow(false)
    val isWiping: StateFlow<Boolean> = _isWiping.asStateFlow()

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

    /** Called when the user dismisses the wipe-error dialog. */
    fun acknowledgeWipeError() {
        _wipeError.value = null
        // Defect 3: after a panic wipe the process restarts on its own; the
        // dismiss action only restarts sooner. It never touches the database.
        if (WipeInProgress.isActive) {
            WipeInProgress.restoreHandlerBeforeRestart()
            processRestarter.restartProcess()
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
        if (WipeInProgress.isActive || !wipeActive.compareAndSet(false, true)) {
            try {
                android.util.Log.w("TravelModeViewModel", "Panic wipe already in progress, ignoring duplicate call")
            } catch (_: Throwable) {}
            return
        }
        _isWiping.value = true
        applicationScope.launch {
            // Defect 1/3: block every repository read/write first, inside the application-scoped job.
            WipeInProgress.begin()
            try {
                // N4: disable monitoring auto-start BEFORE anything else.
                MonitoringService.markPanicWipeSuppression(context)
                val preErrors = mutableListOf<String>()
                try { dataStore.edit { it[KEY_MONITORING_ENABLED] = false } } catch (e: Exception) {
                    preErrors.add("Failed to disable monitoring setting: ${e.message ?: e.javaClass.simpleName}")
                }
                try { travelMode.confirmExit() } catch (e: Exception) {
                    preErrors.add("Failed to clear travel mode: ${e.message ?: e.javaClass.simpleName}")
                }
                _checkedItems.value = emptySet()

                // Defect 1: stop results are checked. A timeout or exception is
                // recorded; the gate stays set; Room is closed only when no
                // writer is active (PanicWipeSequence).
                val sequence = PanicWipeSequence(
                    stopService = { MonitoringService.stopAndAwait(context, STOP_TIMEOUT_MS) },
                    cancelWorker = { ScanWorker.cancelAndAwait(context, STOP_TIMEOUT_MS) },
                    writersActive = {
                        MonitoringService.isRunning.value || WipeInProgress.activeOperations > 0
                    },
                    closeDatabase = { database.close() },
                    wipe = { DataWipeManager(context).panicWipe(dataStores = listOf(dataStore)) },
                    writerDrainTimeoutMs = STOP_TIMEOUT_MS
                )
                val wipe = sequence.run()
                // The settings store was cleared; keep auto-start disabled.
                MonitoringService.markPanicWipeSuppression(context)
                val result = WipeResult.of(wipe.filesDeleted, preErrors + wipe.errors)

                if (!result.success) {
                    // Defect 3: show the error, then restart anyway. WipeInProgress
                    // blocks all repository access until the process ends.
                    _wipeError.value = "Panic wipe did not complete. Some data may remain. " +
                        "The app will restart now.\n" + result.errors.joinToString("\n")
                    delay(wipeErrorDisplayMs)
                }
                // Round 4: final leftover check right before the restart. If any
                // writer re-created the DB file, delete it again and record it.
                val leftover = LeftoverDatabaseCheck.run(
                    LeftoverDatabaseCheck.DB_FILE_NAMES.map { context.getDatabasePath(it) }
                )
                if (leftover.isNotEmpty()) {
                    try {
                        android.util.Log.e("PanicWipe", leftover.joinToString("; "))
                    } catch (_: Throwable) {}
                    context.getSharedPreferences("panic_wipe_status", Context.MODE_PRIVATE).edit()
                        .putString("last_leftover_errors", leftover.joinToString("\n")).commit()
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        WipeInProgress.restoreHandlerBeforeRestart()
                        processRestarter.restartProcess()
                        WipeInProgress.abort()
                        _isWiping.value = false
                        wipeActive.set(false)
                    } catch (t: Throwable) {
                        WipeInProgress.abort()
                        _isWiping.value = false
                        wipeActive.set(false)
                        throw t
                    }
                }
            }
        }
    }
}
