/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when database access is attempted while a panic wipe is running. */
class WipeInProgressException : IllegalStateException("Database access blocked: panic wipe in progress")

/**
 * Process-wide panic-wipe gate. When [begin] is called, every repository
 * database access is refused. [activeOperations] counts accesses that are
 * still running, so the wipe can wait until no writer is inside Room before
 * it closes the database.
 */
object WipeInProgress {
    @Volatile private var active = false
    private val inFlight = AtomicInteger(0)
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile private var handlerInstalled = false

    val isActive: Boolean get() = active
    val activeOperations: Int get() = inFlight.get()

    @Synchronized
    fun begin() {
        active = true
        installUncaughtHandler()
    }

    /**
     * Lifts the gate and restores the original uncaught exception handler.
     * Called if restart fails or returns, or in test resets.
     */
    @Synchronized
    fun abort() {
        active = false
        inFlight.set(0)
        restoreUncaughtHandler()
    }

    /**
     * Restores the original uncaught exception handler before the process restarts.
     */
    @Synchronized
    fun restoreHandlerBeforeRestart() {
        restoreUncaughtHandler()
    }

    /** Throws [WipeInProgressException] when the gate is set. Used by [WipeGuardOpenHelper]. */
    fun checkNotActive() {
        if (active) throw WipeInProgressException()
    }

    /** True when [t] (or a cause) is a [WipeInProgressException]. */
    fun isWipeBlock(t: Throwable?): Boolean {
        var c = t
        var depth = 0
        while (c != null && depth < 16) {
            if (c is WipeInProgressException) return true
            c = c.cause; depth++
        }
        return false
    }

    /**
     * A direct-DAO caller that does not catch the block exception must not
     * crash the process in the middle of the wipe. While the gate is set,
     * uncaught [WipeInProgressException]s (coroutines route them here) are
     * dropped and logged with [Log.w]; every other exception goes to the previous handler.
     */
    @Synchronized
    private fun installUncaughtHandler() {
        if (handlerInstalled) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        handlerInstalled = true
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (active && isWipeBlock(e)) {
                try {
                    Log.w("WipeInProgress", "Suppressed uncaught wipe-block exception during active wipe", e)
                } catch (_: Throwable) {}
                return@setDefaultUncaughtExceptionHandler
            }
            previousHandler?.uncaughtException(thread, e)
        }
    }

    @Synchronized
    private fun restoreUncaughtHandler() {
        if (handlerInstalled) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            previousHandler = null
            handlerInstalled = false
        }
    }

    /** Test-only reset. Restores the handler and clears the gate. */
    fun resetForTests() {
        abort()
    }

    /** Runs [block] as a counted database access, or throws when the gate is set. */
    suspend fun <T> access(block: suspend () -> T): T {
        // Increment first, then check, so begin() + "activeOperations == 0" can never miss a writer.
        inFlight.incrementAndGet()
        try {
            if (active) throw WipeInProgressException()
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** Wraps a Room flow. No new collection starts while the gate is set; errors after the gate is set are dropped. */
    fun <T> guard(source: Flow<T>): Flow<T> = flow {
        if (!active) emitAll(source)
    }.catch { e -> if (!active) throw e }
}
