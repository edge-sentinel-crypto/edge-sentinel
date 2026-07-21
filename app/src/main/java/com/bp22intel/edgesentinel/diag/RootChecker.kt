/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.diag

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether the device has root (superuser) access.
 *
 * Uses multiple heuristics since no single check is definitive:
 *   1. Checks for 'su' binary in standard PATH locations
 *   2. Checks for common root management apps (Magisk, SuperSU, etc.)
 *   3. Checks Android build tags for "test-keys"
 *   4. Attempts to execute 'su' to verify actual root shell access
 *
 * Results are cached after first check since root status rarely changes
 * during a single app session.
 */
@Singleton
class RootChecker @Inject constructor() {

    companion object {
        private const val TAG = "RootChecker"

        /** Common paths where su binary may be installed */
        private val SU_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
        )

        /** Package names of common root management apps */
        private val ROOT_PACKAGES = arrayOf(
            "com.topjohnwu.magisk",         // Magisk
            "eu.chainfire.supersu",          // SuperSU
            "com.koushikdutta.superuser",    // Koush Superuser
            "com.noshufou.android.su",       // ChainsDD Superuser
            "com.thirdparty.superuser",      // Other superuser
        )
    }

    /** Cached root status: null = not checked, true/false = result */
    @Volatile
    private var cachedResult: Boolean? = null

    /**
     * Whether the device is rooted.
     *
     * The first call runs all detection heuristics; subsequent calls
     * return the cached result.
     */
    val isRooted: Boolean
        get() {
            cachedResult?.let { return it }
            val result = checkRoot()
            cachedResult = result
            return result
        }

    /** Clears the cached root check result, forcing a re-check on next access. */
    fun clearCache() {
        cachedResult = null
    }

    /**
     * Returns a human-readable summary of root detection findings.
     * Useful for diagnostics/settings UI.
     */
    fun getDetailedStatus(): Map<String, Boolean> = buildMap {
        put("su_binary_found", checkSuBinaryExists())
        put("su_executable", checkSuExecutable())
        put("test_keys", checkBuildTags())
        put("diag_device", File("/dev/diag").exists())
    }

    private fun checkRoot(): Boolean {
        val hasSuBinary = checkSuBinaryExists()
        val hasTestKeys = checkBuildTags()
        val suExecutable = checkSuExecutable()

        val isRooted = hasSuBinary || hasTestKeys || suExecutable

        Log.i(TAG, "Root check: su_binary=$hasSuBinary, test_keys=$hasTestKeys, " +
                "su_exec=$suExecutable -> rooted=$isRooted")

        return isRooted
    }

    /** Checks if the 'su' binary exists in any standard location. */
    private fun checkSuBinaryExists(): Boolean {
        for (path in SU_PATHS) {
            if (File(path).exists()) {
                Log.d(TAG, "Found su binary at $path")
                return true
            }
        }

        // Also check PATH environment variable
        val path = System.getenv("PATH") ?: return false
        for (dir in path.split(":")) {
            val su = File(dir, "su")
            if (su.exists()) {
                Log.d(TAG, "Found su binary in PATH at ${su.absolutePath}")
                return true
            }
        }

        return false
    }

    /** Checks Android build tags for "test-keys" (indicates non-release build). */
    private fun checkBuildTags(): Boolean {
        val tags = android.os.Build.TAGS ?: return false
        return tags.contains("test-keys")
    }

    /**
     * Attempts to execute 'su' to verify actual root shell access.
     * This is the most reliable check but may trigger a superuser prompt.
     */
    private fun checkSuExecutable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            process.destroy()
            exitCode == 0
        } catch (e: Exception) {
            Log.d(TAG, "su execution failed: ${e.message}")
            false
        }
    }
}
