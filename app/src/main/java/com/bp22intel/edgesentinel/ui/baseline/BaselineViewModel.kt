/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.baseline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.baseline.BaselineAnomalyResult
import com.bp22intel.edgesentinel.baseline.BaselineConfidence
import com.bp22intel.edgesentinel.baseline.BaselineManager
import com.bp22intel.edgesentinel.baseline.LocationBaseline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BaselineViewModel @Inject constructor(
    private val baselineManager: BaselineManager
) : ViewModel() {

    val baselines: StateFlow<List<LocationBaseline>> = baselineManager.getAllBaselines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentAnomaly = MutableStateFlow<BaselineAnomalyResult?>(null)
    val currentAnomaly: StateFlow<BaselineAnomalyResult?> = _currentAnomaly.asStateFlow()

    private val _selectedBaseline = MutableStateFlow<LocationBaseline?>(null)
    val selectedBaseline: StateFlow<LocationBaseline?> = _selectedBaseline.asStateFlow()

    fun selectBaseline(baseline: LocationBaseline) {
        _selectedBaseline.value = baseline
    }

    fun clearSelection() {
        _selectedBaseline.value = null
    }

    fun resetBaseline(baselineId: Long) {
        viewModelScope.launch {
            baselineManager.resetBaseline(baselineId)
            _selectedBaseline.value = null
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            baselineManager.resetAll()
        }
    }
}
