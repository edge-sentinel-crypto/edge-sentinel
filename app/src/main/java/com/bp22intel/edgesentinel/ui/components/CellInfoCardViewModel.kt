/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.components

import androidx.lifecycle.ViewModel
import com.bp22intel.edgesentinel.detection.geo.TowerPositionTracker
import com.bp22intel.edgesentinel.detection.tower.TowerDatabaseManager
import com.bp22intel.edgesentinel.detection.tower.TowerVerifier
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CellInfoCardViewModel @Inject constructor(
    val towerVerifier: TowerVerifier,
    val towerDatabaseManager: TowerDatabaseManager,
    val towerPositionTracker: TowerPositionTracker
) : ViewModel()
