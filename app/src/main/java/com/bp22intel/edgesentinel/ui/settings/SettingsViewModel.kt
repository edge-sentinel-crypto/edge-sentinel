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
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        private val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        private val KEY_DETECTION_SENSITIVITY = stringPreferencesKey("detection_sensitivity")
        private val KEY_NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")
        private val KEY_NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration")
        private val KEY_DEMO_MODE = booleanPreferencesKey("demo_mode")
        private val KEY_ADVANCED_MODE = booleanPreferencesKey("advanced_mode")
        private val KEY_ALERT_RETENTION_DAYS = intPreferencesKey("alert_retention_days")
        private val KEY_COOPERATIVE_LOCALIZATION = booleanPreferencesKey("cooperative_localization_enabled")
    }

    val isMonitoringEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_MONITORING_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val detectionSensitivity: StateFlow<DetectionSensitivity> = dataStore.data
        .map { prefs ->
            val name = prefs[KEY_DETECTION_SENSITIVITY] ?: DetectionSensitivity.MEDIUM.name
            try {
                DetectionSensitivity.valueOf(name)
            } catch (_: Exception) {
                DetectionSensitivity.MEDIUM
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetectionSensitivity.MEDIUM)

    val notificationSound: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_NOTIFICATION_SOUND] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val notificationVibration: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_NOTIFICATION_VIBRATION] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isDemoMode: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_DEMO_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAdvancedMode: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_ADVANCED_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val alertRetentionDays: StateFlow<Int> = dataStore.data
        .map { prefs -> prefs[KEY_ALERT_RETENTION_DAYS] ?: 7 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)

    val isCooperativeLocalization: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_COOPERATIVE_LOCALIZATION] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _isRooted = MutableStateFlow(false)
    val isRooted: StateFlow<Boolean> = _isRooted.asStateFlow()

    init {
        _isRooted.value = checkRootAccess()
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_MONITORING_ENABLED] = enabled }
        }
    }

    fun setDetectionSensitivity(sensitivity: DetectionSensitivity) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_DETECTION_SENSITIVITY] = sensitivity.name }
        }
    }

    fun setNotificationSound(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_NOTIFICATION_SOUND] = enabled }
        }
    }

    fun setNotificationVibration(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_NOTIFICATION_VIBRATION] = enabled }
        }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_DEMO_MODE] = enabled }
        }
    }

    fun setAdvancedMode(enabled: Boolean) {
        if (!_isRooted.value) return
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_ADVANCED_MODE] = enabled }
        }
    }

    fun setAlertRetentionDays(days: Int) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_ALERT_RETENTION_DAYS] = days }
        }
    }

    fun setCooperativeLocalization(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_COOPERATIVE_LOCALIZATION] = enabled }
        }
    }

    fun exportLogs(context: Context) {
        viewModelScope.launch {
            try {
                val logDir = File(context.cacheDir, "logs")
                logDir.mkdirs()
                val logFile = File(logDir, "edge_sentinel_log_${System.currentTimeMillis()}.txt")
                logFile.writeText(
                    "Edge Sentinel Log Export\n" +
                        "Timestamp: ${System.currentTimeMillis()}\n" +
                        "App Version: 1.0.0\n" +
                        "Device: ${android.os.Build.MODEL}\n" +
                        "Android: ${android.os.Build.VERSION.RELEASE}\n"
                )

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    logFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Logs"))
            } catch (_: Exception) {
                // Handle export failure silently
            }
        }
    }

    fun checkRootAccess(): Boolean {
        return try {
            val paths = arrayOf(
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su"
            )
            paths.any { File(it).exists() }
        } catch (_: Exception) {
            false
        }
    }
}
