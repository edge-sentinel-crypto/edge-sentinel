/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bp22intel.edgesentinel.data.local.dao.AlertDao
import com.bp22intel.edgesentinel.data.local.dao.AlertFeedbackDao
import com.bp22intel.edgesentinel.data.local.dao.BaselineDao
import com.bp22intel.edgesentinel.data.local.dao.BleDeviceDao
import com.bp22intel.edgesentinel.data.local.dao.CellDao
import com.bp22intel.edgesentinel.data.local.dao.EstimatedTowerPositionDao
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao
import com.bp22intel.edgesentinel.data.local.dao.ScanDao
import com.bp22intel.edgesentinel.data.local.entity.AlertEntity
import com.bp22intel.edgesentinel.data.local.entity.AlertFeedbackEntity
import com.bp22intel.edgesentinel.data.local.entity.BaselineEntity
import com.bp22intel.edgesentinel.data.local.entity.BleDeviceEntity
import com.bp22intel.edgesentinel.data.local.entity.CellTowerEntity
import com.bp22intel.edgesentinel.data.local.entity.EstimatedTowerPositionEntity
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import com.bp22intel.edgesentinel.data.local.entity.ScanEntity
import com.bp22intel.edgesentinel.data.local.entity.TrustedNetworkEntity

@Database(
    entities = [
        CellTowerEntity::class,
        AlertEntity::class,
        AlertFeedbackEntity::class,
        ScanEntity::class,
        BleDeviceEntity::class,
        BaselineEntity::class,
        KnownTowerEntity::class,
        TrustedNetworkEntity::class,
        EstimatedTowerPositionEntity::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class EdgeSentinelDatabase : RoomDatabase() {
    abstract fun cellDao(): CellDao
    abstract fun alertDao(): AlertDao
    abstract fun scanDao(): ScanDao
    abstract fun bleDeviceDao(): BleDeviceDao
    abstract fun baselineDao(): BaselineDao
    abstract fun knownTowerDao(): KnownTowerDao
    abstract fun trustedNetworkDao(): TrustedNetworkDao
    abstract fun alertFeedbackDao(): AlertFeedbackDao
    abstract fun estimatedTowerPositionDao(): EstimatedTowerPositionDao
}
