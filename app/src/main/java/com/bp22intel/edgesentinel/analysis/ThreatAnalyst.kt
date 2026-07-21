/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.analysis

import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI Threat Analyst — rule-based, fully offline engine that interprets raw
 * detection data and produces plain-English situation assessments.
 *
 * Think of this as the intelligence analyst sitting between the raw sensor
 * feeds (detectors) and the executive dashboard (UI). Detectors say *what*
 * happened; the analyst explains *what it means* and *what to do about it*.
 *
 * All analysis is deterministic and runs on-device — no network calls.
 *
 * ## Research basis (v2)
 *
 * Individual alert analysis incorporates findings from:
 * - NDSS 2025 — IMSI-catcher identity-exposing message characterisation
 * - SMDFbs (Sensors 2023) — specification-based FBS misbehaviour detection
 * - ICCCN 2024 — SIB1 broadcast anomaly fingerprinting
 * - Apple-Google Joint Spec (May 2024) — unwanted BLE tracker detection
 * - AirCatch (arXiv 2602.07656, Feb 2026) — tracker device masquerading
 * - Forest Blizzard DNS hijacking campaign (April 2026)
 *
 * Situation analysis incorporates:
 * - FBSDetector / "Gotta Detect 'Em All" (Purdue, arXiv 2401.04958) — multi-step chains
 * - Shaik et al. (NDSS 2016) + Rupprecht et al. (IEEE S&P 2019) — LTE Layer 2 attacks
 * - Temporal, geographic, and attack-chain correlation logic
 */
