/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.bluetooth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.data.local.entity.BleDeviceEntity
import com.bp22intel.edgesentinel.detection.bluetooth.BleAlertManager
import com.bp22intel.edgesentinel.detection.bluetooth.BleDeviceTracker
import com.bp22intel.edgesentinel.detection.bluetooth.BleTrackingDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val detector: BleTrackingDetector,
    private val deviceTracker: BleDeviceTracker,
    private val alertManager: BleAlertManager
) : ViewModel() {

    val isScanning: StateFlow<Boolean> = detector.isScanning

    val nearbyDeviceCount: StateFlow<Int> = detector.nearbyDeviceCount

    val statusMessage: StateFlow<String?> = detector.statusMessage

    val alerts: StateFlow<List<BleAlertManager.BleAlert>> = alertManager.activeAlerts

    val allDevices: StateFlow<List<BleDeviceEntity>> = deviceTracker.getAllDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trackers: StateFlow<List<BleDeviceEntity>> = deviceTracker.getTrackers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recentDevices = MutableStateFlow<List<BleDeviceEntity>>(emptyList())
    val recentDevices: StateFlow<List<BleDeviceEntity>> = _recentDevices.asStateFlow()

    init {
        viewModelScope.launch {
            val twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
            deviceTracker.getRecentDevices(twoHoursAgo).collect { devices ->
                _recentDevices.value = devices
            }
        }
    }

    fun startScanning() {
        detector.startScanning()
    }

    fun stopScanning() {
        detector.stopScanning()
    }

    fun toggleScanning() {
        if (isScanning.value) stopScanning() else startScanning()
    }

    fun evaluateAlerts() {
        viewModelScope.launch {
            alertManager.evaluateDevices(allDevices.value)
        }
    }
}
