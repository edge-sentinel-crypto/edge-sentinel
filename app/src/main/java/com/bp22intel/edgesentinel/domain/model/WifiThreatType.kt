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

/**
 * WiFi-specific threat categories detected by the WiFi analysis layer.
 */
enum class WifiThreatType(val label: String, val description: String) {
    EVIL_TWIN(
        "Evil Twin",
        "Multiple APs sharing the same SSID with different BSSIDs or conflicting security"
    ),
    ROGUE_AP(
        "Rogue AP",
        "New access point appearing at abnormally high signal strength"
    ),
    DEAUTH_ATTACK(
        "Deauth Attack",
        "Rapid connect/disconnect cycles indicating a deauthentication attack"
    ),
    SSID_SPOOF(
        "SSID Spoof",
        "Access point mimicking well-known network names in unusual locations"
    ),
    KARMA_ATTACK(
        "Karma Attack",
        "AP responding to probe requests with matching SSIDs — possible KARMA/MANA attack"
    ),
    PROBE_LEAK(
        "Probe Leak",
        "Device broadcasting probe requests that reveal previously-connected SSIDs"
    )
}
