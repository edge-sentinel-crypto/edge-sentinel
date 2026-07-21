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

import com.bp22intel.edgesentinel.data.local.dao.ScanDao
import com.bp22intel.edgesentinel.data.local.toDomain
import com.bp22intel.edgesentinel.data.local.toEntity
import com.bp22intel.edgesentinel.domain.model.ScanResult
import com.bp22intel.edgesentinel.domain.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepositoryImpl @Inject constructor(
    private val scanDao: ScanDao
) : ScanRepository {

    override suspend fun insertScan(scan: ScanResult): Long =
        scanDao.insert(scan.toEntity())

    override fun getRecentScans(limit: Int): Flow<List<ScanResult>> =
        scanDao.getRecent(limit).map { entities -> entities.map { it.toDomain() } }
}
