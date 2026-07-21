/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    val isOnboardingComplete: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_ONBOARDING_COMPLETE] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun completeOnboarding() {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[KEY_ONBOARDING_COMPLETE] = true }
        }
    }
}
