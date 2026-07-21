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

import com.bp22intel.edgesentinel.domain.model.CellTower
import kotlinx.coroutines.flow.Flow

interface CellRepository {
    suspend fun insertOrUpdateCell(cell: CellTower)
    fun getAllCells(): Flow<List<CellTower>>
    suspend fun getCellByCid(cid: Int): CellTower?
    suspend fun getKnownCellsForLac(lacTac: Int): List<CellTower>
}
