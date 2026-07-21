/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.baseline

import com.bp22intel.edgesentinel.domain.model.CellTower
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background learning process that observes the RF environment each scan cycle
 * and incrementally updates location baselines.
 *
 * Uses exponential moving average for signal strengths and tracks tower appearance
 * rates over time. Marks a baseline as 'confident' after [CONFIDENT_THRESHOLD]
 * observations. Handles time-of-day variations with separate day/night profiles.
 */
@Singleton
class BaselineLearner @Inject constructor() {

    companion object {
        const val CONFIDENT_THRESHOLD = 20
        private const val EMA_ALPHA = 0.15
        private const val APPEARANCE_DECAY = 0.05
    }

    /**
     * Update a baseline with a new observation of cell towers and optional WiFi APs.
     * Returns the updated baseline (caller is responsible for persisting).
     */
    fun learn(
        baseline: LocationBaseline,
        currentTowers: List<CellTower>,
        currentWifiAps: List<ObservedWifiAp>? = null,
        currentBleCount: Int? = null
    ): LocationBaseline {
        val now = System.currentTimeMillis()
        val newObservationCount = baseline.observationCount + 1
        val isDaytime = isDaytime()

        val updatedTowers = updateTowers(baseline.expectedTowers, currentTowers)
        val updatedWifi = updateWifiAps(baseline.expectedWifiAps, currentWifiAps)
        val updatedNetworkDist = updateNetworkDistribution(
            baseline.networkTypeDistribution, currentTowers, newObservationCount
        )
        val updatedBleRange = updateBleRange(baseline.bleCountRange, currentBleCount)

        val updatedDayProfile = if (isDaytime) {
            updateTimeProfile(baseline.dayProfile, currentTowers, currentBleCount)
        } else baseline.dayProfile

        val updatedNightProfile = if (!isDaytime) {
            updateTimeProfile(baseline.nightProfile, currentTowers, currentBleCount)
        } else baseline.nightProfile

        return baseline.copy(
            expectedTowers = updatedTowers,
            expectedWifiAps = updatedWifi,
            bleCountRange = updatedBleRange,
            networkTypeDistribution = updatedNetworkDist,
            dayProfile = updatedDayProfile,
            nightProfile = updatedNightProfile,
            observationCount = newObservationCount,
            confidence = BaselineConfidence.fromObservationCount(newObservationCount),
            updatedAt = now
        )
    }

