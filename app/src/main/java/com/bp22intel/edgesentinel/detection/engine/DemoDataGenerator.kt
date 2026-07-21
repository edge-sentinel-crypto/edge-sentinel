/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.engine

import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.ScanResult
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType
import javax.inject.Inject
import kotlin.random.Random

/**
 * Generates realistic-looking demo data for UI testing and demonstration mode.
 *
 * All generated data uses realistic values: real US carrier MCC/MNC codes,
 * plausible signal strengths, and varied threat types and severity levels.
 */
class DemoDataGenerator @Inject constructor() {

    companion object {
        /** Real US carrier MCC/MNC pairs for realistic demo data. */
        private val US_CARRIERS = listOf(
            CarrierInfo(310, 260, "T-Mobile"),
            CarrierInfo(310, 410, "AT&T"),
            CarrierInfo(311, 480, "Verizon"),
            CarrierInfo(312, 530, "Sprint/T-Mobile"),
            CarrierInfo(310, 120, "Sprint"),
            CarrierInfo(310, 150, "AT&T"),
        )

        /** Suspicious MCC/MNC pair for fake BTS demos. */
        private val SUSPICIOUS_CARRIER = CarrierInfo(310, 999, "Unknown")

        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val HOURS_24 = 24 * MILLIS_PER_HOUR
    }

    private data class CarrierInfo(val mcc: Int, val mnc: Int, val name: String)

    /**
     * Generate a list of realistic-looking alerts spread over the last 24 hours.
     *
     * @param count Number of alerts to generate (default 5).
     * @return List of [Alert] with varied types, severities, and timestamps.
     */
    fun generateDemoAlerts(count: Int = 5): List<Alert> {
        val now = System.currentTimeMillis()
        val alerts = mutableListOf<Alert>()

        val templates = listOf(
            AlertTemplate(
                threatType = ThreatType.FAKE_BTS,
                severity = ThreatLevel.THREAT,
                confidence = Confidence.HIGH,
                summary = "Suspected IMSI catcher detected — abnormally strong signal (-35 dBm) " +
                    "from unknown cell tower (CID 91442)",
                details = """{"strong_signal":"Signal -35 dBm exceeds threshold (-50 dBm)","unknown_cid":"Cell ID 91442 not in observation history","missing_neighbors":"Only 1 cell visible; historical average is 4.2 per LAC"}"""
            ),
            AlertTemplate(
                threatType = ThreatType.NETWORK_DOWNGRADE,
                severity = ThreatLevel.SUSPICIOUS,
                confidence = Confidence.MEDIUM,
                summary = "Significant network downgrade from 4G to 2G detected",
                details = """{"downgrade_48221":"LTE -> GSM (2-step downgrade on CID 48221)"}"""
            ),
            AlertTemplate(
                threatType = ThreatType.TRACKING_PATTERN,
                severity = ThreatLevel.SUSPICIOUS,
                confidence = Confidence.MEDIUM,
                summary = "Rapid cell reselection with unknown LAC values — possible tracking",
                details = """{"unknown_lac":"LAC/TAC values not seen before: 41023, 41099","rapid_reselection":"5 distinct LAC/TAC values in last 5 minutes (threshold: 3)"}"""
            ),
            AlertTemplate(
                threatType = ThreatType.SILENT_SMS,
                severity = ThreatLevel.THREAT,
                confidence = Confidence.MEDIUM,
                summary = "Silent SMS (Type-0) detected — device may be under location tracking",
                details = """{"type":"Type-0 SMS (silent ping)","protocol_id":"0x40"}"""
            ),
            AlertTemplate(
                threatType = ThreatType.SIGNAL_ANOMALY,
                severity = ThreatLevel.CLEAR,
                confidence = Confidence.LOW,
                summary = "Unusual signal pattern detected but within normal parameters",
                details = """{"signal_variance":"Signal strength variance 12 dB over 5 minutes"}"""
            ),
            AlertTemplate(
                threatType = ThreatType.FAKE_BTS,
                severity = ThreatLevel.SUSPICIOUS,
                confidence = Confidence.MEDIUM,
                summary = "Unknown cell tower with unusual MCC/MNC 310/999 detected",
                details = """{"unusual_carrier_72001":"MCC/MNC 310/999 not in known US carrier list","unknown_cid_72001":"Cell ID 72001 not found in observation history"}"""
            ),
            AlertTemplate(
                threatType = ThreatType.NETWORK_DOWNGRADE,
                severity = ThreatLevel.THREAT,
                confidence = Confidence.HIGH,
                summary = "Severe network downgrade from 5G to 2G detected",
                details = """{"downgrade_55102":"NR -> GSM (3-step downgrade on CID 55102)"}"""
            ),
        )

        for (i in 0 until count) {
            val template = templates[i % templates.size]
            val hoursAgo = (i.toDouble() / count * 24).toLong()
            val timestamp = now - (hoursAgo * MILLIS_PER_HOUR) -
                Random.nextLong(0, MILLIS_PER_HOUR)

            alerts.add(
                Alert(
                    id = (i + 1).toLong(),
                    timestamp = timestamp,
                    threatType = template.threatType,
                    severity = template.severity,
                    confidence = template.confidence,
                    summary = template.summary,
                    detailsJson = template.details,
                    cellId = Random.nextLong(10000, 99999),
                    acknowledged = i > count / 2 // Older alerts are acknowledged
                )
            )
        }

        return alerts.sortedByDescending { it.timestamp }
    }

