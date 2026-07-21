/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.cellinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.data.sensor.CellInfoCollector
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CellInfoViewModel @Inject constructor(
    private val cellRepository: CellRepository,
    private val cellInfoCollector: CellInfoCollector
) : ViewModel() {

    private val _currentCell = MutableStateFlow<CellTower?>(null)
    val currentCell: StateFlow<CellTower?> = _currentCell.asStateFlow()

    val allKnownCells: StateFlow<List<CellTower>> = cellRepository.getAllCells()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _networkTypeHistory = MutableStateFlow<List<Pair<Long, NetworkType>>>(emptyList())
    val networkTypeHistory: StateFlow<List<Pair<Long, NetworkType>>> =
        _networkTypeHistory.asStateFlow()

    init {
        refreshCellInfo()

        viewModelScope.launch {
            cellRepository.getAllCells().collect { cells ->
                // Build network type history from known cells' first/last seen timestamps
                val history = cells
                    .sortedByDescending { it.lastSeen }
                    .map { it.lastSeen to it.networkType }
                    .take(20)
                _networkTypeHistory.value = history

                // Update current cell from repository if not already set
                if (_currentCell.value == null && cells.isNotEmpty()) {
                    _currentCell.value = cells.maxByOrNull { it.lastSeen }
                }
            }
        }
    }

    fun refreshCellInfo() {
        viewModelScope.launch {
            try {
                val cells = cellInfoCollector.getCurrentCellInfo()
                if (cells.isNotEmpty()) {
                    _currentCell.value = cells.first()
                    for (cell in cells) {
                        cellRepository.insertOrUpdateCell(cell)
                    }
                }
            } catch (_: Exception) {
                // Permission may not be granted yet
            }
        }
    }
}
