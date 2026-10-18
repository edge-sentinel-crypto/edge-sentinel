/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.travel

import com.bp22intel.edgesentinel.data.WipeInProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ordered panic-wipe procedure, separated from Android types so it can be tested with fakes.
 *
 * 1. Set [WipeInProgress]. All repository access is blocked from this point.
 * 2. Stop the service and the worker. A timeout or exception is recorded; the gate stays set.
 * 3. Wait until no writer is active. If writers are still active, Room is NOT closed.
 * 4. Run the wipe. On failure, retry once.
 *
 * The caller must restart the process after [run] returns, whatever the result.
 */
class PanicWipeSequence(
    private val stopService: suspend () -> Boolean,
    private val cancelWorker: suspend () -> Boolean,
    private val writersActive: () -> Boolean,
    private val closeDatabase: () -> Unit,
    private val wipe: suspend () -> WipeResult,
    private val writerDrainTimeoutMs: Long = 5_000L,
    private val pollMs: Long = 50L
) {
    var databaseClosed = false
        private set

    suspend fun run(): WipeResult {
        WipeInProgress.begin()
        val errors = mutableListOf<String>()

        val serviceStopped = try { stopService() } catch (e: Exception) {
            errors.add("Monitoring service stop failed: ${e.message ?: e.javaClass.simpleName}"); false
        }
        if (!serviceStopped && errors.isEmpty()) errors.add("Monitoring service did not stop before the timeout")

        val sizeBeforeWorker = errors.size
        val workerStopped = try { cancelWorker() } catch (e: Exception) {
            errors.add("Scan worker cancel failed: ${e.message ?: e.javaClass.simpleName}"); false
        }
        if (!workerStopped && errors.size == sizeBeforeWorker) errors.add("Scan worker did not stop before the timeout")

        // Writes are blocked by the gate. Continue only after in-flight writers drain.
        withTimeoutOrNull(writerDrainTimeoutMs) {
            while (writersActive()) delay(pollMs)
        }
        if (writersActive()) {
            errors.add("Writers still active after ${writerDrainTimeoutMs} ms; database left open, process restart will end them")
        } else {
            try {
                closeDatabase()
                databaseClosed = true
            } catch (e: Exception) {
                errors.add("Failed to close database: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        val first = runWipe()
        if (first.success) return WipeResult.of(first.filesDeleted, errors)

        // Retry the delete once. A successful retry re-verifies that no data remains.
        val retry = runWipe()
        if (!retry.success) {
            errors.addAll(first.errors.map { "Attempt 1: $it" })
            errors.addAll(retry.errors.map { "Retry: $it" })
        }
        return WipeResult.of(first.filesDeleted + retry.filesDeleted, errors)
    }

    private suspend fun runWipe(): WipeResult = try {
        wipe()
    } catch (e: Exception) {
        WipeResult.of(0, listOf("Wipe threw: ${e.message ?: e.javaClass.simpleName}"))
    }
}

/** Round 4: last check before the process restarts after a panic wipe. */
object LeftoverDatabaseCheck {
    val DB_FILE_NAMES = listOf(
        "edge_sentinel.db", "edge_sentinel.db-wal", "edge_sentinel.db-shm", "edge_sentinel.db-journal"
    )

    /** Deletes any of [files] that exist again. Returns one error per leftover found. */
    fun run(files: List<java.io.File>): List<String> {
        val errors = mutableListOf<String>()
        for (f in files) {
            if (!f.exists()) continue
            errors.add("Leftover ${f.name} existed after the wipe; deleted again")
            val deleted = try { f.delete() } catch (e: Exception) { false }
            if (!deleted || f.exists()) errors.add("Could not delete leftover ${f.name}")
        }
        return errors
    }
}
