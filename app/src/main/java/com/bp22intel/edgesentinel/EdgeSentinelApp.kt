/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.bp22intel.edgesentinel.data.local.dao.AlertDao
import com.bp22intel.edgesentinel.detection.tower.TowerDatabaseManager
import com.bp22intel.edgesentinel.notification.NotificationChannels
import com.bp22intel.edgesentinel.service.ScanWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class EdgeSentinelApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var towerDatabaseManager: TowerDatabaseManager

    @Inject
    lateinit var alertDao: AlertDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** Default alert retention: 7 days in milliseconds. */
        const val DEFAULT_RETENTION_DAYS = 7
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)

        // Enqueue periodic backup scan worker (runs only if foreground service is dead)
        ScanWorker.enqueue(this)

        // Auto-import bundled US tower database on first launch
        appScope.launch {
            try {
                towerDatabaseManager.autoImportBundledData()
            } catch (e: Exception) {
                android.util.Log.e("EdgeSentinelApp", "Tower auto-import failed", e)
            }
        }

        // Clean up old alerts based on retention policy
        appScope.launch {
            try {
                val retentionMs = DEFAULT_RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000
                val cutoff = System.currentTimeMillis() - retentionMs
                alertDao.deleteBefore(cutoff)
                android.util.Log.d("EdgeSentinelApp", "Alert cleanup: removed alerts older than $DEFAULT_RETENTION_DAYS days")
            } catch (e: Exception) {
                android.util.Log.e("EdgeSentinelApp", "Alert cleanup failed", e)
            }
        }
    }
}
