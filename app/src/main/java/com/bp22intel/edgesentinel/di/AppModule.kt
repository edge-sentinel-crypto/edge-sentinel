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

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase
import com.bp22intel.edgesentinel.data.local.dao.AlertDao
import com.bp22intel.edgesentinel.data.local.dao.AlertFeedbackDao
import com.bp22intel.edgesentinel.data.local.dao.BaselineDao
import com.bp22intel.edgesentinel.data.local.dao.BleDeviceDao
import com.bp22intel.edgesentinel.data.local.dao.CellDao
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.dao.ScanDao
import com.bp22intel.edgesentinel.data.sensor.CellInfoCollector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// Room migrations live in com.bp22intel.edgesentinel.data.local.Migrations so
// they can be shared with instrumented migration tests. No destructive fallback.

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): EdgeSentinelDatabase {
        return Room.databaseBuilder(
            context,
            EdgeSentinelDatabase::class.java,
            "edge_sentinel.db"
        ).addMigrations(*com.bp22intel.edgesentinel.data.local.Migrations.ALL)
         // NOTE: fallbackToDestructiveMigration() was intentionally removed.
         // A missing migration must now fail loudly (IllegalStateException) in
         // QA instead of silently wiping all user data on upgrade.
         .build()
    }

    @Provides
    fun provideCellDao(db: EdgeSentinelDatabase): CellDao = db.cellDao()

    @Provides
    fun provideAlertDao(db: EdgeSentinelDatabase): AlertDao = db.alertDao()

    @Provides
    fun provideScanDao(db: EdgeSentinelDatabase): ScanDao = db.scanDao()

    @Provides
    fun provideBaselineDao(db: EdgeSentinelDatabase): BaselineDao = db.baselineDao()

    @Provides
    fun provideBleDeviceDao(db: EdgeSentinelDatabase): BleDeviceDao = db.bleDeviceDao()

    @Provides
    fun provideKnownTowerDao(db: EdgeSentinelDatabase): KnownTowerDao = db.knownTowerDao()

    @Provides
    fun provideAlertFeedbackDao(db: EdgeSentinelDatabase): AlertFeedbackDao = db.alertFeedbackDao()

    @Provides
    @Singleton
    fun provideTrustedNetworkDao(db: EdgeSentinelDatabase): com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao = db.trustedNetworkDao()

    @Provides
    fun provideEstimatedTowerPositionDao(db: EdgeSentinelDatabase): com.bp22intel.edgesentinel.data.local.dao.EstimatedTowerPositionDao = db.estimatedTowerPositionDao()

    @Provides
    @Singleton
    fun provideCellInfoCollector(
        @ApplicationContext context: Context
    ): CellInfoCollector {
        return CellInfoCollector(context)
    }

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.dataStore
    }
}
