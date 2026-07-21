/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.detectors

import android.util.Log
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.ThreatType
import com.bp22intel.edgesentinel.diag.DiagBridge
import com.bp22intel.edgesentinel.diag.DiagMessageParser
import com.bp22intel.edgesentinel.diag.DiagMessageParser.Companion.LOG_GSM_RR_SIGNALING
import com.bp22intel.edgesentinel.diag.DiagMessageParser.Companion.LOG_LTE_NAS_EMM_OTA
import com.bp22intel.edgesentinel.diag.DiagMessageParser.Companion.LOG_LTE_RRC_OTA
import com.bp22intel.edgesentinel.diag.DiagMessageParser.Companion.LOG_WCDMA_RRC_SIGNALING
import javax.inject.Inject

/**
 * Deep threat detector that uses the Qualcomm DIAG interface for baseband analysis.
 *
 * This detector provides capabilities that are impossible without root access:
 *   - Cipher mode detection: reads actual encryption algorithm from Cipher Mode Command
 *   - Protocol anomaly detection: spots IMSI harvesting, auth rejects, suspicious LU rejects
 *   - Raw baseband event analysis: captures signaling messages across GSM/WCDMA/LTE
 *
 * On non-rooted devices, this detector gracefully returns null (no detection).
 * The [CipherModeDetector] delegates to this detector when root is available.
 *
 * Detection flow:
 *   1. Check if DIAG interface is available (root + /dev/diag)
 *   2. Open DIAG device if not already open
 *   3. Read raw baseband messages
 *   4. Parse HDLC frames and validate CRC
 *   5. Extract log messages and analyze for threats
 *   6. Return highest-severity finding as DetectionResult
 */
class DiagBasedDetector @Inject constructor(
    private val diagBridge: DiagBridge,
    private val parser: DiagMessageParser,
) : ThreatDetector {

    companion object {
        private const val TAG = "DiagBasedDetector"

        /** Score for A5/0 (no encryption) — critical threat */
        private const val SCORE_CIPHER_A5_0 = 0.95

        /** Score for A5/1 or A5/2 (weak encryption) — high threat */
        private const val SCORE_CIPHER_WEAK = 0.80

        /** Score for protocol anomalies (IMSI request, auth reject) */
        private const val SCORE_PROTOCOL_ANOMALY = 0.70
    }

    override val type: ThreatType = ThreatType.CIPHER_ANOMALY

    override suspend fun analyze(
        cells: List<CellTower>,
        history: List<CellTower>
    ): DetectionResult? {
        if (!diagBridge.isAvailable) {
            return null
        }

        // Open the DIAG device if needed
        if (!diagBridge.isOpen) {
            if (!diagBridge.open()) {
                Log.w(TAG, "Failed to open DIAG device")
                return null
            }
        }

        // Read a batch of raw DIAG data
        val rawData = diagBridge.read() ?: return null
        if (rawData.isEmpty()) return null

        // De-frame device-level wrapper to get individual elements
        val elements = parser.parseRawDeviceData(rawData)
        if (elements.isEmpty()) return null

        val findings = mutableListOf<Finding>()

        for (element in elements) {
            // De-frame HDLC encoding within each element
            val messages = parser.deframeHdlc(element)

            for (messageData in messages) {
                // Try to parse as a DIAG log message
                val logMsg = parser.parseLogMessage(messageData)

                if (logMsg != null) {
                    analyzeLogMessage(logMsg, findings)
                }

                // Also check for protocol anomalies in raw messages
                val anomaly = parser.detectProtocolAnomaly(messageData)
                if (anomaly != null) {
                    findings.add(Finding(
                        score = SCORE_PROTOCOL_ANOMALY,
                        confidence = Confidence.MEDIUM,
                        summary = "Protocol anomaly: $anomaly",
                        details = mapOf(
                            "anomaly" to anomaly,
                            "source" to "diag_raw",
                        ),
                    ))
                }
            }
        }

        if (findings.isEmpty()) return null

        // Return the highest-severity finding
        val worst = findings.maxBy { it.score }
        return DetectionResult(
            threatType = ThreatType.CIPHER_ANOMALY,
            score = worst.score,
            confidence = worst.confidence,
            summary = worst.summary,
            details = worst.details,
        )
    }

    private fun analyzeLogMessage(
        msg: DiagMessageParser.DiagMessage,
        findings: MutableList<Finding>,
    ) {
        when (msg.logCode) {
            LOG_GSM_RR_SIGNALING -> analyzeGsmRr(msg, findings)
            LOG_WCDMA_RRC_SIGNALING -> analyzeWcdmaRrc(msg, findings)
            LOG_LTE_RRC_OTA -> analyzeLteRrc(msg, findings)
            LOG_LTE_NAS_EMM_OTA -> analyzeLteNas(msg, findings)
        }
    }

    /** Analyzes GSM Radio Resource signaling for cipher mode anomalies. */
    private fun analyzeGsmRr(
        msg: DiagMessageParser.DiagMessage,
        findings: MutableList<Finding>,
    ) {
        val cipherInfo = parser.extractCipherMode(msg.payload) ?: return

        Log.i(TAG, "GSM Cipher Mode detected: ${cipherInfo.algorithmName}")

        if (cipherInfo.isWeak) {
            val score = if (cipherInfo.algorithm == DiagMessageParser.CIPHER_A5_0) {
                SCORE_CIPHER_A5_0
            } else {
                SCORE_CIPHER_WEAK
            }

            val confidence = if (cipherInfo.algorithm == DiagMessageParser.CIPHER_A5_0) {
                Confidence.HIGH
            } else {
                Confidence.MEDIUM
            }

            findings.add(Finding(
                score = score,
                confidence = confidence,
                summary = "Weak cipher detected: ${cipherInfo.algorithmName}",
                details = mapOf(
                    "cipher_algorithm" to cipherInfo.algorithmName,
                    "cipher_id" to cipherInfo.algorithm.toString(),
                    "log_code" to "0x%04X".format(msg.logCode),
                    "source" to "diag_gsm_rr",
                ),
            ))
        }
    }

    /** Analyzes WCDMA RRC signaling for security mode anomalies. */
    private fun analyzeWcdmaRrc(
        msg: DiagMessageParser.DiagMessage,
        findings: MutableList<Finding>,
    ) {
        // WCDMA Security Mode Command analysis
        // The payload structure varies by DIAG version; log for future analysis
        if (msg.payload.isNotEmpty()) {
            Log.d(TAG, "WCDMA RRC message captured (${msg.payload.size} bytes)")
        }
    }

    /** Analyzes LTE RRC OTA messages for security anomalies. */
    private fun analyzeLteRrc(
        msg: DiagMessageParser.DiagMessage,
        findings: MutableList<Finding>,
    ) {
        if (msg.payload.isNotEmpty()) {
            Log.d(TAG, "LTE RRC OTA message captured (${msg.payload.size} bytes)")
        }
    }

    /** Analyzes LTE NAS EMM messages for security context issues. */
    private fun analyzeLteNas(
        msg: DiagMessageParser.DiagMessage,
        findings: MutableList<Finding>,
    ) {
        if (msg.payload.isNotEmpty()) {
            Log.d(TAG, "LTE NAS EMM message captured (${msg.payload.size} bytes)")
        }
    }

    /** Internal finding before selecting the worst result. */
    private data class Finding(
        val score: Double,
        val confidence: Confidence,
        val summary: String,
        val details: Map<String, String>,
    )
}
