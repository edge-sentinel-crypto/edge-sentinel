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

enum class NetworkThreatType(val label: String, val description: String) {
    VPN_DROP(
        "VPN Drop",
        "VPN connection was silently dropped without user action"
    ),
    DNS_HIJACK(
        "DNS Hijack",
        "DNS responses from device resolver differ from trusted resolvers"
    ),
    TLS_MITM(
        "TLS MITM",
        "TLS certificate chain indicates man-in-the-middle interception"
    ),
    CAPTIVE_PORTAL_INJECT(
        "Captive Portal Injection",
        "Captive portal detected with potential JavaScript injection"
    ),
    DNS_NXDOMAIN_HIJACK(
        "NXDOMAIN Hijack",
        "ISP/state actor redirecting failed DNS lookups to controlled servers"
    )
}
