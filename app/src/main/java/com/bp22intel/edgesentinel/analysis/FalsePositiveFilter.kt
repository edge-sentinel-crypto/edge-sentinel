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

import com.bp22intel.edgesentinel.data.local.dao.AlertFeedbackDao
import com.bp22intel.edgesentinel.domain.model.Alert
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suppression recommendation returned by the learning engine.
 *
 * - [SUPPRESS]: 3+ false-positive reports → don't show the alert at all.
 * - [REDUCE]: 2 reports → lower severity by one level.
 * - [ANNOTATE]: 1 report → show but add a "previously reported" note.
 * - [BOOST]: a confirmed threat exists for this tower → never suppress, boost confidence.
 * - [NONE]: no prior feedback — proceed normally.
 */
enum class SuppressionAction {
    SUPPRESS,
    REDUCE,
    ANNOTATE,
    BOOST,
    NONE
}

/**
 * Full recommendation from the false-positive filter, including the action
 * plus any learning-status strings for the UI.
 */
data class FilterRecommendation(
    val action: SuppressionAction,
    /** Human-readable learning-status lines to display on the alert detail screen. */
    val learningNotes: List<String> = emptyList()
)

/**
 * On-device learning engine that reduces false positives over time.
 *
 * Queries historical user feedback from [AlertFeedbackDao] and produces
 * a [FilterRecommendation] for each new alert. Also integrates
 * [LocationProfile] as an additional "normalcy" signal.
 *
 * All processing is fully offline — no network calls.
 */
@Singleton
class FalsePositiveFilter @Inject constructor(
    private val feedbackDao: AlertFeedbackDao,
    private val locationProfile: LocationProfile
) {

    /**
     * Evaluate an alert against historical feedback and location profile.
     *
     * @return A [FilterRecommendation] indicating how to handle this alert.
     */
    suspend fun evaluate(alert: Alert): FilterRecommendation {
        val details = alert.detailsJson.parseJsonSafe()
        val cellId = details.optLong("cellId", -1L).takeIf { it > 0 }
        val bssid = details.optString("bssid", "").takeIf { it.isNotEmpty() }
        val threatType = alert.threatType.name
        val notes = mutableListOf<String>()

        // ── 0. Check for KNOWN_DEVICE (booster/femtocell/known network the user trusts) ──
        val ssid = details.optString("ssid", "").takeIf { it.isNotEmpty() }
        val knownDeviceCount = when {
            cellId != null -> feedbackDao.getKnownDeviceCount(cellId)
            // Check SSID-level trust first (mesh networks), then BSSID
            ssid != null -> feedbackDao.getKnownDeviceCountForSsid(ssid)
                .takeIf { it > 0 }
                ?: (bssid?.let { feedbackDao.getKnownDeviceCountForBssid(it) } ?: 0)
            bssid != null -> feedbackDao.getKnownDeviceCountForBssid(bssid)
            else -> 0
        }
        if (knownDeviceCount > 0) {
            val label = if (ssid != null) "network \"$ssid\"" else "tower"
            notes.add("This $label is marked as known/trusted — suppressing alert.")
            return FilterRecommendation(action = SuppressionAction.SUPPRESS, learningNotes = notes)
        }

        // ── 1. Check for confirmed threats — always wins ──────────────────
        val confirmedCount = when {
            cellId != null -> feedbackDao.getConfirmedThreatCount(threatType, cellId)
            ssid != null -> feedbackDao.getConfirmedThreatCountForSsid(threatType, ssid)
                .takeIf { it > 0 }
                ?: (bssid?.let { feedbackDao.getConfirmedThreatCountForBssid(threatType, it) } ?: 0)
            bssid != null -> feedbackDao.getConfirmedThreatCountForBssid(threatType, bssid)
            else -> 0
        }
        if (confirmedCount > 0) {
            notes.add("You confirmed this threat type here previously — staying alert.")
            return FilterRecommendation(action = SuppressionAction.BOOST, learningNotes = notes)
        }

        // ── 2. Count false-positive feedback ──────────────────────────────
        val fpCount = when {
            cellId != null -> feedbackDao.getFalsePositiveCount(threatType, cellId)
            ssid != null -> feedbackDao.getFalsePositiveCountForSsid(threatType, ssid)
                .takeIf { it > 0 }
                ?: (bssid?.let { feedbackDao.getFalsePositiveCountForBssid(threatType, it) } ?: 0)
            bssid != null -> feedbackDao.getFalsePositiveCountForBssid(threatType, bssid)
            else -> 0
        }

        // Gather the most recent FP for the "learning status" note.
        if (fpCount > 0 && cellId != null) {
            val latestFp = feedbackDao.getLatestFalsePositive(threatType, cellId)
            if (latestFp != null) {
                val dateStr = SimpleDateFormat("MMM d", Locale.getDefault())
                    .format(Date(latestFp.timestamp))
                notes.add("You marked a similar alert as 'Not a Threat' on $dateStr")
            }
        }

        // ── 3. Location profile — is this tower "normal" here? ────────────
        val lat = details.optDouble("latitude", Double.NaN)
        val lon = details.optDouble("longitude", Double.NaN)
        if (cellId != null && !lat.isNaN() && !lon.isNaN()) {
            val observation = locationProfile.getTowerObservationCount(cellId, lat, lon)
            if (observation != null && observation.count >= 5) {
                notes.add("This tower has been seen ${observation.count} times at this location")
                // Location familiarity acts as a mild FP boost (adds 1 virtual FP report)
                // but only if there are no confirmed threats.
                val adjustedFpCount = fpCount + 1
                return buildRecommendation(adjustedFpCount, notes)
            } else if (observation != null && observation.count > 0) {
                notes.add("This tower has been seen ${observation.count} time${if (observation.count > 1) "s" else ""} at this location")
            }
        }

        return buildRecommendation(fpCount, notes)
    }

    private fun buildRecommendation(fpCount: Int, notes: List<String>): FilterRecommendation {
        val action = when {
            fpCount >= 3 -> SuppressionAction.SUPPRESS
            fpCount == 2 -> SuppressionAction.REDUCE
            fpCount == 1 -> SuppressionAction.ANNOTATE
            else -> SuppressionAction.NONE
        }
        return FilterRecommendation(action = action, learningNotes = notes)
    }

    /** Safely parse JSON, returning empty object on failure. */
    private fun String.parseJsonSafe(): JSONObject {
        return try { JSONObject(this) } catch (_: Exception) { JSONObject() }
    }
}
