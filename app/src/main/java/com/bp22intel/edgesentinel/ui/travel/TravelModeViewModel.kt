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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.travel.TravelAdvisor
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

    fun exportData(@Suppress("UNUSED_PARAMETER") passphrase: String) {
        viewModelScope.launch {
            try {
                val state = travelState.value
                val alerts = database.alertDao().getActiveSince(0L)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

                val report = buildString {
                    appendLine("═══════════════════════════════════════════")
                    appendLine("  EDGE SENTINEL — TRAVEL SECURITY REPORT")
                    appendLine("═══════════════════════════════════════════")
                    appendLine()
                    appendLine("Generated: ${dateFormat.format(Date())}")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
                    appendLine()

                    if (state.currentCountryName != null) {
                        appendLine("── Travel Session ──")
                        appendLine("Country: ${state.currentCountryName} (${state.currentCountryCode})")
                        if (state.entryTimestamp > 0) {
                            appendLine("Entry: ${dateFormat.format(Date(state.entryTimestamp))}")
                        }
                        state.threatProfile?.let { profile ->
                            appendLine("Risk Level: ${profile.riskLevel}/5")
                            appendLine("Primary Threats:")
                            profile.primaryThreats.forEach { appendLine("  • $it") }
                        }
                        appendLine()
                    }

                    appendLine("── Alerts (${alerts.size}) ──")
                    if (alerts.isEmpty()) {
                        appendLine("  No alerts recorded.")
                    } else {
                        alerts.forEach { alert ->
                            appendLine("  [${dateFormat.format(Date(alert.timestamp))}] ${alert.severity}: ${alert.threatType}")
                            appendLine("    ${alert.summary}")
                        }
                    }
                    appendLine()

                    appendLine("── Checklist Status ──")
                    val checked = _checkedItems.value
                    TravelAdvisor.getPreDepartureChecklist().forEach { item ->
                        val mark = if (item.id in checked) "✓" else "✗"
                        appendLine("  [$mark] ${item.text}")
                    }
                    TravelAdvisor.getPostDepartureChecklist().forEach { item ->
                        val mark = if (item.id in checked) "✓" else "✗"
                        appendLine("  [$mark] ${item.text}")
                    }
                    appendLine()
                    appendLine("═══════════════════════════════════════════")
                    appendLine("  END OF REPORT")
                    appendLine("═══════════════════════════════════════════")
                }

                val exportDir = File(context.cacheDir, "travel_exports")
                exportDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val exportFile = File(exportDir, "edge_sentinel_travel_$timestamp.txt")
                exportFile.writeText(report)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Edge Sentinel Travel Report — $timestamp")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Travel Report").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                // Export failure handled silently
            }
        }
    }

    fun wipeTravelData() {
        viewModelScope.launch {
            val entryTimestamp = travelState.value.entryTimestamp
            if (entryTimestamp > 0) {
                val now = System.currentTimeMillis()
                // Clear ONLY travel-period data: alerts and estimated tower
                // positions observed during the trip. Learned baselines and other
                // long-lived detection learning are deliberately preserved so that
                // "Clear Travel Data" does not reset the user's home environment model.
                database.alertDao().deleteBetween(entryTimestamp, now)
                database.estimatedTowerPositionDao().deleteBetween(entryTimestamp, now)
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
            // Nuclear option — erase ALL app data via Room's clearAllTables
            withContext(Dispatchers.IO) {
                database.clearAllTables()
            }

            // Clear all preferences
            dataStore.edit { it.clear() }
            checklistPrefs.edit().clear().apply()
            context.getSharedPreferences("trusted_mitm_services", Context.MODE_PRIVATE)
                .edit().clear().apply()
            context.getSharedPreferences("network_integrity_history", Context.MODE_PRIVATE)
                .edit().clear().apply()

            // Clear travel mode state
            travelMode.confirmExit()

            // Clear checklist
            _checkedItems.value = emptySet()

            // Clear cached files
            withContext(Dispatchers.IO) {
                context.cacheDir.deleteRecursively()
            }
        }
    }
}
