/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.bluetooth

import com.bp22intel.edgesentinel.data.local.entity.BleDeviceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates alerts when BLE tracking is suspected.
 *
 * Confidence scoring based on:
 * - Number of distinct location clusters where device appeared
 * - Time span between first and last sighting
 * - Whether device matches a known tracker protocol
 * - Pattern consistency (regular sighting intervals)
 */
@Singleton
class BleAlertManager @Inject constructor(
    private val deviceTracker: BleDeviceTracker
) {

    companion object {
        /** Minimum location clusters to consider a device as following. */
        private const val MIN_FOLLOWING_CLUSTERS = 2

        /** Minimum time span (ms) to consider a persistent follower (30 minutes). */
        private const val MIN_FOLLOWING_DURATION_MS = 30 * 60 * 1000L

        /** Number of new devices in a short window to trigger mass surveillance alert. */
        private const val MASS_SURVEILLANCE_THRESHOLD = 15

        /** Time window for mass surveillance detection (5 minutes). */
        private const val MASS_SURVEILLANCE_WINDOW_MS = 5 * 60 * 1000L
    }

    /**
     * Types of BLE tracking alerts.
     */
    enum class BleAlertType {
        PERSISTENT_FOLLOWER,
        KNOWN_TRACKER_DETECTED,
        MASS_SURVEILLANCE,
        ANOMALOUS_BEACON
    }

    /**
     * A BLE tracking alert with confidence level and details.
     */
    data class BleAlert(
        val type: BleAlertType,
        val deviceEntity: BleDeviceEntity,
        val confidence: Float,
        val summary: String,
        val details: Map<String, String>
    )

    private val _activeAlerts = MutableStateFlow<List<BleAlert>>(emptyList())
    val activeAlerts: StateFlow<List<BleAlert>> = _activeAlerts.asStateFlow()

    /**
     * Evaluate all tracked devices for potential tracking behavior.
     * Call this periodically after each scan cycle.
     */
    suspend fun evaluateDevices(devices: List<BleDeviceEntity>) {
        val alerts = mutableListOf<BleAlert>()

        for (device in devices) {
            // Check for persistent followers
            evaluatePersistentFollower(device)?.let { alerts.add(it) }

            // Check for known tracker types
            evaluateKnownTracker(device)?.let { alerts.add(it) }

            // Check for anomalous beacons (rotating MAC, consistent advertising)
            evaluateAnomalousBeacon(device)?.let { alerts.add(it) }
        }

        // Check for mass surveillance (many new devices at once)
        evaluateMassSurveillance()?.let { alerts.add(it) }

        _activeAlerts.value = alerts.sortedByDescending { it.confidence }
    }

    /**
     * Detect a device that has appeared at multiple distinct locations.
     */
    private fun evaluatePersistentFollower(device: BleDeviceEntity): BleAlert? {
        val clusterCount = deviceTracker.getLocationClusterCount(device.locationClusters)
        val timeSpan = device.lastSeen - device.firstSeen

        if (clusterCount < MIN_FOLLOWING_CLUSTERS || timeSpan < MIN_FOLLOWING_DURATION_MS) {
            return null
        }

        val confidence = calculateFollowerConfidence(clusterCount, timeSpan, device)
        val hours = timeSpan / (60 * 60 * 1000.0)

        return BleAlert(
            type = BleAlertType.PERSISTENT_FOLLOWER,
            deviceEntity = device,
            confidence = confidence,
            summary = "This device has followed you to $clusterCount locations in the past ${"%.1f".format(hours)} hours",
            details = mapOf(
                "location_clusters" to "$clusterCount",
                "time_span_hours" to "%.1f".format(hours),
                "seen_count" to "${device.seenCount}",
                "tracker_protocol" to (device.trackerProtocol ?: "unknown"),
                "mac_address" to device.macAddress
            )
        )
    }

    /**
     * Alert when a known tracker (AirTag, SmartTag, Tile) is detected.
     */
    private fun evaluateKnownTracker(device: BleDeviceEntity): BleAlert? {
        if (!device.isTrackerType || device.trackerProtocol == null) return null

        // Only alert if the tracker has been seen for some time (avoid alerting on
        // brief encounters with others' trackers in public places)
        val timeSpan = device.lastSeen - device.firstSeen
        if (timeSpan < MIN_FOLLOWING_DURATION_MS && device.seenCount < 3) return null

        val confidence = if (timeSpan > MIN_FOLLOWING_DURATION_MS) 0.85f else 0.50f

        return BleAlert(
            type = BleAlertType.KNOWN_TRACKER_DETECTED,
            deviceEntity = device,
            confidence = confidence,
            summary = "${device.trackerProtocol?.replace("_", " ")} detected nearby for extended period",
            details = mapOf(
                "tracker_protocol" to device.trackerProtocol,
                "first_seen_ago_minutes" to "${(System.currentTimeMillis() - device.firstSeen) / 60000}",
                "seen_count" to "${device.seenCount}"
            )
        )
    }

    /**
     * Detect devices with rotating MACs that maintain consistent advertising data.
     * This is a signature of a tracker trying to avoid detection by randomizing its
     * address while keeping the same payload.
     */
    private fun evaluateAnomalousBeacon(device: BleDeviceEntity): BleAlert? {
        // An anomalous beacon has been seen many times but isn't a known tracker
        if (device.isTrackerType) return null
        if (device.seenCount < 10) return null

        val timeSpan = device.lastSeen - device.firstSeen
        if (timeSpan < MIN_FOLLOWING_DURATION_MS) return null

        val clusterCount = deviceTracker.getLocationClusterCount(device.locationClusters)
        if (clusterCount < 2) return null

        return BleAlert(
            type = BleAlertType.ANOMALOUS_BEACON,
            deviceEntity = device,
            confidence = 0.60f,
            summary = "Anomalous BLE beacon: consistent advertising data seen at $clusterCount locations",
            details = mapOf(
                "advertising_hash" to device.advertisingDataHash,
                "seen_count" to "${device.seenCount}",
                "location_clusters" to "$clusterCount"
            )
        )
    }

    /**
     * Detect sudden appearance of many new BLE devices (potential mass surveillance).
     */
    private suspend fun evaluateMassSurveillance(): BleAlert? {
        val since = System.currentTimeMillis() - MASS_SURVEILLANCE_WINDOW_MS
        val newDeviceCount = deviceTracker.countNewDevicesSince(since)

        if (newDeviceCount < MASS_SURVEILLANCE_THRESHOLD) return null

        return BleAlert(
            type = BleAlertType.MASS_SURVEILLANCE,
            deviceEntity = BleDeviceEntity(
                macAddress = "N/A",
                advertisingDataHash = "",
                manufacturerId = null,
                deviceName = "Mass BLE Deployment",
                firstSeen = since,
                lastSeen = System.currentTimeMillis(),
                locationClusters = "[]",
                seenCount = newDeviceCount,
                isTrackerType = false,
                trackerProtocol = null
            ),
            confidence = 0.70f,
            summary = "$newDeviceCount new BLE devices appeared in the last 5 minutes — possible mass surveillance deployment",
            details = mapOf(
                "new_device_count" to "$newDeviceCount",
                "window_minutes" to "5",
                "threshold" to "$MASS_SURVEILLANCE_THRESHOLD"
            )
        )
    }

    /**
     * Calculate confidence score for a persistent follower based on multiple factors.
     */
    private fun calculateFollowerConfidence(
        clusterCount: Int,
        timeSpanMs: Long,
        device: BleDeviceEntity
    ): Float {
        var confidence = 0.0f

        // More location clusters = higher confidence
        confidence += when {
            clusterCount >= 5 -> 0.40f
            clusterCount >= 3 -> 0.30f
            else -> 0.15f
        }

        // Longer time span = higher confidence
        val hours = timeSpanMs / (60 * 60 * 1000.0)
        confidence += when {
            hours >= 4.0 -> 0.30f
            hours >= 2.0 -> 0.20f
            else -> 0.10f
        }

        // Known tracker protocol = bonus confidence
        if (device.isTrackerType) {
            confidence += 0.20f
        }

        // High seen count = more consistent pattern
        if (device.seenCount >= 20) {
            confidence += 0.10f
        }

        return confidence.coerceAtMost(0.99f)
    }
}
