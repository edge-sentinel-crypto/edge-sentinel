/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.model

enum class ThreatType {
    FAKE_BTS,
    NETWORK_DOWNGRADE,
    SILENT_SMS,
    TRACKING_PATTERN,
    CIPHER_ANOMALY,
    SIGNAL_ANOMALY,
    NR_ANOMALY,
    REGISTRATION_FAILURE,
    TEMPORAL_ANOMALY,
    /** Behavioral anomaly on a KNOWN tower — the tower exists in our database but is acting differently
     *  than its established baseline (band change, signal spike, TAC shift, neighbor list change, etc.).
     *  This catches the most sophisticated attack: spoofing a real Cell ID. */
    KNOWN_TOWER_ANOMALY,
    /** Synthetic type emitted by the fusion layer when a compound attack pattern is detected. */
    COMPOUND_PATTERN
}
