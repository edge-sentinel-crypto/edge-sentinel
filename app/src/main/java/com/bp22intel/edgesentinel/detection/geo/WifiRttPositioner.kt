/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.geo

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi RTT (Round-Trip Time) self-positioning using IEEE 802.11mc.
 *
 * When the device supports WiFi RTT and there are RTT-capable APs nearby,
 * this provides 1-2m user position accuracy — far better than GPS indoors.
 * A more accurate user position translates directly to better threat
 * position estimates.
 *
 * Requires Android 9+ (API 28) and hardware support.
 * Gracefully degrades: [isAvailable] returns false on unsupported devices.
 */
@Singleton
class WifiRttPositioner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WifiRttPositioner"
        /** Maximum time to wait for ranging results (ms). */
        private const val RANGING_TIMEOUT_MS = 3000L
        /** Maximum number of APs to range against per cycle. */
        private const val MAX_APS_PER_REQUEST = 10
    }

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Whether WiFi RTT is available on this device.
     * Checks for Android 9+ and the hardware feature flag.
     */
    val isAvailable: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            false
        } else {
            try {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
            } catch (e: Exception) {
                Log.w(TAG, "Error checking WiFi RTT feature: ${e.message}")
                false
            }
        }
    }

    /**
     * Ranging result for a single AP.
     *
     * @param macAddress AP BSSID
     * @param distanceMm Distance in millimeters
     * @param distanceStdDevMm Standard deviation in millimeters
     * @param rssi RSSI at time of ranging
     * @param numAttempted Number of ranging attempts
     * @param numSuccessful Number of successful measurements
     */
    data class RttResult(
        val macAddress: String,
        val distanceMm: Int,
        val distanceStdDevMm: Int,
        val rssi: Int,
        val numAttempted: Int,
        val numSuccessful: Int
    ) {
        val distanceM: Double get() = distanceMm / 1000.0
        val accuracyM: Double get() = distanceStdDevMm / 1000.0
    }

    /**
     * Perform RTT ranging against nearby RTT-capable APs.
     *
     * This is a **blocking** call (uses a CountDownLatch internally) and
     * should be called from a coroutine / background thread.
     *
     * @return List of successful ranging results, or empty list if unavailable/failed
     */
    @Suppress("MissingPermission")
    fun performRanging(): List<RttResult> {
        if (!isAvailable) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()

        return try {
            performRangingInternal()
        } catch (e: Exception) {
            Log.w(TAG, "RTT ranging failed: ${e.message}")
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @Suppress("MissingPermission")
    private fun performRangingInternal(): List<RttResult> {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val rttManager = context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
            ?: return emptyList()

        if (!rttManager.isAvailable) return emptyList()

        // Get scan results — filter for RTT-capable APs (802.11mc responders)
        val scanResults = try {
            wifiManager.scanResults
                ?.filter { it.is80211mcResponder }
                ?.sortedByDescending { it.level }
                ?.take(MAX_APS_PER_REQUEST)
                ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "No WiFi scan permission: ${e.message}")
            return emptyList()
        }

        if (scanResults.isEmpty()) return emptyList()

        val request = try {
            RangingRequest.Builder()
                .apply { scanResults.forEach { addAccessPoint(it) } }
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build ranging request: ${e.message}")
            return emptyList()
        }

        val resultsRef = AtomicReference<List<RttResult>>(emptyList())
        val latch = CountDownLatch(1)

        rttManager.startRanging(request, executor, object : RangingResultCallback() {
            override fun onRangingResults(results: List<RangingResult>) {
                resultsRef.set(
                    results
                        .filter { it.status == RangingResult.STATUS_SUCCESS }
                        .map { r ->
                            RttResult(
                                macAddress = r.macAddress?.toString() ?: "",
                                distanceMm = r.distanceMm,
                                distanceStdDevMm = r.distanceStdDevMm,
                                rssi = r.rssi,
                                numAttempted = r.numAttemptedMeasurements,
                                numSuccessful = r.numSuccessfulMeasurements
                            )
                        }
                )
                latch.countDown()
            }

            override fun onRangingFailure(code: Int) {
                Log.w(TAG, "Ranging failed with code: $code")
                latch.countDown()
            }
        })

        latch.await(RANGING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return resultsRef.get()
    }
}