    /**
     * Create an initial baseline from the first observation at a new location.
     */
    fun createInitial(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        currentTowers: List<CellTower>,
        currentWifiAps: List<ObservedWifiAp>? = null,
        currentBleCount: Int? = null
    ): LocationBaseline {
        val now = System.currentTimeMillis()
        val towers = currentTowers.map { tower ->
            ExpectedTower(
                cid = tower.cid,
                lacTac = tower.lacTac,
                mcc = tower.mcc,
                mnc = tower.mnc,
                networkType = tower.networkType.name,
                signalMin = tower.signalStrength,
                signalMax = tower.signalStrength,
                signalAvg = tower.signalStrength.toDouble(),
                appearanceRate = 1.0
            )
        }

        val wifiAps = currentWifiAps?.map { ap ->
            ExpectedWifiAp(
                bssid = ap.bssid,
                ssid = ap.ssid,
                signalMin = ap.signalStrength,
                signalMax = ap.signalStrength,
                signalAvg = ap.signalStrength.toDouble(),
                appearanceRate = 1.0
            )
        } ?: emptyList()

        val networkDist = currentTowers.groupBy { it.networkType.name }
            .mapValues { it.value.size.toDouble() / currentTowers.size }

        val bleRange = if (currentBleCount != null) {
            currentBleCount..currentBleCount
        } else 0..0

        val profile = TimeProfile(
            towers = towers,
            networkTypeDistribution = networkDist,
            avgBleCount = (currentBleCount ?: 0).toDouble()
        )

        return LocationBaseline(
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            expectedTowers = towers,
            expectedWifiAps = wifiAps,
            bleCountRange = bleRange,
            networkTypeDistribution = networkDist,
            dayProfile = if (isDaytime()) profile else null,
            nightProfile = if (!isDaytime()) profile else null,
            observationCount = 1,
            confidence = BaselineConfidence.LEARNING,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun updateTowers(
        existing: List<ExpectedTower>,
        current: List<CellTower>
    ): List<ExpectedTower> {
        val currentMap = current.associateBy { it.cid }
        val existingMap = existing.associateBy { it.cid }
        val allCids = existingMap.keys + currentMap.keys

        return allCids.map { cid ->
            val exp = existingMap[cid]
            val cur = currentMap[cid]

            when {
                exp != null && cur != null -> {
                    // Update existing tower with EMA
                    exp.copy(
                        signalMin = minOf(exp.signalMin, cur.signalStrength),
                        signalMax = maxOf(exp.signalMax, cur.signalStrength),
                        signalAvg = ema(exp.signalAvg, cur.signalStrength.toDouble()),
                        appearanceRate = minOf(1.0, exp.appearanceRate + APPEARANCE_DECAY)
                    )
                }
                exp != null -> {
                    // Tower not seen this scan — decay appearance rate
                    exp.copy(
                        appearanceRate = maxOf(0.0, exp.appearanceRate - APPEARANCE_DECAY)
                    )
                }
                else -> {
                    // New tower — add with low initial appearance rate
                    val tower = cur!!
                    ExpectedTower(
                        cid = tower.cid,
                        lacTac = tower.lacTac,
                        mcc = tower.mcc,
                        mnc = tower.mnc,
                        networkType = tower.networkType.name,
                        signalMin = tower.signalStrength,
                        signalMax = tower.signalStrength,
                        signalAvg = tower.signalStrength.toDouble(),
                        appearanceRate = APPEARANCE_DECAY
                    )
                }
            }
        }.filter { it.appearanceRate > 0.01 }
    }

    private fun updateWifiAps(
        existing: List<ExpectedWifiAp>,
        current: List<ObservedWifiAp>?
    ): List<ExpectedWifiAp> {
        if (current == null) return existing

        val currentMap = current.associateBy { it.bssid }
        val existingMap = existing.associateBy { it.bssid }
        val allBssids = existingMap.keys + currentMap.keys

        return allBssids.map { bssid ->
            val exp = existingMap[bssid]
            val cur = currentMap[bssid]

            when {
                exp != null && cur != null -> exp.copy(
                    ssid = cur.ssid,
                    signalMin = minOf(exp.signalMin, cur.signalStrength),
                    signalMax = maxOf(exp.signalMax, cur.signalStrength),
                    signalAvg = ema(exp.signalAvg, cur.signalStrength.toDouble()),
                    appearanceRate = minOf(1.0, exp.appearanceRate + APPEARANCE_DECAY)
                )
                exp != null -> exp.copy(
                    appearanceRate = maxOf(0.0, exp.appearanceRate - APPEARANCE_DECAY)
                )
                else -> {
                    val ap = cur!!
                    ExpectedWifiAp(
                        bssid = ap.bssid,
                        ssid = ap.ssid,
                        signalMin = ap.signalStrength,
                        signalMax = ap.signalStrength,
                        signalAvg = ap.signalStrength.toDouble(),
                        appearanceRate = APPEARANCE_DECAY
                    )
                }
            }
        }.filter { it.appearanceRate > 0.01 }
    }

    private fun updateNetworkDistribution(
        existing: Map<String, Double>,
        current: List<CellTower>,
        observationCount: Int
    ): Map<String, Double> {
        if (current.isEmpty()) return existing

        val currentDist = current.groupBy { it.networkType.name }
            .mapValues { it.value.size.toDouble() / current.size }

        val allTypes = existing.keys + currentDist.keys
        return allTypes.associateWith { type ->
            val oldVal = existing[type] ?: 0.0
            val newVal = currentDist[type] ?: 0.0
            ema(oldVal, newVal)
        }
    }

    private fun updateBleRange(existing: IntRange, count: Int?): IntRange {
        if (count == null) return existing
        return minOf(existing.first, count)..maxOf(existing.last, count)
    }

    private fun updateTimeProfile(
        existing: TimeProfile?,
        currentTowers: List<CellTower>,
        currentBleCount: Int?
    ): TimeProfile {
        val towers = currentTowers.map { tower ->
            ExpectedTower(
                cid = tower.cid,
                lacTac = tower.lacTac,
                mcc = tower.mcc,
                mnc = tower.mnc,
                networkType = tower.networkType.name,
                signalMin = tower.signalStrength,
                signalMax = tower.signalStrength,
                signalAvg = tower.signalStrength.toDouble(),
                appearanceRate = 1.0
            )
        }

        if (existing == null) {
            return TimeProfile(
                towers = towers,
                networkTypeDistribution = currentTowers.groupBy { it.networkType.name }
                    .mapValues { it.value.size.toDouble() / currentTowers.size },
                avgBleCount = (currentBleCount ?: 0).toDouble()
            )
        }

        return TimeProfile(
            towers = updateTowers(existing.towers, currentTowers),
            networkTypeDistribution = currentTowers.groupBy { it.networkType.name }
                .mapValues { it.value.size.toDouble() / currentTowers.size }
                .let { currentDist ->
                    val allTypes = existing.networkTypeDistribution.keys + currentDist.keys
                    allTypes.associateWith { type ->
                        ema(existing.networkTypeDistribution[type] ?: 0.0, currentDist[type] ?: 0.0)
                    }
                },
            avgBleCount = ema(existing.avgBleCount, (currentBleCount ?: 0).toDouble())
        )
    }

    private fun ema(old: Double, new: Double): Double =
        old * (1.0 - EMA_ALPHA) + new * EMA_ALPHA

    private fun isDaytime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour in 7..21
    }
}
