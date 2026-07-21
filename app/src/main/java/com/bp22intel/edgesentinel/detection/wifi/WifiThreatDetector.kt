/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.wifi

import com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.WifiThreatType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single WiFi threat detection finding.
 */
data class WifiDetectionResult(
    val threatType: WifiThreatType,
    val score: Double,
    val confidence: Confidence,
    val summary: String,
    val details: Map<String, String>,
    val involvedAps: List<ObservedAp> = emptyList()
)

/**
 * WiFi threat detection engine.
 *
 * Analyzes scan snapshots and rolling history to detect:
 * - Evil twin attacks (same SSID, different BSSID, conflicting security)
 * - Rogue APs (new AP at abnormally high signal strength)
 * - Deauth attacks (rapid connect/disconnect cycles)
 * - SSID spoofing (APs mimicking well-known network names)
 * - Karma/MANA attacks (APs matching many probe-request SSIDs)
 *
 * All detection is LOCAL — no data ever leaves the device.
 */
@Singleton
class WifiThreatDetector @Inject constructor(
    private val trustedNetworkDao: TrustedNetworkDao
) {

    companion object {
        /** Signal stronger than this for a new AP is suspicious. */
        private const val ROGUE_SIGNAL_THRESHOLD = -40

        /** Minimum number of scans before we flag new APs. */
        private const val MIN_HISTORY_FOR_ROGUE = 3

        /** SSIDs commonly targeted by spoofing attacks. */
        private val COMMONLY_SPOOFED_SSIDS = setOf(
            "free wifi", "free_wifi", "freewifi",
            "airport wifi", "airport_wifi", "airport free wifi",
            "starbucks", "starbucks wifi",
            "hotel wifi", "hotel_wifi", "guest",
            "attwifi", "xfinity", "xfinitywifi",
            "google starbucks",
            "mcdonalds wifi", "mcdonalds free wifi",
            "boingo", "boingo hotspot",
            "tmobile", "t-mobile",
            "linksys", "netgear", "default",
            "wifi", "free internet", "open"
        )

        /** Max SSIDs an AP can advertise before KARMA suspicion. */
        private const val KARMA_SSID_THRESHOLD = 5

        /** Rapid disconnection events within this window trigger deauth detection. */
        private const val DEAUTH_WINDOW_MS = 60_000L
        private const val DEAUTH_MIN_EVENTS = 3
    }

    /**
     * Run all detectors against the current scan and historical data.
     *
     * When [trustedBssids] is provided, detections involving ONLY trusted APs
     * are suppressed entirely, and mixed detections have their score reduced.
     *
     * When [trustedSsids] is provided, evil-twin detections for those SSIDs are
     * suppressed (mesh networks with multiple BSSIDs are expected). Anomaly
     * detections (e.g., encryption downgrade) on trusted SSIDs are still raised.
     */
    fun analyze(
        current: WifiScanSnapshot,
        history: List<WifiScanSnapshot>,
        disconnectTimestamps: List<Long> = emptyList(),
        trustedBssids: Set<String> = emptySet(),
        trustedSsids: Set<String> = emptySet()
    ): List<WifiDetectionResult> {
        val results = mutableListOf<WifiDetectionResult>()

        detectEvilTwins(current, trustedSsids)?.let { results.addAll(it) }
        detectRogueAps(current, history)?.let { results.addAll(it) }
        detectDeauthAttack(disconnectTimestamps)?.let { results.add(it) }
        detectSsidSpoofing(current)?.let { results.addAll(it) }
        detectKarmaAttack(current, history)?.let { results.addAll(it) }

        if (trustedBssids.isEmpty() && trustedSsids.isEmpty()) return results

        // Filter / attenuate results based on trusted networks
        return results.mapNotNull { result ->
            // Check if all involved APs belong to a trusted SSID
            val involvedSsids = result.involvedAps.map { it.ssid }.toSet()
            val allSsidsTrusted = involvedSsids.isNotEmpty() && involvedSsids.all { it in trustedSsids }

            val involvedBssids = result.involvedAps.map { it.bssid }.toSet()
            val allBssidsTrusted = involvedBssids.isNotEmpty() && involvedBssids.all { it in trustedBssids }

            when {
                // All involved APs are trusted (by SSID or BSSID) — suppress entirely
                allSsidsTrusted || allBssidsTrusted -> null
                // Some involved APs are trusted — reduce score
                involvedBssids.any { it in trustedBssids } ||
                    involvedSsids.any { it in trustedSsids } -> result.copy(
                    score = result.score * 0.5,
                    confidence = scoreToConfidence(result.score * 0.5)
                )
                // No trusted APs involved — keep as-is
                else -> result
            }
        }
    }

    /**
     * Convenience: load trusted BSSIDs and SSIDs from the database and run analysis.
     */
    suspend fun analyzeWithTrustedNetworks(
        current: WifiScanSnapshot,
        history: List<WifiScanSnapshot>,
        disconnectTimestamps: List<Long> = emptyList()
    ): List<WifiDetectionResult> {
        val trustedBssids = trustedNetworkDao.getAllTrustedBssids().toSet()
        val trustedSsids = trustedNetworkDao.getAllTrustedSsids().toSet()
        return analyze(current, history, disconnectTimestamps, trustedBssids, trustedSsids)
    }

    /**
     * Evil Twin: Multiple APs sharing the same SSID but different BSSIDs,
     * especially when one is open and the other is secured.
     *
     * When [trustedSsids] contains an SSID, the "multiple BSSIDs" heuristic
     * is skipped (mesh networks are expected). However, security anomalies
     * (e.g., open AP among secured ones) are still flagged even for trusted
     * SSIDs — an attacker could spoof a trusted network name.
     */
    private fun detectEvilTwins(
        scan: WifiScanSnapshot,
        trustedSsids: Set<String> = emptySet()
    ): List<WifiDetectionResult>? {
        val results = mutableListOf<WifiDetectionResult>()

        // Group by SSID, ignore hidden/empty SSIDs
        val bySsid = scan.accessPoints
            .filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid }

        for ((ssid, aps) in bySsid) {
            if (aps.size < 2) continue

            val uniqueBssids = aps.map { it.bssid }.distinct()
            if (uniqueBssids.size < 2) continue

            val isTrustedSsid = ssid in trustedSsids

            val securityTypes = aps.map { it.securityType }.distinct()
            val hasOpenAndSecured = SecurityType.OPEN in securityTypes &&
                securityTypes.any { it != SecurityType.OPEN && it != SecurityType.UNKNOWN }

            // For trusted SSIDs: skip the multiple-BSSID heuristic entirely
            // UNLESS there's a security mismatch (open+secured = possible attack)
            if (isTrustedSsid && !hasOpenAndSecured) {
                continue
            }

            var score = 1.0
            val indicators = mutableMapOf<String, String>()

            indicators["ssid"] = ssid
            indicators["bssid_count"] = "${uniqueBssids.size} distinct BSSIDs"

            if (hasOpenAndSecured) {
                score += 2.0
                indicators["security_mismatch"] =
                    "Mixed security: ${securityTypes.joinToString { it.label }}"
                if (isTrustedSsid) {
                    indicators["trusted_anomaly"] =
                        "⚠ Trusted network '$ssid' has an unexpected open AP — possible spoofing"
                }
            }

            // Different channels for same SSID is more suspicious
            val uniqueFreqs = aps.map { it.frequency }.distinct()
            if (uniqueFreqs.size > 1) {
                score += 0.5
                indicators["frequency_spread"] =
                    "Operating on ${uniqueFreqs.size} different frequencies"
            }

            // Large signal strength disparity
            val maxSignal = aps.maxOf { it.signalStrength }
            val minSignal = aps.minOf { it.signalStrength }
            if (maxSignal - minSignal > 20) {
                score += 0.5
                indicators["signal_disparity"] =
                    "Signal spread: $minSignal to $maxSignal dBm (${maxSignal - minSignal} dBm delta)"
            }

            val confidence = scoreToConfidence(score)

            results.add(
                WifiDetectionResult(
                    threatType = WifiThreatType.EVIL_TWIN,
                    score = score,
                    confidence = confidence,
                    summary = if (isTrustedSsid)
                        "Security anomaly on trusted network '$ssid': mixed open/secured APs detected"
                    else
                        buildEvilTwinSummary(ssid, uniqueBssids.size, hasOpenAndSecured),
                    details = indicators,
                    involvedAps = aps
                )
            )
        }

        return results.ifEmpty { null }
    }

    /**
     * Rogue AP: New AP appearing at abnormally high signal strength that was
     * never seen in prior scans.
     */
    private fun detectRogueAps(
        current: WifiScanSnapshot,
        history: List<WifiScanSnapshot>
    ): List<WifiDetectionResult>? {
        if (history.size < MIN_HISTORY_FOR_ROGUE) return null

        val historicalBssids = history.flatMap { it.accessPoints }.map { it.bssid }.toSet()
        val results = mutableListOf<WifiDetectionResult>()

        for (ap in current.accessPoints) {
            if (ap.bssid in historicalBssids) continue
            if (ap.signalStrength < ROGUE_SIGNAL_THRESHOLD) continue

            var score = 1.5
            val indicators = mutableMapOf<String, String>()

            indicators["bssid"] = ap.bssid
            indicators["ssid"] = ap.ssid.ifBlank { "(hidden)" }
            indicators["signal"] = "${ap.signalStrength} dBm (threshold: $ROGUE_SIGNAL_THRESHOLD dBm)"

            // Open network at strong signal is more suspicious
            if (ap.securityType == SecurityType.OPEN) {
                score += 1.0
                indicators["open_network"] = "No encryption — potential honeypot"
            }

            // Very strong signal
            if (ap.signalStrength > -30) {
                score += 1.0
                indicators["extreme_signal"] =
                    "Extremely strong signal (${ap.signalStrength} dBm) suggests very close proximity"
            }

            results.add(
                WifiDetectionResult(
                    threatType = WifiThreatType.ROGUE_AP,
                    score = score,
                    confidence = scoreToConfidence(score),
                    summary = "New AP '${ap.ssid.ifBlank { "(hidden)" }}' at ${ap.signalStrength} dBm — not in observation history",
                    details = indicators,
                    involvedAps = listOf(ap)
                )
            )
        }

        return results.ifEmpty { null }
    }

    /**
     * Deauth Attack: Heuristic detection via rapid connect/disconnect cycles.
     * The caller provides timestamps of recent WiFi disconnect events.
     */
    private fun detectDeauthAttack(disconnectTimestamps: List<Long>): WifiDetectionResult? {
        val now = System.currentTimeMillis()
        val recentDisconnects = disconnectTimestamps.filter { now - it < DEAUTH_WINDOW_MS }

        if (recentDisconnects.size < DEAUTH_MIN_EVENTS) return null

        val count = recentDisconnects.size
        val windowSec = DEAUTH_WINDOW_MS / 1000

        var score = 1.0 + (count - DEAUTH_MIN_EVENTS) * 0.5
        val indicators = mutableMapOf(
            "disconnect_count" to "$count disconnections in ${windowSec}s window",
            "timestamps" to recentDisconnects.takeLast(5).joinToString { "${(now - it) / 1000}s ago" }
        )

        // Very rapid disconnects are more suspicious
        if (count >= 5) {
            score += 1.0
            indicators["rapid_cycle"] = "High-frequency cycling suggests active deauthentication frames"
        }

        return WifiDetectionResult(
            threatType = WifiThreatType.DEAUTH_ATTACK,
            score = score,
            confidence = scoreToConfidence(score),
            summary = "$count rapid WiFi disconnections in ${windowSec}s — possible deauth attack",
            details = indicators
        )
    }

    /**
     * SSID Spoofing: Detects APs mimicking well-known trusted network names.
     * Open networks with common names are flagged as suspicious.
     */
    private fun detectSsidSpoofing(scan: WifiScanSnapshot): List<WifiDetectionResult>? {
        val results = mutableListOf<WifiDetectionResult>()

        for (ap in scan.accessPoints) {
            val normalized = ap.ssid.trim().lowercase()
            if (normalized !in COMMONLY_SPOOFED_SSIDS) continue

            var score = 0.5
            val indicators = mutableMapOf<String, String>()

            indicators["ssid"] = ap.ssid
            indicators["bssid"] = ap.bssid
            indicators["known_target"] = "Matches commonly-spoofed SSID pattern"

            if (ap.securityType == SecurityType.OPEN) {
                score += 1.5
                indicators["open_network"] = "Open network — no authentication required"
            }

            if (ap.signalStrength > -50) {
                score += 0.5
                indicators["strong_signal"] = "${ap.signalStrength} dBm — unusually strong"
            }

            if (score >= 1.0) {
                results.add(
                    WifiDetectionResult(
                        threatType = WifiThreatType.SSID_SPOOF,
                        score = score,
                        confidence = scoreToConfidence(score),
                        summary = "AP '${ap.ssid}' matches a commonly-spoofed network name" +
                            if (ap.securityType == SecurityType.OPEN) " (open network)" else "",
                        details = indicators,
                        involvedAps = listOf(ap)
                    )
                )
            }
        }

        return results.ifEmpty { null }
    }

    /**
     * Karma/MANA Attack: Detects APs that appear to respond to many different
     * SSIDs — a hallmark of KARMA/MANA rogue AP tools. Identified by a single
     * BSSID advertising multiple distinct SSIDs across scan history.
     */
    private fun detectKarmaAttack(
        current: WifiScanSnapshot,
        history: List<WifiScanSnapshot>
    ): List<WifiDetectionResult>? {
        // Build BSSID -> set of SSIDs observed across history + current
        val bssidSsids = mutableMapOf<String, MutableSet<String>>()

        val allSnapshots = history + current
        for (snapshot in allSnapshots) {
            for (ap in snapshot.accessPoints) {
                if (ap.ssid.isBlank()) continue
                bssidSsids.getOrPut(ap.bssid) { mutableSetOf() }.add(ap.ssid)
            }
        }

        val results = mutableListOf<WifiDetectionResult>()

        for ((bssid, ssids) in bssidSsids) {
            if (ssids.size < KARMA_SSID_THRESHOLD) continue

            // Only flag if this BSSID is visible in the current scan
            val currentAp = current.accessPoints.find { it.bssid == bssid } ?: continue

            val score = 2.0 + (ssids.size - KARMA_SSID_THRESHOLD) * 0.5
            val indicators = mutableMapOf(
                "bssid" to bssid,
                "ssid_count" to "${ssids.size} distinct SSIDs observed",
                "ssids" to ssids.take(10).joinToString(", ") { "'$it'" },
                "current_ssid" to currentAp.ssid
            )

            results.add(
                WifiDetectionResult(
                    threatType = WifiThreatType.KARMA_ATTACK,
                    score = score,
                    confidence = scoreToConfidence(score),
                    summary = "AP $bssid has advertised ${ssids.size} different SSIDs — possible KARMA/MANA attack",
                    details = indicators,
                    involvedAps = listOf(currentAp)
                )
            )
        }

        return results.ifEmpty { null }
    }

    private fun scoreToConfidence(score: Double): Confidence = when {
        score >= 3.0 -> Confidence.HIGH
        score >= 1.5 -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    private fun buildEvilTwinSummary(
        ssid: String,
        bssidCount: Int,
        hasSecurityMismatch: Boolean
    ): String = when {
        hasSecurityMismatch ->
            "Evil twin detected: '$ssid' has $bssidCount BSSIDs with mixed open/secured configuration"
        bssidCount >= 3 ->
            "Suspicious: '$ssid' broadcast by $bssidCount distinct access points"
        else ->
            "Potential evil twin: '$ssid' seen from $bssidCount different BSSIDs"
    }
}
