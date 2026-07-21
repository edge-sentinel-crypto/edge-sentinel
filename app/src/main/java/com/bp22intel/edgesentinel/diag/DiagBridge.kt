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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin JNI bridge to the native DIAG helper library.
 *
 * Provides a safe Kotlin API over the native C functions that interface with
 * the Qualcomm DIAG port (/dev/diag). All operations check for root access
 * and device availability before attempting native calls.
 *
 * Usage flow:
 *   1. Check [isAvailable] to see if DIAG capture is possible
 *   2. Call [open] to start a DIAG session
 *   3. Call [read] in a loop to receive baseband messages
 *   4. Optionally call [write] to send DIAG commands (e.g., log mask setup)
 *   5. Call [close] when done
 *
 * On non-rooted devices, [isAvailable] returns false and all operations
 * return graceful defaults (null/false) rather than throwing.
 */
@Singleton
class DiagBridge @Inject constructor(
    private val rootChecker: RootChecker,
) {

    companion object {
        private const val TAG = "DiagBridge"
        private var libraryLoaded = false

        init {
            try {
                System.loadLibrary("diag-helper")
                libraryLoaded = true
                Log.i(TAG, "Native diag-helper library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load diag-helper library: ${e.message}")
                libraryLoaded = false
            }
        }
    }

    /**
     * Whether the DIAG interface is available on this device.
     * Requires: native library loaded, root access, and /dev/diag present.
     */
    val isAvailable: Boolean
        get() {
            if (!libraryLoaded) return false
            if (!rootChecker.isRooted) return false
            return nativeCheckDiagDevice()
        }

    /** Whether a DIAG session is currently open. */
    val isOpen: Boolean
        get() = libraryLoaded && nativeIsOpen()

    /**
     * Opens the Qualcomm DIAG device and switches to memory logging mode.
     *
     * @return true if the device was opened successfully, false otherwise
     */
    fun open(): Boolean {
        if (!libraryLoaded) {
            Log.w(TAG, "open: native library not loaded")
            return false
        }
        if (!rootChecker.isRooted) {
            Log.w(TAG, "open: device is not rooted")
            return false
        }

        val fd = nativeOpenDiag()
        if (fd < 0) {
            Log.e(TAG, "open: nativeOpenDiag failed with code $fd")
            return false
        }
        Log.i(TAG, "open: DIAG device opened (fd=$fd)")
        return true
    }

    /**
     * Closes the DIAG device. Safe to call even if not open.
     */
    fun close() {
        if (libraryLoaded) {
            nativeCloseDiag()
            Log.i(TAG, "close: DIAG device closed")
        }
    }

    /**
     * Reads raw bytes from the DIAG device.
     *
     * Returns the raw device output which must be parsed by [DiagMessageParser]
     * to extract individual DIAG messages with CRC validation.
     *
     * @return raw byte array from /dev/diag, or null if not open or on error
     */
    fun read(): ByteArray? {
        if (!libraryLoaded || !nativeIsOpen()) return null
        return nativeReadDiag()
    }

    /**
     * Writes a command to the DIAG device (e.g., log mask configuration).
     *
     * @param data the command bytes to send
     * @return number of bytes written, or a negative error code
     */
    fun write(data: ByteArray): Int {
        if (!libraryLoaded || !nativeIsOpen()) return -1
        return nativeWriteDiag(data)
    }

    /* -- Native method declarations (implemented in diag-helper.c) -- */

    private external fun nativeCheckDiagDevice(): Boolean
    private external fun nativeOpenDiag(): Int
    private external fun nativeCloseDiag()
    private external fun nativeReadDiag(): ByteArray?
    private external fun nativeWriteDiag(data: ByteArray): Int
    private external fun nativeIsOpen(): Boolean
}
