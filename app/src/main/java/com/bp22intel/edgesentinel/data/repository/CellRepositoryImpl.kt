/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.repository

import com.bp22intel.edgesentinel.data.local.dao.CellDao
import com.bp22intel.edgesentinel.data.local.toDomain
import com.bp22intel.edgesentinel.data.local.toEntity
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CellRepositoryImpl @Inject constructor(
    private val cellDao: CellDao
) : CellRepository {

    override suspend fun insertOrUpdateCell(cell: CellTower) {
        cellDao.insert(cell.toEntity())
    }

    override fun getAllCells(): Flow<List<CellTower>> =
        cellDao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getCellByCid(cid: Int): CellTower? =
        cellDao.getByCid(cid)?.toDomain()

    override suspend fun getKnownCellsForLac(lacTac: Int): List<CellTower> =
        cellDao.getByLacTac(lacTac).map { it.toDomain() }
}