@Singleton
class ThreatAnalyst @Inject constructor(
    private val falsePositiveFilter: FalsePositiveFilter
) {

    // ──────────────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Analyse a single alert and produce a human-readable assessment.
     *
     * The returned [AlertAnalysis] contains everything the UI needs to
     * display a clear, actionable explanation to the user.
     */
    /**
     * Analyse a single alert and produce a human-readable assessment.
     *
     * This is the synchronous version that does NOT apply false-positive
     * filtering. Use [analyzeAlertWithLearning] for the full pipeline.
     */
    fun analyzeAlert(alert: Alert): AlertAnalysis {
        val details = alert.detailsJson.parseJsonSafe()
        return when (alert.threatType) {
            ThreatType.FAKE_BTS          -> analyzeFakeBts(alert, details)
            ThreatType.NETWORK_DOWNGRADE -> analyzeNetworkDowngrade(alert, details)
            ThreatType.SILENT_SMS        -> analyzeSilentSms(alert, details)
            ThreatType.TRACKING_PATTERN  -> analyzeTrackingPattern(alert, details)
            ThreatType.CIPHER_ANOMALY    -> analyzeCipherAnomaly(alert, details)
            ThreatType.SIGNAL_ANOMALY    -> analyzeSignalAnomaly(alert, details)
            ThreatType.NR_ANOMALY        -> analyzeNrAnomaly(alert, details)
            ThreatType.REGISTRATION_FAILURE -> analyzeRegistrationFailure(alert, details)
            ThreatType.TEMPORAL_ANOMALY  -> analyzeTemporalAnomaly(alert, details)
            ThreatType.KNOWN_TOWER_ANOMALY -> analyzeKnownTowerAnomaly(alert, details)
            ThreatType.COMPOUND_PATTERN  -> analyzeCompoundPattern(alert, details)
        }
    }

    /**
     * Analyse a single alert with false-positive learning applied.
     *
     * Runs the base analysis, then consults [FalsePositiveFilter] to
     * potentially SUPPRESS, REDUCE severity, ANNOTATE, or BOOST the result.
     *
     * @return A pair of the (possibly adjusted) [AlertAnalysis] and the
     *         [FilterRecommendation] containing learning notes for the UI.
     */
    suspend fun analyzeAlertWithLearning(alert: Alert): Pair<AlertAnalysis, FilterRecommendation> {
        val baseAnalysis = analyzeAlert(alert)
        val recommendation = falsePositiveFilter.evaluate(alert)

        val adjustedAnalysis = when (recommendation.action) {
            SuppressionAction.SUPPRESS -> baseAnalysis.copy(
                riskLevel = RiskLevel.LOW,
                shouldWorry = false,
                plainEnglish = baseAnalysis.plainEnglish +
                    " (Suppressed: you've reported similar alerts as false positives multiple times.)"
            )
            SuppressionAction.REDUCE -> {
                val reducedRisk = when (baseAnalysis.riskLevel) {
                    RiskLevel.CRITICAL -> RiskLevel.HIGH
                    RiskLevel.HIGH -> RiskLevel.MEDIUM
                    RiskLevel.MEDIUM -> RiskLevel.LOW
                    RiskLevel.LOW -> RiskLevel.LOW
                }
                baseAnalysis.copy(
                    riskLevel = reducedRisk,
                    shouldWorry = reducedRisk >= RiskLevel.HIGH
                )
            }
            SuppressionAction.ANNOTATE -> baseAnalysis // Notes shown in UI via learningNotes
            SuppressionAction.BOOST -> {
                val boostedRisk = when (baseAnalysis.riskLevel) {
                    RiskLevel.LOW -> RiskLevel.MEDIUM
                    else -> baseAnalysis.riskLevel
                }
                baseAnalysis.copy(
                    riskLevel = boostedRisk,
                    confidence = (baseAnalysis.confidence + 0.1f).coerceAtMost(1.0f),
                    shouldWorry = boostedRisk >= RiskLevel.HIGH || baseAnalysis.shouldWorry
                )
            }
            SuppressionAction.NONE -> baseAnalysis
        }

        return adjustedAnalysis to recommendation
    }

    /**
     * Check if an alert should be suppressed entirely (not shown to the user).
     *
     * Called by [MonitoringService] before creating an alert.
     */
    suspend fun shouldSuppressAlert(alert: Alert): Boolean {
        val recommendation = falsePositiveFilter.evaluate(alert)
        return recommendation.action == SuppressionAction.SUPPRESS
    }

    /**
     * Synthesise multiple alerts and environmental context into a holistic
     * situation brief — the "big picture" for the user.
     *
     * Incorporates multi-step attack chain recognition (FBSDetector, Purdue 2025),
     * temporal correlation, geographic persistence analysis, and deliberate-
     * downgrade cross-referencing (Shaik/Rupprecht LTE Layer 2 research).
     *
     * @param alerts   Current active/recent alerts (may be empty).
     * @param cellInfo Current serving cell information, if available.
     * @param isMoving Whether the device is in motion (walking/driving).
     */
    fun analyzeSituation(
        alerts: List<Alert>,
        cellInfo: CellTower?,
        isMoving: Boolean
    ): SituationBrief {
        if (alerts.isEmpty()) {
            return SituationBrief(
                summary = "No active alerts. Your cellular environment looks normal.",
                overallRisk = RiskLevel.LOW,
                topConcerns = emptyList(),
                recommendations = listOf("No action needed — carry on as normal."),
                allClear = true
            )
        }

        // Analyse each alert individually (sync — no FP filter in situation brief;
        // suppressed alerts are already filtered out by MonitoringService).
        val analyses = alerts.map { analyzeAlert(it) }
        var highestRisk = analyses.maxOf { it.riskLevel }

        // ── Attack chain recognition ────────────────────────────────────
        val threatTypes = alerts.map { it.threatType }.toSet()
        val detectedChains = detectAttackChains(threatTypes)

        // If we matched a known chain, escalate to at least CRITICAL.
        if (detectedChains.isNotEmpty()) {
            highestRisk = maxOf(highestRisk, RiskLevel.CRITICAL)
        }

        // ── Deliberate downgrade cross-reference (Shaik + Rupprecht) ────
        val hasDeliberateDowngrade = detectDeliberateDowngrade(alerts)
        if (hasDeliberateDowngrade) {
            highestRisk = maxOf(highestRisk, RiskLevel.CRITICAL)
        }

        // ── Temporal correlation ────────────────────────────────────────
        val temporalCorrelation = detectTemporalCorrelation(alerts)

        // Multiple distinct alert types within a tight window → escalate.
        if (temporalCorrelation) {
            highestRisk = maxOf(highestRisk, RiskLevel.HIGH)
        }

        // ── Geographic persistence ──────────────────────────────────────
        val geoPersistence = detectGeographicPersistence(alerts)

        // Build top concerns: unique plain-English descriptions, worst first.
        val topConcerns = buildTopConcerns(analyses, detectedChains,
            hasDeliberateDowngrade, temporalCorrelation, geoPersistence)

        // Deduplicate and prioritise recommendations.
        val recommendations = buildRecommendations(
            analyses, highestRisk, isMoving, detectedChains, hasDeliberateDowngrade)

        val allClear = highestRisk == RiskLevel.LOW &&
            analyses.none { it.shouldWorry }

        val summary = buildSituationSummary(
            alerts, analyses, highestRisk, cellInfo, isMoving,
            detectedChains, hasDeliberateDowngrade, temporalCorrelation, geoPersistence)

        return SituationBrief(
            summary = summary,
            overallRisk = highestRisk,
            topConcerns = topConcerns,
            recommendations = recommendations,
            allClear = allClear
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Per-threat-type analysis logic
    // ──────────────────────────────────────────────────────────────────────

    /**
     * FAKE_BTS — potential rogue base station / IMSI catcher.
     *
     * Key signals: unusually high signal strength while stationary suggests
     * a nearby portable transmitter. Moving through a strong-signal area
     * is less suspicious (could be a legitimate macro cell).
     *
     * Research enhancements:
     * - NDSS 2025: rapid identity-request pattern detection
     * - ICCCN 2024: SIB1 broadcast anomaly fingerprinting
     */
    private fun analyzeFakeBts(alert: Alert, details: JSONObject): AlertAnalysis {
        val signalStrength = details.optInt("signalStrength", Int.MIN_VALUE)
        val isMoving = details.optBoolean("isMoving", false)
        val nearbyTowerCount = details.optInt("nearbyTowerCount", -1)
        val isHighSignal = signalStrength != Int.MIN_VALUE && signalStrength > -60

        // NDSS 2025 — rapid identity request pattern.
        // If the BTS sent >1 identity request within a 60-second window, this
        // matches the 53 identity-exposing NAS/RRC messages characterised by
        // the NDSS research. Legitimate towers rarely re-request SUPI/IMSI.
        val identityRequestCount = details.optInt("identityRequestCount", 0)
        val hasRapidIdentityRequests = identityRequestCount > 1

        // ICCCN 2024 — SIB1 broadcast anomaly.
        // Fake base stations frequently have misconfigured or cloned SIB1
        // parameters that don't match the real network's broadcast.
        val sib1Anomaly = details.optBoolean("sib1Anomaly", false)

        // Stationary + high signal + few legitimate towers = very suspicious.
        var riskLevel = when {
            !isMoving && isHighSignal && nearbyTowerCount in 0..2 -> RiskLevel.CRITICAL
            !isMoving && isHighSignal -> RiskLevel.HIGH
            !isMoving -> RiskLevel.MEDIUM
            isHighSignal -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // NDSS 2025 escalation: rapid identity requests bump to at least HIGH.
        if (hasRapidIdentityRequests && riskLevel < RiskLevel.HIGH) {
            riskLevel = RiskLevel.HIGH
        }

        val shouldWorry = riskLevel >= RiskLevel.HIGH

        var plainEnglish = when (riskLevel) {
            RiskLevel.CRITICAL ->
                "A suspicious cell tower with an unusually strong signal appeared nearby while " +
                "you are stationary. This is consistent with a portable surveillance device " +
                "(IMSI catcher) operating close to your location."
            RiskLevel.HIGH ->
                "A cell tower with characteristics of a fake base station is broadcasting " +
                "a very strong signal near you. This could be a surveillance device."
            RiskLevel.MEDIUM ->
                "Some cell tower characteristics look unusual. This may indicate a fake " +
                "base station, but could also be a legitimate tower configuration."
            else ->
                "A cell tower showed some irregular characteristics but the pattern is " +
                "more consistent with normal network behaviour while in transit."
        }

        // ICCCN 2024: append SIB1 anomaly context.
        if (sib1Anomaly) {
            plainEnglish += " System broadcast information from this tower doesn't match " +
                "expected network parameters."
        }

        // NDSS 2025: append identity-request context.
        if (hasRapidIdentityRequests) {
            plainEnglish += " This tower sent $identityRequestCount identity requests in rapid " +
                "succession — legitimate towers rarely re-request your device identity."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.CRITICAL ->
                "Avoid sensitive calls and messaging. Move to a different location " +
                "at least several blocks away and check if the alert clears."
            RiskLevel.HIGH ->
                "Avoid sensitive communications until you leave this area. Use " +
                "encrypted messaging apps for anything confidential."
            RiskLevel.MEDIUM ->
                "Use encrypted messaging for sensitive conversations as a precaution. " +
                "Monitor whether additional alerts appear."
            else ->
                "This is likely normal. No action needed, but keep monitoring."
        }

        val possibleCauses = buildList {
            if (riskLevel >= RiskLevel.HIGH) add("IMSI catcher or rogue base station nearby")
            if (hasRapidIdentityRequests) {
                add("Rapid SUPI/IMSI identity requests detected (NDSS 2025 pattern)")
            }
            if (sib1Anomaly) {
                add("SIB1 broadcast parameters inconsistent with legitimate network")
            }
            add("Legitimate new cell tower with unusual configuration")
            add("Temporary carrier equipment (event coverage, maintenance)")
            if (isMoving) add("Passing through an area with strong tower coverage")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = shouldWorry
        )
    }

    /**
     * NETWORK_DOWNGRADE — forced fallback from 4G/5G to an older protocol.
     *
     * In urban areas this shouldn't happen (dense 4G/5G coverage), so it's
     * more concerning. In rural areas it may be normal coverage gaps.
     *
     * Research enhancement:
     * - Forest Blizzard (April 2026): DNS hijacking context for network anomalies
     */
    private fun analyzeNetworkDowngrade(alert: Alert, details: JSONObject): AlertAnalysis {
        val fromNetwork = details.optString("fromNetwork", "")
        val toNetwork = details.optString("toNetwork", "")
        val isUrban = details.optBoolean("isUrban", true) // Default to urban (more cautious)

        // Forest Blizzard (April 2026) — DNS resolution anomaly context.
        val dnsAnomaly = details.optBoolean("dnsAnomaly", false)

        val isSevereDowngrade = toNetwork in listOf("GSM", "2G", "CDMA")

        val riskLevel = when {
            isSevereDowngrade && isUrban -> RiskLevel.HIGH
            isSevereDowngrade -> RiskLevel.MEDIUM
            isUrban -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        val shouldWorry = riskLevel >= RiskLevel.HIGH

        val fromLabel = fromNetwork.ifEmpty { "a newer network" }
        val toLabel = toNetwork.ifEmpty { "an older network" }

        var plainEnglish = when (riskLevel) {
            RiskLevel.HIGH ->
                "Your phone was forced from $fromLabel down to $toLabel in an area " +
                "with good coverage. This is a common tactic used by surveillance " +
                "equipment to disable modern encryption."
            RiskLevel.MEDIUM ->
                "Your phone dropped from $fromLabel to $toLabel. In your current area " +
                "this is somewhat unusual and could indicate an attempt to weaken " +
                "your connection's security."
            else ->
                "Your phone switched from $fromLabel to $toLabel. In rural or fringe " +
                "coverage areas, this is often normal behaviour."
        }

        if (dnsAnomaly) {
            plainEnglish += " A DNS resolution anomaly was also detected, which may " +
                "indicate network-level tampering."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.HIGH ->
                "Avoid phone calls and standard SMS — they may not be encrypted. " +
                "Use end-to-end encrypted apps (Signal, WhatsApp) for all communication."
            RiskLevel.MEDIUM ->
                "Be cautious with sensitive calls. Prefer encrypted messaging apps " +
                "until your connection returns to a modern network."
            else ->
                "This is likely a normal coverage gap. No special action needed."
        }

        val possibleCauses = buildList {
            if (isUrban && isSevereDowngrade) add("Deliberate downgrade attack to strip encryption")
            if (dnsAnomaly) {
                add("DNS hijacking similar to recent state-sponsored campaigns targeting consumer routers")
            }
            add("Network congestion causing fallback")
            add("Coverage gap or building penetration issues")
            add("Carrier network maintenance or outage")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = shouldWorry
        )
    }

    /**
     * SILENT_SMS — invisible text message used for location pinging.
     *
     * Always concerning. These are a well-documented surveillance technique:
     * the target's phone processes them but shows no notification.
     */
    private fun analyzeSilentSms(alert: Alert, details: JSONObject): AlertAnalysis {
        val count = details.optInt("count", 1)

        val riskLevel = when {
            count >= 5 -> RiskLevel.CRITICAL
            count >= 2 -> RiskLevel.HIGH
            else -> RiskLevel.MEDIUM
        }

        val plainEnglish = when {
            count >= 5 ->
                "Multiple invisible messages ($count) have been sent to your phone in a " +
                "short period. This is a strong indicator of active location tracking — " +
                "someone is pinging your device to determine where you are."
            count >= 2 ->
                "Several silent messages ($count) were detected. These invisible texts are " +
                "used to track your phone's location without your knowledge."
            else ->
                "An invisible text message was sent to your phone. Silent SMS is a known " +
                "technique used by law enforcement and others to ping your location."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.CRITICAL ->
                "Your location is actively being tracked. If this is unexpected, " +
                "enable airplane mode immediately, then assess your situation. " +
                "Consider powering off the device."
            RiskLevel.HIGH ->
                "Someone may be tracking your location. Consider enabling airplane " +
                "mode if you need to stop being located. Avoid patterns in your movement."
            else ->
                "Be aware that your location may have been pinged. A single occurrence " +
                "could be anomalous, but stay alert for additional silent messages."
        }

        val possibleCauses = buildList {
            add("Active location tracking via silent SMS ping")
            add("Law enforcement or intelligence location request")
            if (count == 1) add("Network diagnostic message (less likely)")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = true // Silent SMS is always concerning.
        )
    }

    /**
     * TRACKING_PATTERN — Location tracking indicators, including LAC oscillation
     * and BLE unwanted-tracker detection.
     *
     * While stationary, rapid LAC changes mean the network (or something
     * impersonating it) is repeatedly re-registering the device — a classic
     * triangulation technique. While driving, LAC changes are expected.
     *
     * Research enhancements:
     * - Apple-Google Joint Spec (May 2024): BLE tracker type identification
     * - AirCatch (arXiv 2602.07656, Feb 2026): device masquerading detection
     */
    private fun analyzeTrackingPattern(alert: Alert, details: JSONObject): AlertAnalysis {
        val isMoving = details.optBoolean("isMoving", false)
        val lacChanges = details.optInt("lacChanges", 0)

        // Apple-Google Joint Spec — BLE unwanted tracker detection.
        // The spec defines standardised advertisement patterns for AirTag,
        // SmartTag, Tile, and other DULT-compliant trackers.
        val bleTrackerType = details.optString("bleTrackerType", "").lowercase()
        val hasBleTracker = bleTrackerType.isNotEmpty()
        val durationMinutes = details.optInt("durationMinutes", 0)
        val locationCount = details.optInt("locationCount", 0)

        // Determine if this is primarily a BLE tracker alert or LAC-based.
        val isBleTrackerAlert = hasBleTracker

        var riskLevel = if (isBleTrackerAlert) {
            // BLE tracker risk assessment per Apple-Google spec.
            // >30 minutes at multiple locations = definitely separated & following.
            when {
                durationMinutes > 30 && locationCount > 1 -> RiskLevel.CRITICAL
                durationMinutes > 30 -> RiskLevel.HIGH
                durationMinutes > 10 -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            }
        } else {
            // LAC oscillation risk assessment.
            when {
                !isMoving && lacChanges >= 4 -> RiskLevel.CRITICAL
                !isMoving && lacChanges >= 2 -> RiskLevel.HIGH
                !isMoving -> RiskLevel.MEDIUM
                lacChanges >= 6 -> RiskLevel.MEDIUM  // Excessive even for driving
                else -> RiskLevel.LOW
            }
        }

        val shouldWorry = riskLevel >= RiskLevel.HIGH

        val trackerLabel = when (bleTrackerType) {
            "airtag"   -> "an Apple AirTag"
            "smarttag" -> "a Samsung SmartTag"
            "tile"     -> "a Tile tracker"
            "unknown"  -> "an unknown BLE tracker"
            else       -> "a BLE tracker"
        }

        val plainEnglish = if (isBleTrackerAlert) {
            when {
                riskLevel >= RiskLevel.CRITICAL ->
                    "A separated $trackerLabel has been following you for $durationMinutes " +
                    "minutes across $locationCount different locations. This is a strong " +
                    "indicator that someone planted a tracker on you or your belongings."
                riskLevel >= RiskLevel.HIGH ->
                    "A separated $trackerLabel has been near you for $durationMinutes " +
                    "minutes. This device appears to be travelling with you and may have " +
                    "been placed in your belongings without your knowledge."
                riskLevel >= RiskLevel.MEDIUM ->
                    "A separated $trackerLabel was detected nearby. It has been in range " +
                    "for $durationMinutes minutes. This could be someone else's lost item, " +
                    "but monitor whether it continues to follow you."
                else ->
                    "A $trackerLabel was briefly detected nearby. This is most likely a " +
                    "passing device and not a concern."
            }
        } else {
            when {
                !isMoving && riskLevel >= RiskLevel.HIGH ->
                    "Your phone is repeatedly re-registering with different network zones " +
                    "even though you haven't moved. This pattern is used to triangulate " +
                    "your exact location — someone may be actively tracking you."
                !isMoving ->
                    "Some unusual network zone switching was detected while you're stationary. " +
                    "This could indicate location tracking, but the pattern isn't conclusive yet."
                riskLevel >= RiskLevel.MEDIUM ->
                    "An unusually high number of network zone changes occurred while moving. " +
                    "While some handovers are normal during travel, this rate is elevated."
                else ->
                    "Network zone changes detected during movement. This is typical " +
                    "behaviour as your phone hands off between cell towers while in transit."
            }
        }

        val recommendation = if (isBleTrackerAlert) {
            when (riskLevel) {
                RiskLevel.CRITICAL ->
                    "A tracker is actively following you. Search your belongings, bag, and " +
                    "vehicle thoroughly. If found, disable or remove the battery. Consider " +
                    "contacting local authorities."
                RiskLevel.HIGH ->
                    "Check your belongings for an unwanted tracker. Look in bags, pockets, " +
                    "and vehicle compartments. If you find one, remove its battery."
                RiskLevel.MEDIUM ->
                    "Monitor whether this tracker continues to follow you. If it persists " +
                    "for more than 30 minutes, search your belongings."
                else ->
                    "This is likely a passing device. No action needed, but stay aware."
            }
        } else {
            when (riskLevel) {
                RiskLevel.CRITICAL ->
                    "Active tracking is likely. Enable airplane mode if you need to break " +
                    "the tracking. Avoid this location for sensitive activities."
                RiskLevel.HIGH ->
                    "Possible active tracking. Consider enabling airplane mode temporarily " +
                    "and moving to a different area to see if the pattern stops."
                RiskLevel.MEDIUM ->
                    "Monitor the situation. If you see this alert repeatedly while " +
                    "stationary, take it more seriously."
                else ->
                    "This is likely normal handover behaviour during travel. No action needed."
            }
        }

        val possibleCauses = buildList {
            if (isBleTrackerAlert) {
                add("Unwanted ${trackerLabel.removePrefix("a ").removePrefix("an ")} placed in your belongings")
                add("Someone else's lost tracker travelling near you")
                // AirCatch (2026) — device masquerading.
                add("Tracker may be masquerading as a legitimate device (AirCatch 2026 finding)")
            }
            if (!isMoving && !isBleTrackerAlert) {
                add("Active location triangulation via forced re-registration")
                add("Nearby IMSI catcher causing network oscillation")
            }
            if (!isBleTrackerAlert) {
                add("Normal cell tower handovers during movement")
                add("Network load balancing between sectors")
                add("Carrier network reconfiguration")
            }
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = shouldWorry
        )
    }

    /**
     * CIPHER_ANOMALY — encryption weakened or removed on the air interface.
     *
     * Null cipher (A5/0 or equivalent) = critical, the call is in the clear.
     * Downgrade to A5/1 (broken) = high. Anything else = medium.
     */
    private fun analyzeCipherAnomaly(alert: Alert, details: JSONObject): AlertAnalysis {
        val cipher = details.optString("cipher", "").uppercase()
        val previousCipher = details.optString("previousCipher", "").uppercase()

        val isNullCipher = cipher in listOf("A5/0", "A50", "NONE", "NULL", "")
        val isWeakCipher = cipher in listOf("A5/1", "A51")

        val riskLevel = when {
            isNullCipher -> RiskLevel.CRITICAL
            isWeakCipher -> RiskLevel.HIGH
            else -> RiskLevel.MEDIUM
        }

        val shouldWorry = riskLevel >= RiskLevel.HIGH

        val plainEnglish = when {
            isNullCipher ->
                "Your phone's connection has NO encryption. Every call, text, and data " +
                "packet is being transmitted in the open. Anyone with a radio receiver " +
                "can listen to your communications right now."
            isWeakCipher ->
                "Your phone's encryption was downgraded to a weak, broken algorithm " +
                "(${cipher}). A sophisticated attacker can decrypt your calls and " +
                "messages in real time."
            else ->
                "An unusual change in your phone's encryption settings was detected. " +
                "The current cipher ($cipher) may be weaker than expected" +
                if (previousCipher.isNotEmpty()) " (was $previousCipher)." else "."
        }

        val recommendation = when {
            isNullCipher ->
                "STOP all voice calls and standard SMS immediately. Your communications " +
                "are completely unprotected. Use only end-to-end encrypted apps (Signal, " +
                "WhatsApp) or wait until encryption is restored."
            isWeakCipher ->
                "Avoid voice calls and standard SMS for anything sensitive. Use " +
                "end-to-end encrypted messaging apps. This cipher can be broken " +
                "with commodity hardware."
            else ->
                "Prefer encrypted messaging apps for sensitive conversations. " +
                "Monitor for further encryption changes."
        }

        val possibleCauses = buildList {
            if (isNullCipher) {
                add("Active interception — someone is stripping encryption to eavesdrop")
                add("Misconfigured cell tower (rare but possible)")
            }
            if (isWeakCipher) {
                add("Deliberate cipher downgrade for surveillance")
                add("Legacy network infrastructure forcing weaker encryption")
            }
            add("Network configuration change during maintenance")
            add("Handover to older equipment with limited cipher support")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = shouldWorry
        )
    }

    /**
     * SIGNAL_ANOMALY — unexpected signal strength patterns.
     *
     * A brand-new tower appearing with very high signal is suspicious (portable
     * transmitter). General fluctuations are usually just the RF environment.
     */
    private fun analyzeSignalAnomaly(alert: Alert, details: JSONObject): AlertAnalysis {
        val isNewTower = details.optBoolean("isNewTower", false)
        val signalStrength = details.optInt("signalStrength", Int.MIN_VALUE)
        val isHighSignal = signalStrength != Int.MIN_VALUE && signalStrength > -60
        val signalDelta = details.optInt("signalDelta", 0)
        val isLargeFluctuation = kotlin.math.abs(signalDelta) > 20

        val riskLevel = when {
            isNewTower && isHighSignal -> RiskLevel.HIGH
            isNewTower -> RiskLevel.MEDIUM
            isLargeFluctuation && isHighSignal -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        val shouldWorry = riskLevel >= RiskLevel.HIGH

        val plainEnglish = when {
            isNewTower && isHighSignal ->
                "A previously unseen cell tower just appeared with an unusually strong " +
                "signal. New towers don't normally appear overnight with high power — " +
                "this could be a portable surveillance device."
            isNewTower ->
                "A new cell tower was detected in your area that wasn't seen before. " +
                "This is worth monitoring but may be legitimate new infrastructure."
            isLargeFluctuation ->
                "Unusual signal strength changes were detected. Large swings can " +
                "indicate interference or environmental changes, but are usually harmless."
            else ->
                "Minor signal variations were detected. This is typically caused by " +
                "normal environmental factors and is not a concern."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.HIGH ->
                "A suspicious new tower is nearby. Use encrypted communications and " +
                "consider moving to a different area to see if the tower follows you."
            RiskLevel.MEDIUM ->
                "Keep an eye on this. If the new tower persists or you see other " +
                "alerts (fake BTS, cipher changes), treat it more seriously."
            else ->
                "This is likely normal. No action needed."
        }

        val possibleCauses = buildList {
            if (isNewTower && isHighSignal) add("Portable surveillance device (IMSI catcher)")
            if (isNewTower) add("New legitimate cell tower installation")
            add("Environmental interference (buildings, weather)")
            add("Network load balancing adjustments")
            if (isLargeFluctuation) add("Physical obstruction or movement near tower")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = shouldWorry
        )
    }

    /**
     * NR_ANOMALY — 5G-specific anomalies, typically unexpected downgrades.
     *
     * Losing 5G in an urban area with known coverage is suspicious.
     * In rural/fringe areas, 5G drop to 4G is common and expected.
     *
     * Research enhancements:
     * - SMDFbs (Sensors 2023): RRC protocol state violation detection
     * - Forest Blizzard (April 2026): DNS hijacking context
     */
    private fun analyzeNrAnomaly(alert: Alert, details: JSONObject): AlertAnalysis {
        val isUrban = details.optBoolean("isUrban", true)
        val fromNr = details.optBoolean("from5G", true)
        val toNetwork = details.optString("toNetwork", "LTE")
        val isDowngradeToLegacy = toNetwork.uppercase() in listOf("GSM", "2G", "CDMA", "WCDMA", "3G")

        // SMDFbs (Sensors 2023) — RRC protocol state machine violation detection.
        // The 5G RRC state machine has well-defined transitions. Violations
        // (e.g., RRC_CONNECTED → RRC_IDLE without a proper RRCRelease) are
        // a strong indicator of a fake base station at 98% detection accuracy.
        val rrcStateViolation = details.optBoolean("rrcStateViolation", false)
        val stateTransitionAnomaly = details.optBoolean("stateTransitionAnomaly", false)

        // Forest Blizzard (April 2026) — DNS hijacking context.
        val dnsAnomaly = details.optBoolean("dnsAnomaly", false)

        var riskLevel = when {
            isUrban && isDowngradeToLegacy -> RiskLevel.HIGH
            isUrban && fromNr -> RiskLevel.MEDIUM
            isDowngradeToLegacy -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // SMDFbs: RRC state violation immediately escalates to at least HIGH.
        if (rrcStateViolation && riskLevel < RiskLevel.HIGH) {
            riskLevel = RiskLevel.HIGH
        }

        val shouldWorry = riskLevel >= RiskLevel.HIGH

        var plainEnglish = when {
            isUrban && isDowngradeToLegacy ->
                "Your 5G connection was dropped all the way to $toNetwork in an area " +
                "with good 5G coverage. Skipping past 4G to a legacy network is highly " +
                "unusual and may indicate forced downgrade for surveillance."
            isUrban && fromNr ->
                "Your 5G connection dropped to $toNetwork in an area with known 5G " +
                "coverage. While brief drops can happen, this is worth watching."
            isDowngradeToLegacy ->
                "Your connection fell back from 5G to $toNetwork. In areas with " +
                "limited 5G this can happen, but the jump to a legacy network is notable."
            else ->
                "Your 5G connection briefly dropped to $toNetwork. In your current area " +
                "this is likely a normal coverage transition."
        }

        // SMDFbs: append RRC violation context.
        if (rrcStateViolation) {
            plainEnglish += " The 5G protocol state machine detected an illegal state " +
                "transition — this is a strong indicator of a fake base station."
        }
        if (stateTransitionAnomaly) {
            plainEnglish += " An unexpected RRC state transition was observed (e.g., " +
                "connected → idle without proper release), which deviates from the " +
                "5G specification."
        }

        if (dnsAnomaly) {
            plainEnglish += " A DNS resolution anomaly was also detected, which may " +
                "indicate network-level tampering."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.HIGH ->
                "Avoid sensitive calls — your connection's security has been significantly " +
                "weakened. Use encrypted messaging apps and monitor for additional alerts."
            RiskLevel.MEDIUM ->
                "Monitor the situation. If your connection doesn't return to 5G soon " +
                "or you see other security alerts, take precautions."
            else ->
                "This is likely a normal 5G coverage gap. No action needed."
        }

        val possibleCauses = buildList {
            if (isUrban && isDowngradeToLegacy) {
                add("Forced network downgrade for surveillance purposes")
            }
            if (rrcStateViolation || stateTransitionAnomaly) {
                add("5G RRC protocol state violation consistent with fake base station (SMDFbs pattern)")
            }
            if (dnsAnomaly) {
                add("DNS hijacking similar to recent state-sponsored campaigns targeting consumer routers")
            }
            add("5G signal obstruction or congestion")
            add("Carrier network maintenance or reconfiguration")
            add("Coverage gap at cell edge")
            if (!isUrban) add("Limited 5G deployment in this area")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = shouldWorry
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Multi-alert correlation (analyzeSituation helpers)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Known attack chain definitions, derived from FBSDetector (Purdue 2025)
     * and real-world surveillance tradecraft.
     *
     * Each chain is a set of [ThreatType]s that, when observed together,
     * indicate a specific coordinated attack rather than coincidental events.
     */
    private data class AttackChain(
        val name: String,
        val requiredTypes: Set<ThreatType>,
        val description: String
    )

    private val knownAttackChains = listOf(
        // FBSDetector (Purdue 2025): classic multi-step interception chain.
        AttackChain(
            name = "Classic IMSI catcher interception chain",
            requiredTypes = setOf(ThreatType.FAKE_BTS, ThreatType.NETWORK_DOWNGRADE, ThreatType.CIPHER_ANOMALY),
            description = "Multiple indicators form a coordinated attack chain: fake tower → " +
                "encryption strip → interception. This is a textbook multi-step cellular attack."
        ),
        // Tower-based tracking: new suspicious tower → fake BTS confirmed → tracking.
        AttackChain(
            name = "Tower-based tracking operation",
            requiredTypes = setOf(ThreatType.SIGNAL_ANOMALY, ThreatType.FAKE_BTS, ThreatType.TRACKING_PATTERN),
            description = "A suspicious new tower appeared, was identified as a fake base station, " +
                "and tracking patterns were detected. This indicates a tower-based tracking operation."
        ),
        // Shaik/Rupprecht: downgrade then ping to confirm location.
        AttackChain(
            name = "Location confirmation after encryption strip",
            requiredTypes = setOf(ThreatType.NETWORK_DOWNGRADE, ThreatType.SILENT_SMS),
            description = "Your connection was downgraded to remove encryption, then invisible " +
                "messages were sent to confirm your location. This is a deliberate two-step attack."
        )
    )

    /**
     * Detect which known attack chains are present in the current alert set.
     *
     * @return List of matched [AttackChain]s, empty if no chains detected.
     */
    private fun detectAttackChains(threatTypes: Set<ThreatType>): List<AttackChain> {
        return knownAttackChains.filter { chain ->
            threatTypes.containsAll(chain.requiredTypes)
        }
    }

    /**
     * Detect deliberate downgrade attack by cross-referencing NETWORK_DOWNGRADE
     * and CIPHER_ANOMALY alerts.
     *
     * Based on Shaik et al. (NDSS 2016) + Rupprecht et al. (IEEE S&P 2019):
     * if a downgrade from 5G/LTE to 2G occurs concurrently with a cipher anomaly,
     * the downgrade is almost certainly deliberate rather than a coverage gap.
     */
    private fun detectDeliberateDowngrade(alerts: List<Alert>): Boolean {
        val downgradeAlerts = alerts.filter { it.threatType == ThreatType.NETWORK_DOWNGRADE }
        val hasCipherAnomaly = alerts.any { it.threatType == ThreatType.CIPHER_ANOMALY }

        if (!hasCipherAnomaly) return false

        return downgradeAlerts.any { alert ->
            val details = alert.detailsJson.parseJsonSafe()
            val fromNetwork = details.optString("fromNetwork", "").uppercase()
            val toNetwork = details.optString("toNetwork", "").uppercase()
            val fromModern = fromNetwork in listOf("5G", "NR", "LTE", "4G")
            val toLegacy = toNetwork in listOf("GSM", "2G", "CDMA")
            fromModern && toLegacy
        }
    }

    /**
     * Detect temporal correlation: multiple different threat types within a
     * narrow time window (5 minutes) suggest coordination rather than coincidence.
     */
    private fun detectTemporalCorrelation(alerts: List<Alert>): Boolean {
        if (alerts.size < 2) return false

        // Group by threat type — we need at least 2 different types.
        val distinctTypes = alerts.map { it.threatType }.distinct()
        if (distinctTypes.size < 2) return false

        // Check if any two alerts of different types are within the time window.
        val sorted = alerts.sortedBy { it.timestamp }
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                if (sorted[i].threatType != sorted[j].threatType &&
                    sorted[j].timestamp - sorted[i].timestamp <= TEMPORAL_WINDOW_MS) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Detect geographic persistence: multiple alerts occurring at the same
     * cell ID, suggesting a persistent threat at a specific location rather
     * than a transient event.
     */
    private fun detectGeographicPersistence(alerts: List<Alert>): Boolean {
        if (alerts.size < 2) return false

        // Count alerts per cell ID (excluding null).
        val cellCounts = alerts
            .mapNotNull { it.cellId }
            .groupingBy { it }
            .eachCount()

        // 2+ alerts at the same cell = geographic persistence.
        return cellCounts.values.any { it >= 2 }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Situation brief helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Build the top concerns list, incorporating attack-chain and correlation
     * insights alongside individual alert analyses.
     */
    private fun buildTopConcerns(
        analyses: List<AlertAnalysis>,
        detectedChains: List<AttackChain>,
        hasDeliberateDowngrade: Boolean,
        temporalCorrelation: Boolean,
        geoPersistence: Boolean
    ): List<String> {
        val concerns = mutableListOf<String>()

        // Attack chains are the most important — lead with them.
        for (chain in detectedChains) {
            concerns.add(chain.description)
        }

        // Deliberate downgrade cross-reference.
        if (hasDeliberateDowngrade && detectedChains.none {
                it.requiredTypes.contains(ThreatType.NETWORK_DOWNGRADE) &&
                it.requiredTypes.contains(ThreatType.CIPHER_ANOMALY)
            }) {
            concerns.add(
                "Network downgrade from a modern network to 2G occurred alongside an " +
                "encryption anomaly — this is almost certainly a deliberate attack to " +
                "strip your connection's security."
            )
        }

        // Temporal correlation.
        if (temporalCorrelation) {
            concerns.add(
                "Multiple threat indicators detected within a narrow time window — " +
                "this suggests a coordinated rather than coincidental event."
            )
        }

        // Geographic persistence.
        if (geoPersistence) {
            concerns.add(
                "Repeated alerts at this location suggest a persistent threat rather " +
                "than a transient one."
            )
        }

        // Individual alert analyses, worst first, deduplicated.
        analyses
            .sortedByDescending { it.riskLevel }
            .map { it.plainEnglish }
            .distinct()
            .forEach { if (it !in concerns) concerns.add(it) }

        return concerns.take(MAX_TOP_CONCERNS)
    }

    /**
     * Build a concise executive summary from all current alerts and context.
     */
    private fun buildSituationSummary(
        alerts: List<Alert>,
        analyses: List<AlertAnalysis>,
        highestRisk: RiskLevel,
        cellInfo: CellTower?,
        isMoving: Boolean,
        detectedChains: List<AttackChain>,
        hasDeliberateDowngrade: Boolean,
        temporalCorrelation: Boolean,
        geoPersistence: Boolean
    ): String {
        val alertCount = alerts.size
        val worrySomeCount = analyses.count { it.shouldWorry }
        val threatTypes = alerts.map { it.threatType }.distinct()

        val motionContext = if (isMoving) "while in transit" else "at your current location"

        return when {
            highestRisk == RiskLevel.CRITICAL -> buildString {
                append("CRITICAL SITUATION: ")
                append("$alertCount active alert${alertCount.plural()} detected $motionContext. ")
                append("$worrySomeCount require${if (worrySomeCount == 1) "s" else ""} immediate attention. ")

                // Name the attack chain explicitly if detected.
                if (detectedChains.isNotEmpty()) {
                    val chainNames = detectedChains.joinToString("; ") { it.name }
                    append("Attack chain identified: $chainNames. ")
                } else if (threatTypes.size > 1) {
                    append("Multiple attack indicators present — this may be a coordinated effort. ")
                }

                if (hasDeliberateDowngrade) {
                    append("A deliberate encryption-stripping downgrade was detected. ")
                }

                if (temporalCorrelation) {
                    append("These alerts occurred within a narrow time window, reinforcing " +
                        "the assessment of a coordinated attack. ")
                }

                if (geoPersistence) {
                    append("This location has seen repeated alerts — the threat appears persistent. ")
                }

                append("Take protective action now.")
            }
            highestRisk == RiskLevel.HIGH -> buildString {
                append("HIGH CONCERN: ")
                append("$alertCount alert${alertCount.plural()} detected $motionContext")
                if (worrySomeCount > 0) {
                    append(", $worrySomeCount warranting concern")
                }
                append(". ")

                if (temporalCorrelation) {
                    append("Multiple indicators arrived in a narrow time window. ")
                }
                if (geoPersistence) {
                    append("Repeated alerts at this location suggest a persistent threat. ")
                }

                append("Precautionary measures recommended.")
            }
            highestRisk == RiskLevel.MEDIUM -> buildString {
                append("$alertCount alert${alertCount.plural()} detected $motionContext. ")
                append("Nothing requires immediate action, but the situation warrants monitoring. ")
                if (geoPersistence) {
                    append("Note: alerts have recurred at this location. ")
                }
                if (cellInfo != null) {
                    append("Connected to ${cellInfo.networkType.generation} (cell ${cellInfo.cid}).")
                }
            }
            else -> buildString {
                append("$alertCount minor alert${alertCount.plural()} detected $motionContext. ")
                append("All appear consistent with normal network behaviour.")
            }
        }
    }

    /**
     * Produce a deduplicated, prioritised list of recommendations.
     */
    private fun buildRecommendations(
        analyses: List<AlertAnalysis>,
        highestRisk: RiskLevel,
        isMoving: Boolean,
        detectedChains: List<AttackChain>,
        hasDeliberateDowngrade: Boolean
    ): List<String> {
        val recs = mutableListOf<String>()

        // Attack-chain-specific top recommendation.
        if (detectedChains.isNotEmpty() || hasDeliberateDowngrade) {
            recs.add(
                "You are likely under active surveillance. Switch to airplane mode, then use " +
                "Wi-Fi with a VPN for any essential communications via Signal or WhatsApp only."
            )
        }

        // Add individual recommendations from worst to least.
        analyses
            .sortedByDescending { it.riskLevel }
            .forEach { analysis ->
                if (analysis.recommendation !in recs) {
                    recs.add(analysis.recommendation)
                }
            }

        // Universal recommendations based on overall risk.
        if (highestRisk >= RiskLevel.HIGH) {
            val encrypted = "Use end-to-end encrypted apps (Signal, WhatsApp) for all communications."
            if (encrypted !in recs) recs.add(0.coerceAtMost(recs.size), encrypted)
        }
        if (highestRisk >= RiskLevel.CRITICAL && !isMoving) {
            val relocate = "Consider relocating — move at least several blocks and check if alerts clear."
            if (relocate !in recs) recs.add(1.coerceAtMost(recs.size), relocate)
        }

        return recs.take(MAX_RECOMMENDATIONS)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Utilities
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Convert the raw detector [Confidence] and contextual [RiskLevel] into
     * a single 0-1 float for the analyst assessment.
     */
    // -----------------------------------------------------------------
    // REGISTRATION_FAILURE analysis
    // -----------------------------------------------------------------

    private fun analyzeRegistrationFailure(alert: Alert, details: JSONObject): AlertAnalysis {
        val isSynchFailure = details.optBoolean("synch_failure", false) ||
            details.optBoolean("auth_reject", false)
        val failureCount = details.optInt("failure_count", 1)

        val riskLevel = when {
            isSynchFailure -> RiskLevel.CRITICAL
            failureCount >= 3 -> RiskLevel.HIGH
            else -> RiskLevel.MEDIUM
        }

        val plainEnglish = if (isSynchFailure) {
            "Your phone failed mutual authentication with a cell tower — the tower " +
            "could not prove it belongs to your carrier. This is a strong indicator " +
            "of a fake base station. Legitimate towers never fail authentication."
        } else {
            "Your phone experienced $failureCount registration failure(s) with a " +
            "nearby cell tower. While occasional failures happen during handovers, " +
            "repeated failures can indicate a device trying to harvest your phone's identity."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.CRITICAL ->
                "Authentication failure is near-certain evidence of a fake tower. " +
                "Switch to airplane mode, move away, and use WiFi calling if available."
            RiskLevel.HIGH ->
                "Multiple registration failures are suspicious. Move to a different " +
                "area and monitor if failures persist."
            else ->
                "A single failure may be transient. Watch for additional alerts."
        }

        val possibleCauses = buildList {
            if (isSynchFailure) add("IMSI catcher / fake base station (near-certain)")
            add("Network congestion or maintenance")
            add("SIM card issue")
            if (failureCount >= 3) add("Persistent fake tower forcing identity exposure")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = riskLevel >= RiskLevel.HIGH
        )
    }

    // -----------------------------------------------------------------
    // TEMPORAL_ANOMALY analysis
    // -----------------------------------------------------------------

    private fun analyzeTemporalAnomaly(alert: Alert, details: JSONObject): AlertAnalysis {
        val hasTransient = details.has("transient_towers") || details.has("transient_tower")
        val hasCellCycling = details.has("cell_cycling") || details.has("cell_cycling_stationary")
        val hasSignalInstability = details.has("signal_instability")

        val riskLevel = when {
            hasTransient && hasCellCycling -> RiskLevel.HIGH
            hasTransient -> RiskLevel.HIGH
            hasCellCycling -> RiskLevel.MEDIUM
            hasSignalInstability -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        val plainEnglish = when {
            hasTransient ->
                "A cell tower appeared briefly and then disappeared. Real towers are " +
                "permanent infrastructure — they don't vanish. This is consistent with " +
                "a portable or vehicle-mounted surveillance device."
            hasCellCycling ->
                "Your phone is rapidly switching between cell towers even though you're " +
                "not moving. This can happen when a device is trying to force your " +
                "phone to connect to it by disrupting your current connection."
            hasSignalInstability ->
                "Unusual signal instability detected — the kind typically produced by " +
                "SDR (software-defined radio) hardware rather than carrier-grade " +
                "base station equipment."
            else ->
                "Minor timing irregularities were observed in cell tower behavior. " +
                "This is usually caused by normal network operations."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.HIGH ->
                "A transient tower is a strong indicator of a portable IMSI catcher. " +
                "Use encrypted communications and consider moving to a different area."
            RiskLevel.MEDIUM ->
                "Monitor the situation. If cell cycling continues or other alerts " +
                "appear, take it more seriously."
            else ->
                "Likely normal network behavior. No action needed."
        }

        val possibleCauses = buildList {
            if (hasTransient) add("Portable IMSI catcher (van/backpack/drone-mounted)")
            if (hasTransient) add("Temporary cell on wheels for event coverage")
            if (hasCellCycling) add("Forced cell reselection attack")
            if (hasCellCycling) add("Network congestion or tower maintenance")
            if (hasSignalInstability) add("SDR-based fake base station")
            if (hasSignalInstability) add("Environmental interference")
            add("Normal network optimization")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = riskLevel >= RiskLevel.HIGH
        )
    }

    // -----------------------------------------------------------------
    // KNOWN_TOWER_ANOMALY analysis (behavioral baseline detector)
    // -----------------------------------------------------------------

    private fun analyzeKnownTowerAnomaly(alert: Alert, details: JSONObject): AlertAnalysis {
        val summary = alert.summary
        val hasDuplicate = details.keys().asSequence().any {
            it.startsWith("duplicate_cid_pci_")
        }
        val hasBandChange = details.keys().asSequence().any {
            it.startsWith("band_change_")
        }
        val hasTacChange = details.keys().asSequence().any {
            it.startsWith("tac_change_")
        }
        val hasGeoDisplacement = details.keys().asSequence().any {
            it.startsWith("geo_displacement_")
        }
        val hasSignalAnomaly = details.keys().asSequence().any {
            it.startsWith("signal_anomaly_")
        }
        val hasNeighborMismatch = details.keys().asSequence().any {
            it.startsWith("neighbor_mismatch_")
        }

        val riskLevel = when {
            hasDuplicate -> RiskLevel.CRITICAL
            hasBandChange && hasTacChange -> RiskLevel.HIGH
            hasBandChange || hasTacChange -> RiskLevel.HIGH
            hasGeoDisplacement && hasSignalAnomaly -> RiskLevel.HIGH
            hasGeoDisplacement -> RiskLevel.MEDIUM
            hasNeighborMismatch -> RiskLevel.MEDIUM
            hasSignalAnomaly -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        val shouldWorry = riskLevel >= RiskLevel.HIGH

        val plainEnglish = when {
            hasDuplicate ->
                "Two transmitters are broadcasting the same Cell ID with different " +
                "Physical Cell IDs. This is definitive evidence of cell identity " +
                "spoofing — someone has cloned a real tower's identity. This is " +
                "the most sophisticated form of IMSI catcher attack."
            hasBandChange ->
                "A tower you've connected to many times before is suddenly broadcasting " +
                "on a different frequency band. Real cell towers are physically tuned " +
                "to specific frequencies and don't change them. Someone is likely " +
                "spoofing this tower's identity from a different device."
            hasTacChange ->
                "A known cell tower has appeared under a different Tracking Area Code. " +
                "Carriers don't reassign TACs for existing towers — this is strong " +
                "evidence that a fake transmitter is broadcasting this tower's identity."
            hasGeoDisplacement && hasSignalAnomaly ->
                "A known tower is producing an abnormally strong signal AND appears " +
                "to be much closer than its actual position. This combination strongly " +
                "suggests a nearby device is spoofing this tower's identity."
            hasGeoDisplacement ->
                "A known tower's signal suggests it's much closer than its actual " +
                "registered position. This could indicate a nearby fake transmitter " +
                "broadcasting the same Cell ID."
            hasNeighborMismatch ->
                "A known tower is reporting a completely different set of neighbor " +
                "cells than usual. A spoofer typically doesn't know the real tower's " +
                "neighbor configuration, so this is a telltale sign of impersonation."
            hasSignalAnomaly ->
                "A known tower is producing signal levels significantly outside its " +
                "learned baseline. While this can occasionally be caused by " +
                "environmental changes, a large deviation warrants monitoring."
            else ->
                "Behavioral changes were detected on a tower in your area that " +
                "you've previously connected to. The changes are unusual for " +
                "legitimate infrastructure."
        }

        val recommendation = when (riskLevel) {
            RiskLevel.CRITICAL ->
                "This is near-certain spoofing. Switch to airplane mode immediately, " +
                "use WiFi for communications, and leave the area if possible. Report " +
                "this detection — it is strong evidence of active surveillance."
            RiskLevel.HIGH ->
                "This is highly suspicious. Use encrypted messaging apps only, avoid " +
                "making phone calls, and monitor whether this anomaly persists. If " +
                "it follows you as you move, it's almost certainly targeting you."
            RiskLevel.MEDIUM ->
                "Monitor this situation. If you see multiple anomaly types appear " +
                "simultaneously, treat it as a high-priority alert. Use encrypted " +
                "communications as a precaution."
            else ->
                "This is likely benign. No immediate action needed, but the system " +
                "will continue monitoring for escalation."
        }

        val possibleCauses = buildList {
            if (hasDuplicate) add("Active cell-identity spoofing / IMSI catcher clone attack")
            if (hasBandChange) add("Fake transmitter using wrong frequency for this Cell ID")
            if (hasTacChange) add("Fake transmitter using wrong Tracking Area Code")
            if (hasGeoDisplacement) add("Nearby portable fake base station spoofing a distant tower")
            if (hasSignalAnomaly) add("Nearby high-power transmitter impersonating known tower")
            if (hasNeighborMismatch) add("Fake transmitter lacking knowledge of real tower's neighbors")
            add("Carrier network maintenance or reconfiguration (rare but possible)")
            add("Temporary cell-on-wheels for event coverage")
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidenceToFloat(alert.confidence, riskLevel),
            possibleCauses = possibleCauses,
            shouldWorry = shouldWorry
        )
    }

    // -----------------------------------------------------------------
    // COMPOUND_PATTERN analysis (fusion layer output)
    // -----------------------------------------------------------------

    private fun analyzeCompoundPattern(alert: Alert, details: JSONObject): AlertAnalysis {
        val patternName = details.optString("compound_pattern", "Unknown")
        val confidence = details.optString("compound_confidence", "0.5").toDoubleOrNull() ?: 0.5
        val matchedIndicators = details.optString("matched_indicators", "")

        val riskLevel = when {
            confidence >= 0.85 -> RiskLevel.CRITICAL
            confidence >= 0.65 -> RiskLevel.HIGH
            else -> RiskLevel.MEDIUM
        }

        val plainEnglish = when (patternName) {
            "Classic Stingray" ->
                "Multiple detection systems triggered simultaneously in a pattern that " +
                "matches a known IMSI catcher signature: fake tower identity, overpowered " +
                "signal, network downgrade, and missing neighbor information. This is the " +
                "textbook signature of a Stingray-type surveillance device."
            "Silent Interception" ->
                "A tower mimicking a real cell was detected with cipher downgrade for " +
                "silent call interception. This is a sophisticated attack where the " +
                "attacker clones a legitimate tower's identity to avoid basic detection."
            "Tracking-Only" ->
                "A pattern consistent with identity harvesting was detected — your phone " +
                "is being repeatedly asked to identify itself without any interception. " +
                "Someone may be conducting mass surveillance to track who is in this area."
            "Mobile IMSI Catcher" ->
                "A transient cell tower with shifting characteristics was detected, " +
                "consistent with a vehicle-mounted or drone-based surveillance device " +
                "moving through the area."
            else -> alert.summary
        }

        val recommendation = when (riskLevel) {
            RiskLevel.CRITICAL ->
                "HIGH CONFIDENCE: Multiple independent sensors confirm a surveillance device " +
                "is operating nearby. Enable airplane mode immediately, move to a different " +
                "location, and use WiFi with VPN for communications."
            RiskLevel.HIGH ->
                "Strong evidence of a surveillance device. Use encrypted communications " +
                "(Signal, WhatsApp) and avoid sensitive calls/texts. Move if possible."
            else ->
                "Suspicious pattern detected. Stay alert and monitor for additional indicators."
        }

        val possibleCauses = buildList {
            when (patternName) {
                "Classic Stingray" -> {
                    add("Law enforcement cell-site simulator (Stingray/Hailstorm)")
                    add("Foreign intelligence IMSI catcher")
                }
                "Silent Interception" -> {
                    add("Sophisticated evil-twin IMSI catcher")
                    add("Advanced law enforcement interception device")
                }
                "Tracking-Only" -> {
                    add("Mass surveillance / identity harvesting device")
                    add("Foreign intelligence location tracking")
                }
                "Mobile IMSI Catcher" -> {
                    add("Vehicle-mounted Stingray (surveillance van)")
                    add("Airborne DRTbox / Dirtbox (aircraft/drone)")
                    add("LEER-3 type military EW system")
                }
            }
        }

        return AlertAnalysis(
            plainEnglish = plainEnglish,
            riskLevel = riskLevel,
            recommendation = recommendation,
            confidence = confidence.toFloat().coerceIn(0.0f, 1.0f),
            possibleCauses = possibleCauses,
            shouldWorry = riskLevel >= RiskLevel.HIGH
        )
    }

    private fun confidenceToFloat(confidence: Confidence, risk: RiskLevel): Float {
        val base = when (confidence) {
            Confidence.LOW -> 0.3f
            Confidence.MEDIUM -> 0.6f
            Confidence.HIGH -> 0.85f
        }
        // Boost slightly when risk context aligns with high confidence.
        return when {
            risk >= RiskLevel.HIGH && confidence == Confidence.HIGH -> (base + 0.1f).coerceAtMost(1.0f)
            risk <= RiskLevel.LOW && confidence == Confidence.LOW -> (base - 0.1f).coerceAtLeast(0.0f)
            else -> base
        }
    }

    /** Pluralise helper — returns "s" for counts != 1. */
    private fun Int.plural(): String = if (this == 1) "" else "s"

    /** Safely parse a JSON string, returning an empty object on failure. */
    private fun String.parseJsonSafe(): JSONObject {
        return try {
            JSONObject(this)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    companion object {
        /** Maximum number of top concerns to include in a situation brief. */
        private const val MAX_TOP_CONCERNS = 5

        /** Maximum number of recommendations in a situation brief. */
        private const val MAX_RECOMMENDATIONS = 6

        /**
         * Temporal correlation window: alerts of different types within this
         * window (5 minutes) are considered potentially coordinated.
         */
        private const val TEMPORAL_WINDOW_MS = 5 * 60 * 1000L
    }
}
