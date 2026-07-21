/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Radar
import androidx.compose.ui.graphics.vector.ImageVector

enum class SensorCategory(val label: String, val icon: ImageVector) {
    CELLULAR("Cellular", Icons.Filled.CellTower),
    WIFI("WiFi", Icons.Filled.Wifi),
    BLUETOOTH("Bluetooth", Icons.Filled.Bluetooth),
    NETWORK("Network", Icons.Filled.Language),
    BASELINE("Baseline", Icons.Filled.Radar)
}
