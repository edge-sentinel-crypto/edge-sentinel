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
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.travelDataStore by preferencesDataStore(name = "travel_mode")

enum class TravelState {
    INACTIVE,
    LEARNING,
    ACTIVE,
    EXITING
}

data class TravelModeState(
    val state: TravelState = TravelState.INACTIVE,
    val currentCountryCode: String? = null,
    val currentCountryName: String? = null,
    val entryTimestamp: Long = 0L,
    val sensitivityMultiplier: Float = 1.0f,
    val threatProfile: CountryThreatProfile? = null
)

class TravelMode(private val context: Context) {

    companion object {
        private val KEY_STATE = stringPreferencesKey("travel_state")
        private val KEY_COUNTRY_CODE = stringPreferencesKey("country_code")
        private val KEY_ENTRY_TIMESTAMP = longPreferencesKey("entry_timestamp")
        private val KEY_MANUAL_ACTIVE = booleanPreferencesKey("manual_active")
        private val KEY_SENSITIVITY_OVERRIDE = intPreferencesKey("sensitivity_override")

        private const val LEARNING_PERIOD_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val _state = MutableStateFlow(TravelModeState())
    val state: StateFlow<TravelModeState> = _state.asStateFlow()

    val isActive: Flow<Boolean> = _state.map { it.state != TravelState.INACTIVE }

    suspend fun initialize() {
        val prefs = context.travelDataStore.data.first()
        val savedState = prefs[KEY_STATE]?.let {
            try { TravelState.valueOf(it) } catch (_: Exception) { TravelState.INACTIVE }
        } ?: TravelState.INACTIVE
        val countryCode = prefs[KEY_COUNTRY_CODE]
        val entryTimestamp = prefs[KEY_ENTRY_TIMESTAMP] ?: 0L

        if (savedState != TravelState.INACTIVE && countryCode != null) {
            val profile = CountryThreatProfiles.getProfile(countryCode)
            val currentState = resolveState(savedState, entryTimestamp)
            _state.value = TravelModeState(
                state = currentState,
                currentCountryCode = countryCode,
                currentCountryName = profile?.countryName ?: countryCode,
                entryTimestamp = entryTimestamp,
                sensitivityMultiplier = calculateSensitivity(currentState, entryTimestamp),
                threatProfile = profile
            )
        }
    }

    suspend fun activate(countryCode: String? = null) {
        val detectedCountry = countryCode ?: detectCurrentCountry()
        val profile = detectedCountry?.let { CountryThreatProfiles.getProfile(it) }
        val now = System.currentTimeMillis()

        val newState = TravelModeState(
            state = TravelState.LEARNING,
            currentCountryCode = detectedCountry,
            currentCountryName = profile?.countryName ?: detectedCountry,
            entryTimestamp = now,
            sensitivityMultiplier = 1.5f, // Heightened during learning
            threatProfile = profile
        )
        _state.value = newState
        persistState(newState)
    }

    suspend fun deactivate() {
        _state.value = _state.value.copy(state = TravelState.EXITING)
        persistState(_state.value)
    }

    suspend fun confirmExit() {
        _state.value = TravelModeState()
        context.travelDataStore.edit { it.clear() }
    }

    suspend fun onSimChanged(mcc: String) {
        val countryCode = CountryThreatProfiles.mccToCountryCode(mcc)
        val currentCountry = _state.value.currentCountryCode

        if (countryCode != null && countryCode != currentCountry) {
            activate(countryCode)
        }
    }

    suspend fun updateState() {
        val current = _state.value
        if (current.state == TravelState.INACTIVE) return

        val resolved = resolveState(current.state, current.entryTimestamp)
        if (resolved != current.state) {
            val updated = current.copy(
                state = resolved,
                sensitivityMultiplier = calculateSensitivity(resolved, current.entryTimestamp)
            )
            _state.value = updated
            persistState(updated)
        }
    }

    fun setSensitivityOverride(level: Int) {
        val current = _state.value
        _state.value = current.copy(sensitivityMultiplier = level.toFloat() / 3f + 0.5f)
    }

    private fun detectCurrentCountry(): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.networkCountryIso?.uppercase()
    }

    private fun resolveState(currentState: TravelState, entryTimestamp: Long): TravelState {
        if (currentState == TravelState.LEARNING) {
            val elapsed = System.currentTimeMillis() - entryTimestamp
            if (elapsed >= LEARNING_PERIOD_MS) {
                return TravelState.ACTIVE
            }
        }
        return currentState
    }

    private fun calculateSensitivity(state: TravelState, entryTimestamp: Long): Float {
        return when (state) {
            TravelState.INACTIVE -> 1.0f
            TravelState.LEARNING -> {
                val elapsed = System.currentTimeMillis() - entryTimestamp
                val progress = (elapsed.toFloat() / LEARNING_PERIOD_MS).coerceIn(0f, 1f)
                // Start at 1.5x, gradually reduce to 1.0x as learning period completes
                1.5f - (0.5f * progress)
            }
            TravelState.ACTIVE -> 1.0f
            TravelState.EXITING -> 0.8f
        }
    }

    private suspend fun persistState(state: TravelModeState) {
        context.travelDataStore.edit { prefs ->
            prefs[KEY_STATE] = state.state.name
            state.currentCountryCode?.let { prefs[KEY_COUNTRY_CODE] = it }
            prefs[KEY_ENTRY_TIMESTAMP] = state.entryTimestamp
        }
    }
}
