/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.analysis

import com.bp22intel.edgesentinel.data.local.dao.CellDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tower observation at a specific location cluster.
 *
 * @property cellId  The cell tower CID.
 * @property count   How many times this tower has been observed within the cluster radius.
 */
data class TowerObservation(
    val cellId: Long,
    val count: Int
)

/**
 * Learns what's "normal" for frequently visited locations by analysing
 * historical cell tower observations stored in [CellDao].
 *
 * For each query location, it searches the cell history for towers matching
 * the given CID that have been observed within a ~200 m radius. If a tower
 * has been seen 5+ times at that location, it's considered a known fixture
 * and the [FalsePositiveFilter] can use that to reduce alert severity.
 *
 * No new data collection — this piggybacks entirely on existing cell history.
 */
@Singleton
class LocationProfile @Inject constructor(
    private val cellDao: CellDao
) {

    companion object {
        /** Cluster radius in metres — towers within this distance are "at this location". */
        private const val CLUSTER_RADIUS_METERS = 200.0

        /** Minimum observations for a tower to be considered "familiar". */
        private const val FAMILIAR_THRESHOLD = 5

        /** Earth radius in metres for Haversine calculation. */
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }

    /**
     * Check how many times a specific cell tower has been observed near [lat]/[lon].
     *
     * @return A [TowerObservation] with the count, or `null` if the tower
     *         has never been seen or has no location data.
     */
    suspend fun getTowerObservationCount(cellId: Long, lat: Double, lon: Double): TowerObservation? {
        // CellDao uses Int CID — safe cast since cell IDs fit in Int range.
        val entity = cellDao.getByCid(cellId.toInt()) ?: return null

        // If the stored tower has no location, we can still return the count
        // (the tower was seen, just without GPS). Location check is best-effort.
        val towerLat = entity.latitude
        val towerLon = entity.longitude

        if (towerLat != null && towerLon != null) {
            val distance = haversineMeters(lat, lon, towerLat, towerLon)
            if (distance > CLUSTER_RADIUS_METERS) return null
        }

        return TowerObservation(cellId = cellId, count = entity.timesSeen)
    }

    /**
     * Get all "familiar" tower CIDs near a given location — towers seen
     * [FAMILIAR_THRESHOLD]+ times within the cluster radius.
     *
     * The cell table is typically small (hundreds of rows) so a full scan
     * filtered in Kotlin is fine for on-device performance.
     */
    suspend fun getFamiliarTowerIds(lat: Double, lon: Double): Set<Long> {
        val allCells = cellDao.getAll().first()
        return allCells
            .filter { cell ->
                val cLat = cell.latitude ?: return@filter false
                val cLon = cell.longitude ?: return@filter false
                cell.timesSeen >= FAMILIAR_THRESHOLD &&
                    haversineMeters(lat, lon, cLat, cLon) <= CLUSTER_RADIUS_METERS
            }
            .map { it.id }
            .toSet()
    }

    /** Haversine distance between two lat/lon points, in metres. */
    private fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}
