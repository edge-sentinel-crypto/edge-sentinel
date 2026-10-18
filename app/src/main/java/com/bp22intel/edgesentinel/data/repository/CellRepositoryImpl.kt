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
import com.bp22intel.edgesentinel.data.WipeInProgress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CellRepositoryImpl @Inject constructor(
    private val cellDao: CellDao
) : CellRepository {

    override suspend fun insertOrUpdateCell(cell: CellTower): Unit = WipeInProgress.access {
        cellDao.insert(cell.toEntity())
    }

    override fun getAllCells(): Flow<List<CellTower>> =
        WipeInProgress.guard(cellDao.getAll().map { entities -> entities.map { it.toDomain() } })

    override suspend fun getCellByCid(cid: Int): CellTower? = WipeInProgress.access {
        cellDao.getByCid(cid)?.toDomain()
    }

    override suspend fun getKnownCellsForLac(lacTac: Int): List<CellTower> = WipeInProgress.access {
        cellDao.getByLacTac(lacTac).map { it.toDomain() }
    }
}
