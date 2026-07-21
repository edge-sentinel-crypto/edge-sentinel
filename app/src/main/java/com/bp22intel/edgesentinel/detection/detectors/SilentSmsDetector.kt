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

import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.ThreatType
import javax.inject.Inject

/**
 * Detects Type-0 (silent/stealth) SMS messages used for device tracking.
 *
 * Silent SMS messages are sent by IMSI catchers and law enforcement to ping
 * a device's location without the user's knowledge. Detection of these messages
 * requires either root access or a custom baseband, neither of which is available
 * in a standard Android app.
 *
 * This detector is a stub. Without root access, it always returns null.
 * It can be triggered in demo mode to show what a detection would look like.
 */
class SilentSmsDetector @Inject constructor() : ThreatDetector {

    override val type: ThreatType = ThreatType.SILENT_SMS

    /**
     * Whether demo mode is active. When true, the detector will generate
     * a synthetic detection result for UI testing.
     */
    var demoMode: Boolean = false

    /**
     * Whether real Type-0 SMS detection is available on this build/device.
     *
     * Non-root Android provides no supported API to observe silent (Type-0)
     * SMS, so this is always false in the shipping configuration. Exposed so
     * that UI and automated tests can assert the feature is correctly reported
     * as unsupported rather than silently pretending to work.
     */
    val isSupported: Boolean = false

    /** Human-readable reason the detector is inactive, for UI/status surfaces. */
    val statusReason: String =
        "Silent SMS (Type-0) detection requires root or a custom baseband and is not " +
        "available on standard Android. This detector is inactive on this device. " +
        "Edge Sentinel does not request the RECEIVE_SMS permission and never reads, " +
        "intercepts, or stores SMS content."

    override suspend fun analyze(
        cells: List<CellTower>,
        history: List<CellTower>
    ): DetectionResult? {
        // In demo mode, return a synthetic detection for UI testing
        if (demoMode) {
            return createDemoResult()
        }

        // Without root access, we cannot intercept Type-0 SMS at the baseband level.
        // The Android SMS API does not surface silent/stealth messages, and this app
        // intentionally does not request the RECEIVE_SMS permission. This detector is
        // therefore permanently inactive outside of demo mode on standard Android.
        return null
    }

    private fun createDemoResult(): DetectionResult {
        return DetectionResult(
            threatType = type,
            score = 4.0,
            confidence = Confidence.MEDIUM,
            summary = "Silent SMS (Type-0) detected — device may be under location tracking",
            details = mapOf(
                "type" to "Type-0 SMS (silent ping)",
                "protocol_id" to "0x40",
                "source" to "Demo mode — real detection requires root access",
                "mitigation" to "Consider enabling airplane mode if under active surveillance"
            )
        )
    }
}
