/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.bp22intel.edgesentinel.domain.model.ThreatType

/**
 * Shared utility for threat-type display properties.
 *
 * Replaces private copies previously scattered across AlertCard, AlertDetailScreen, etc.
 */
object ThreatTypeUtils {

    /** Icon for a given threat type (Material Filled). */
    fun icon(type: ThreatType): ImageVector {
        return when (type) {
            ThreatType.FAKE_BTS -> Icons.Filled.SignalCellular4Bar
            ThreatType.NETWORK_DOWNGRADE -> Icons.Filled.NetworkCheck
            ThreatType.SILENT_SMS -> Icons.Filled.Sms
            ThreatType.TRACKING_PATTERN -> Icons.Filled.TrackChanges
            ThreatType.CIPHER_ANOMALY -> Icons.Filled.GppBad
            ThreatType.SIGNAL_ANOMALY -> Icons.Filled.GppMaybe
            ThreatType.NR_ANOMALY -> Icons.Filled.NetworkCheck
            ThreatType.REGISTRATION_FAILURE -> Icons.Filled.GppBad
            ThreatType.TEMPORAL_ANOMALY -> Icons.Filled.TrackChanges
            ThreatType.KNOWN_TOWER_ANOMALY -> Icons.Filled.GppMaybe
            ThreatType.COMPOUND_PATTERN -> Icons.Filled.Warning
        }
    }

    /** Full human-readable label (e.g. "Fake Base Station"). */
    fun label(type: ThreatType): String {
        return when (type) {
            ThreatType.FAKE_BTS -> "Fake Base Station"
            ThreatType.NETWORK_DOWNGRADE -> "Network Downgrade"
            ThreatType.SILENT_SMS -> "Silent SMS"
            ThreatType.TRACKING_PATTERN -> "Tracking Pattern"
            ThreatType.CIPHER_ANOMALY -> "Cipher Anomaly"
            ThreatType.SIGNAL_ANOMALY -> "Signal Anomaly"
            ThreatType.NR_ANOMALY -> "5G NR Anomaly"
            ThreatType.REGISTRATION_FAILURE -> "Authentication Failure"
            ThreatType.TEMPORAL_ANOMALY -> "Temporal Anomaly"
            ThreatType.KNOWN_TOWER_ANOMALY -> "Known Tower Anomaly"
            ThreatType.COMPOUND_PATTERN -> "Compound Attack Pattern"
        }
    }

    /** Short label for compact/card displays (e.g. "Fake BTS", "Downgrade"). */
    fun shortLabel(type: ThreatType): String {
        return when (type) {
            ThreatType.FAKE_BTS -> "Fake BTS"
            ThreatType.NETWORK_DOWNGRADE -> "Downgrade"
            ThreatType.SILENT_SMS -> "Silent SMS"
            ThreatType.TRACKING_PATTERN -> "Tracking"
            ThreatType.CIPHER_ANOMALY -> "Cipher"
            ThreatType.SIGNAL_ANOMALY -> "Signal"
            ThreatType.NR_ANOMALY -> "5G NR"
            ThreatType.REGISTRATION_FAILURE -> "Auth Fail"
            ThreatType.TEMPORAL_ANOMALY -> "Temporal"
            ThreatType.KNOWN_TOWER_ANOMALY -> "Tower Clone"
            ThreatType.COMPOUND_PATTERN -> "Compound"
        }
    }
}
