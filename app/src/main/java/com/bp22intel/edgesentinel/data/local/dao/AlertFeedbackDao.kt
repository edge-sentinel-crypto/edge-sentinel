/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bp22intel.edgesentinel.data.local.entity.AlertFeedbackEntity

@Dao
interface AlertFeedbackDao {

    @Insert
    suspend fun insertFeedback(feedback: AlertFeedbackEntity): Long

    /** All feedback for a specific cell tower (identified by MCC/MNC/LAC/CID). */
    @Query("""
        SELECT * FROM alert_feedback
        WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cell_id = :cid
        ORDER BY timestamp DESC
    """)
    suspend fun getFeedbackForTower(mcc: Int, mnc: Int, lac: Int, cid: Long): List<AlertFeedbackEntity>

    /** All feedback for a specific WiFi BSSID. */
    @Query("SELECT * FROM alert_feedback WHERE bssid = :bssid ORDER BY timestamp DESC")
    suspend fun getFeedbackForBssid(bssid: String): List<AlertFeedbackEntity>

    /** Count of FALSE_POSITIVE reports for a given threat type + cell tower. */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE threat_type = :threatType AND cell_id = :cellId AND feedback = 'FALSE_POSITIVE'
    """)
    suspend fun getFalsePositiveCount(threatType: String, cellId: Long): Int

    /** Count of CONFIRMED_THREAT reports for a given threat type + cell tower. */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE threat_type = :threatType AND cell_id = :cellId AND feedback = 'CONFIRMED_THREAT'
    """)
    suspend fun getConfirmedThreatCount(threatType: String, cellId: Long): Int

    /** Count of FALSE_POSITIVE reports for a given threat type + BSSID. */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE threat_type = :threatType AND bssid = :bssid AND feedback = 'FALSE_POSITIVE'
    """)
    suspend fun getFalsePositiveCountForBssid(threatType: String, bssid: String): Int

    /** Count of CONFIRMED_THREAT reports for a given threat type + BSSID. */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE threat_type = :threatType AND bssid = :bssid AND feedback = 'CONFIRMED_THREAT'
    """)
    suspend fun getConfirmedThreatCountForBssid(threatType: String, bssid: String): Int

    /** Most recent feedback entries. */
    @Query("SELECT * FROM alert_feedback ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentFeedback(limit: Int): List<AlertFeedbackEntity>

    /** Most recent feedback for a specific alert. */
    @Query("SELECT * FROM alert_feedback WHERE alert_id = :alertId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getFeedbackForAlert(alertId: Long): AlertFeedbackEntity?

    /** Check if a cell tower has been marked as a KNOWN_DEVICE (booster, femtocell, etc.). */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE cell_id = :cellId AND feedback = 'KNOWN_DEVICE'
    """)
    suspend fun getKnownDeviceCount(cellId: Long): Int

    /** Check if a BSSID has been marked as a KNOWN_DEVICE. */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE bssid = :bssid AND feedback = 'KNOWN_DEVICE'
    """)
    suspend fun getKnownDeviceCountForBssid(bssid: String): Int

    /** Check if an SSID has been marked as a KNOWN_DEVICE (known network). */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE ssid = :ssid AND feedback = 'KNOWN_DEVICE'
    """)
    suspend fun getKnownDeviceCountForSsid(ssid: String): Int

    /** Count of FALSE_POSITIVE reports for a given threat type + SSID. */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE threat_type = :threatType AND ssid = :ssid AND feedback = 'FALSE_POSITIVE'
    """)
    suspend fun getFalsePositiveCountForSsid(threatType: String, ssid: String): Int

    /** Count of CONFIRMED_THREAT reports for a given threat type + SSID. */
    @Query("""
        SELECT COUNT(*) FROM alert_feedback
        WHERE threat_type = :threatType AND ssid = :ssid AND feedback = 'CONFIRMED_THREAT'
    """)
    suspend fun getConfirmedThreatCountForSsid(threatType: String, ssid: String): Int

    /** Most recent FALSE_POSITIVE feedback matching threat type + cell, for "learning status" display. */
    @Query("""
        SELECT * FROM alert_feedback
        WHERE threat_type = :threatType AND cell_id = :cellId AND feedback = 'FALSE_POSITIVE'
        ORDER BY timestamp DESC LIMIT 1
    """)
    suspend fun getLatestFalsePositive(threatType: String, cellId: Long): AlertFeedbackEntity?
}
