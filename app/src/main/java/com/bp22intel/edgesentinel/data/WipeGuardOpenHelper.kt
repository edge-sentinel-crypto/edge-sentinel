/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 */

package com.bp22intel.edgesentinel.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Round 4 (D4): single choke point for EVERY Room DAO, repository or direct.
 * Room obtains the connection through [writableDatabase]/[readableDatabase]
 * before every query, insert, update, delete and transaction. While a panic
 * wipe is running these getters throw [WipeInProgressException], so no DAO
 * can touch (or re-create) `edge_sentinel.db`, including after Room is closed.
 */
class WipeGuardOpenHelper(private val delegate: SupportSQLiteOpenHelper) : SupportSQLiteOpenHelper by delegate {
    override val writableDatabase: SupportSQLiteDatabase
        get() {
            WipeInProgress.checkNotActive()
            return delegate.writableDatabase
        }

    override val readableDatabase: SupportSQLiteDatabase
        get() {
            WipeInProgress.checkNotActive()
            return delegate.readableDatabase
        }
}

/** Wraps any open-helper factory (the framework one in production) with [WipeGuardOpenHelper]. */
class WipeGuardOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        WipeGuardOpenHelper(delegate.create(configuration))
}
