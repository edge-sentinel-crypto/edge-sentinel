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

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import com.bp22intel.edgesentinel.data.local.dao.BleDeviceDao
import com.bp22intel.edgesentinel.data.local.entity.BleDeviceEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintains a local database of seen BLE devices with location and temporal data.
 *
 * Tracks: first_seen, last_seen, location clusters (lat/lon), advertising data hash,
 * manufacturer, RSSI history. Uses Room DAO for persistence.
 */
@Singleton
class BleDeviceTracker @Inject constructor(
    private val bleDeviceDao: BleDeviceDao,
    private val trackerIdentifier: BleTrackerIdentifier
) {

    companion object {
        /** Distance threshold in meters for clustering locations. */
        private const val LOCATION_CLUSTER_RADIUS_METERS = 100.0

        /** Maximum number of location clusters to keep per device. */
        private const val MAX_LOCATION_CLUSTERS = 50
    }

    /**
     * Record a BLE scan result with optional location data.
     *
     * Updates existing device record or creates a new one. Manages location
     * clustering to detect devices that follow across distinct locations.
     *
     * @param scanResult The BLE scan result
     * @param latitude Current latitude, or null if unavailable
     * @param longitude Current longitude, or null if unavailable
     */
    suspend fun recordDevice(
        scanResult: ScanResult,
        latitude: Double?,
        longitude: Double?
    ) {
        val address = scanResult.device.address
        val scanRecord = scanResult.scanRecord
        val now = System.currentTimeMillis()
        val advHash = computeAdvertisingHash(scanRecord)
        val manufacturerId = trackerIdentifier.getManufacturerId(scanRecord)
        val trackerInfo = trackerIdentifier.identify(scanRecord)

        // Try to find existing device by MAC address first, then by advertising hash
        // (handles rotating MAC addresses that maintain the same advertising payload)
        val existing = bleDeviceDao.getByMacAddress(address)
            ?: bleDeviceDao.getByAdvertisingHash(advHash)

        if (existing != null) {
            val updatedClusters = updateLocationClusters(
                existing.locationClusters, latitude, longitude
            )
            bleDeviceDao.update(
                existing.copy(
                    macAddress = address,
                    advertisingDataHash = advHash,
                    manufacturerId = manufacturerId,
                    deviceName = scanResult.device.name ?: existing.deviceName,
                    lastSeen = now,
                    locationClusters = updatedClusters,
                    seenCount = existing.seenCount + 1,
                    isTrackerType = trackerInfo != null || existing.isTrackerType,
                    trackerProtocol = trackerInfo?.protocol?.name ?: existing.trackerProtocol
                )
            )
        } else {
            val locationClusters = buildInitialLocationCluster(latitude, longitude)
            bleDeviceDao.insert(
                BleDeviceEntity(
                    macAddress = address,
                    advertisingDataHash = advHash,
                    manufacturerId = manufacturerId,
                    deviceName = scanResult.device.name,
                    firstSeen = now,
                    lastSeen = now,
                    locationClusters = locationClusters,
                    seenCount = 1,
                    isTrackerType = trackerInfo != null,
                    trackerProtocol = trackerInfo?.protocol?.name
                )
            )
        }
    }

    /**
     * Get the number of distinct location clusters where a device has been seen.
     */
    fun getLocationClusterCount(locationClustersJson: String): Int {
        return try {
            JSONArray(locationClustersJson).length()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Get all tracked devices as a Flow.
     */
    fun getAllDevices() = bleDeviceDao.getAll()

    /**
     * Get identified tracker devices.
     */
    fun getTrackers() = bleDeviceDao.getTrackers()

    /**
     * Get devices seen recently.
     */
    fun getRecentDevices(since: Long) = bleDeviceDao.getRecentDevices(since)

    /**
     * Count new devices that appeared since a given timestamp.
     */
    suspend fun countNewDevicesSince(since: Long): Int =
        bleDeviceDao.countNewDevicesSince(since)

    /**
     * Clean up old device records.
     */
    suspend fun pruneOldDevices(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        bleDeviceDao.deleteBefore(cutoff)
    }

    /**
     * Compute a SHA-256 hash of the advertising data for fingerprinting devices
     * with rotating MAC addresses.
     */
    private fun computeAdvertisingHash(scanRecord: ScanRecord?): String {
        val bytes = scanRecord?.bytes ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Build initial location cluster JSON from first sighting coordinates.
     */
    private fun buildInitialLocationCluster(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return "[]"
        val cluster = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("count", 1)
            put("first_seen", System.currentTimeMillis())
        }
        return JSONArray().put(cluster).toString()
    }

    /**
     * Update location clusters with a new sighting. If the new location is within
     * [LOCATION_CLUSTER_RADIUS_METERS] of an existing cluster, increment its count.
     * Otherwise, create a new cluster.
     */
    private fun updateLocationClusters(
        existingJson: String,
        lat: Double?,
        lon: Double?
    ): String {
        if (lat == null || lon == null) return existingJson

        val clusters = try {
            JSONArray(existingJson)
        } catch (_: Exception) {
            JSONArray()
        }

        // Check if new location fits into an existing cluster
        for (i in 0 until clusters.length()) {
            val cluster = clusters.getJSONObject(i)
            val clusterLat = cluster.getDouble("lat")
            val clusterLon = cluster.getDouble("lon")
            val distance = haversineDistance(lat, lon, clusterLat, clusterLon)

            if (distance < LOCATION_CLUSTER_RADIUS_METERS) {
                cluster.put("count", cluster.getInt("count") + 1)
                return clusters.toString()
            }
        }

        // New distinct location — add a new cluster
        if (clusters.length() < MAX_LOCATION_CLUSTERS) {
            val newCluster = JSONObject().apply {
                put("lat", lat)
                put("lon", lon)
                put("count", 1)
                put("first_seen", System.currentTimeMillis())
            }
            clusters.put(newCluster)
        }

        return clusters.toString()
    }

    /**
     * Haversine distance between two lat/lon points in meters.
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6_371_000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
