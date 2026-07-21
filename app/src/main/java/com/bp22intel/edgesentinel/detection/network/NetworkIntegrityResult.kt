/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.network

import com.bp22intel.edgesentinel.domain.model.NetworkThreatType

/**
 * Result of a VPN status check.
 */
data class VpnStatusResult(
    val timestamp: Long = System.currentTimeMillis(),
    val isVpnActive: Boolean,
    val wasVpnActive: Boolean,
    val vpnDropDetected: Boolean,
    val vpnUptimeMs: Long = 0L,
    val vpnDowntimeMs: Long = 0L,
    val lastDropTimestamp: Long? = null,
    val bypassLeakDetected: Boolean = false,
    val leakDetails: String? = null
)

/**
 * Result of querying a single DNS resolver.
 */
data class DnsResolverResult(
    val resolverAddress: String,
    val resolverName: String,
    val resolvedAddresses: List<String>,
    val queryTimeMs: Long,
    val error: String? = null
)

/**
 * Result of a DNS integrity check for a single domain.
 */
data class DnsCheckResult(
    val domain: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceResult: DnsResolverResult,
    val trustedResults: List<DnsResolverResult>,
    val hijackDetected: Boolean,
    val nxdomainHijacked: Boolean = false,
    val mismatchDetails: String? = null
)

/**
 * Aggregate result of all DNS integrity checks.
 */
data class DnsIntegrityResult(
    val timestamp: Long = System.currentTimeMillis(),
    val domainResults: List<DnsCheckResult>,
    val overallClean: Boolean,
    val hijackedDomains: List<String> = emptyList(),
    val nxdomainHijacked: Boolean = false
)

/**
 * Result of a TLS integrity check for a single endpoint.
 */
data class TlsCheckResult(
    val hostname: String,
    val timestamp: Long = System.currentTimeMillis(),
    val certificateChainValid: Boolean,
    val mitmDetected: Boolean,
    val presentedIssuer: String? = null,
    val expectedIssuer: String? = null,
    val certificateFingerprint: String? = null,
    val fingerprintMatch: Boolean? = null,
    val error: String? = null
)

/**
 * Aggregate result of all TLS integrity checks.
 */
data class TlsIntegrityResult(
    val timestamp: Long = System.currentTimeMillis(),
    val endpointResults: List<TlsCheckResult>,
    val overallClean: Boolean,
    val mitmEndpoints: List<String> = emptyList()
)

/**
 * Result of a captive portal detection check.
 */
data class CaptivePortalResult(
    val timestamp: Long = System.currentTimeMillis(),
    val captivePortalDetected: Boolean,
    val portalUrl: String? = null,
    val jsInjectionDetected: Boolean = false,
    val injectionDetails: String? = null,
    val httpStatusCode: Int? = null
)

/**
 * Composite result of all network integrity checks.
 */
data class NetworkIntegritySnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val vpnStatus: VpnStatusResult? = null,
    val dnsIntegrity: DnsIntegrityResult? = null,
    val tlsIntegrity: TlsIntegrityResult? = null,
    val captivePortal: CaptivePortalResult? = null,
    val trustScore: Int = 100,
    val threats: List<NetworkThreatType> = emptyList()
) {
    val isClean: Boolean
        get() = threats.isEmpty()
}
