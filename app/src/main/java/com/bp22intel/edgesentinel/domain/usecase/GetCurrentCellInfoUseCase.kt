/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.usecase

import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Retrieves current and historical cell tower information from the repository.
 */
class GetCurrentCellInfoUseCase @Inject constructor(
    private val cellRepository: CellRepository
) {

    /** Returns a reactive stream of all observed cell towers. */
    fun observeAll(): Flow<List<CellTower>> = cellRepository.getAllCells()

    /** Returns the cell tower matching the given [cid], or null if not found. */
    suspend fun getByCid(cid: Int): CellTower? = cellRepository.getCellByCid(cid)

    /** Returns all known cells in the given LAC/TAC area. */
    suspend fun getKnownCellsForLac(lacTac: Int): List<CellTower> =
        cellRepository.getKnownCellsForLac(lacTac)
}
