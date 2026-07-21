/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.repository

import com.bp22intel.edgesentinel.domain.model.ScanResult
import kotlinx.coroutines.flow.Flow

interface ScanRepository {
    suspend fun insertScan(scan: ScanResult): Long
    fun getRecentScans(limit: Int): Flow<List<ScanResult>>
}
