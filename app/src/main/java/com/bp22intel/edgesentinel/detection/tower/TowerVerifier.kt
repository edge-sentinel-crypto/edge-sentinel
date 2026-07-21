/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.tower

import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@ActivityRetainedScoped
class TowerVerifier @Inject constructor(
    private val knownTowerDao: KnownTowerDao
) {
    data class VerificationResult(
        val isKnown: Boolean,
        val confidence: Float,        // 0.0 to 1.0
        val knownTower: KnownTowerEntity? = null,
        val distanceFromKnown: Double? = null, // meters from expected position
        val anomalies: List<String> = emptyList()
    )

    suspend fun verifyTower(mcc: Int, mnc: Int, lac: Int, cid: Int, 
                            observedLat: Double? = null, observedLng: Double? = null): VerificationResult {
        val known = knownTowerDao.findTower(mcc, mnc, lac, cid)
        
        if (known == null) {
            // Tower not in database - suspicious
            val countryTowers = knownTowerDao.getTowerCountByCountry(mcc)
            return if (countryTowers == 0) {
                // No data for this country - can't verify
                VerificationResult(isKnown = false, confidence = 0.2f,
                    anomalies = listOf("No tower database installed for MCC $mcc"))
            } else {
                // We have data but tower not found - suspicious
                VerificationResult(isKnown = false, confidence = 0.8f,
                    anomalies = listOf("Tower CID $cid not found in database ($countryTowers towers indexed for MCC $mcc)"))
            }
        }

        // Tower found - check if position matches
        val anomalies = mutableListOf<String>()
        var distance: Double? = null

        if (observedLat != null && observedLng != null && known.latitude != 0.0) {
            distance = haversineDistance(observedLat, observedLng, known.latitude, known.longitude)
            if (distance > known.range * 3) {
                anomalies.add("Tower position ${distance.toInt()}m from expected location (range: ${known.range}m)")
            }
        }

        return VerificationResult(
            isKnown = true,
            confidence = if (anomalies.isEmpty()) 0.95f else 0.5f,
            knownTower = known,
            distanceFromKnown = distance,
            anomalies = anomalies
        )
    }

    // Haversine formula for distance between two GPS points
    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}