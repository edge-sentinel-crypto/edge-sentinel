/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.di

import com.bp22intel.edgesentinel.data.repository.AlertRepositoryImpl
import com.bp22intel.edgesentinel.data.repository.CellRepositoryImpl
import com.bp22intel.edgesentinel.data.repository.ScanRepositoryImpl
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import com.bp22intel.edgesentinel.domain.repository.ScanRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindCellRepository(impl: CellRepositoryImpl): CellRepository

    @Binds
    abstract fun bindAlertRepository(impl: AlertRepositoryImpl): AlertRepository

    @Binds
    abstract fun bindScanRepository(impl: ScanRepositoryImpl): ScanRepository
}