    /**
     * Generate a list of sample cell towers using realistic US carrier data.
     *
     * @return List of [CellTower] representing a typical cellular environment
     *         plus one suspicious tower.
     */
    fun generateDemoCells(): List<CellTower> {
        val now = System.currentTimeMillis()

        val legitimateCells = US_CARRIERS.take(4).mapIndexed { index, carrier ->
            CellTower(
                id = (index + 1).toLong(),
                cid = 10000 + Random.nextInt(1000, 90000),
                lacTac = 20000 + (index * 100),
                mcc = carrier.mcc,
                mnc = carrier.mnc,
                signalStrength = Random.nextInt(-110, -60),
                networkType = when (index) {
                    0 -> NetworkType.LTE
                    1 -> NetworkType.NR
                    2 -> NetworkType.LTE
                    else -> NetworkType.WCDMA
                },
                latitude = 40.7128 + Random.nextDouble(-0.01, 0.01),
                longitude = -74.0060 + Random.nextDouble(-0.01, 0.01),
                firstSeen = now - HOURS_24,
                lastSeen = now - Random.nextLong(0, MILLIS_PER_HOUR),
                timesSeen = Random.nextInt(10, 500)
            )
        }

        // Add one suspicious cell tower
        val suspiciousCell = CellTower(
            id = 99,
            cid = 91442,
            lacTac = 41023,
            mcc = SUSPICIOUS_CARRIER.mcc,
            mnc = SUSPICIOUS_CARRIER.mnc,
            signalStrength = -35, // Abnormally strong
            networkType = NetworkType.GSM, // Forced 2G
            latitude = 40.7130,
            longitude = -74.0058,
            firstSeen = now - (2 * MILLIS_PER_HOUR),
            lastSeen = now - (5 * 60_000), // Seen 5 minutes ago
            timesSeen = 3
        )

        return legitimateCells + suspiciousCell
    }

    /**
     * Generate a sample scan result representing a completed detection scan.
     *
     * @return A [ScanResult] with realistic values.
     */
    fun generateDemoScanResult(): ScanResult {
        return ScanResult(
            id = 1,
            timestamp = System.currentTimeMillis(),
            cellCount = 5,
            threatLevel = ThreatLevel.SUSPICIOUS,
            durationMs = Random.nextLong(800, 3500)
        )
    }

    private data class AlertTemplate(
        val threatType: ThreatType,
        val severity: ThreatLevel,
        val confidence: Confidence,
        val summary: String,
        val details: String
    )
}
