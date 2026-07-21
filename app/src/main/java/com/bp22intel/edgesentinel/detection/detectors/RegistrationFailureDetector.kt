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

import android.content.Context
import android.os.Build
import android.telephony.CellIdentity
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.ThreatType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects fake cell towers via registration/authentication failures.
 *
 * This is the **single strongest no-root indicator** of a fake base station.
 * On Android 12+ (API 31), `TelephonyCallback.onRegistrationFailed` reports
 * when the device fails to register with a cell, along with the 3GPP cause
 * code.
 *
 * Key signals:
 * - **Cause 21 (SYNCH_FAILURE):** The phone attempted mutual authentication
 *   (AKA) and the network failed. A real carrier always passes AKA; only a
 *   fake tower without access to the HSS/UDM authentication keys will fail.
 * - **Cause 6 (ILLEGAL_ME):** Network rejected the device — may indicate an
 *   IMSI catcher that rejects after harvesting the IMEI.
 * - **Cause 3 (ILLEGAL_UE):** Network rejected the subscriber identity.
 * - **Rapid registration/deregistration oscillation:** The phone keeps
 *   connecting and failing, suggesting a nearby rogue tower that the modem
 *   keeps attempting to use.
 *
 * On Android < 12, this detector gracefully degrades and returns null.
 *
 * References:
 * - 3GPP TS 24.301 § 9.9.3.9 — EMM cause codes
 * - EFF Rayhunter — SYNCH_FAILURE heuristic
 * - Tucker et al., NDSS 2025 — identity-exposing message analysis
 */
@Singleton
class RegistrationFailureDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : ThreatDetector {

    override val type: ThreatType = ThreatType.REGISTRATION_FAILURE

    // -----------------------------------------------------------------
    // Callback registration state
    // -----------------------------------------------------------------

    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var callbackRegistered = false

    // -----------------------------------------------------------------
    // Ring buffer for failure events (last 100)
    // -----------------------------------------------------------------

    data class RegistrationFailureEvent(
        val timestampMs: Long,
        val causeCode: Int,
        val additionalCauseCode: Int,
        val domain: Int,
        val chosenPlmn: String,
        /** CID extracted from the failing CellIdentity, or null if unavailable. */
        val cellId: Int?,
        /** TAC/LAC extracted from the failing CellIdentity, or null. */
        val tacLac: Int?,
        /** MCC from the failing cell, or null. */
        val mcc: Int?,
        /** MNC from the failing cell, or null. */
        val mnc: Int?
    )

    private val events = ArrayDeque<RegistrationFailureEvent>(BUFFER_CAPACITY)
    private val eventsLock = Any()

    // -----------------------------------------------------------------
    // Service state oscillation tracking
    // -----------------------------------------------------------------

    data class ServiceStateTransition(
        val timestampMs: Long,
        val newState: Int // ServiceState.STATE_*
    )

    private val serviceTransitions = ArrayDeque<ServiceStateTransition>(BUFFER_CAPACITY)
    private val transitionsLock = Any()

    // -----------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------

    companion object {
        private const val BUFFER_CAPACITY = 100

        /** Events older than this are ignored during analysis. */
        private const val ANALYSIS_WINDOW_MS = 5L * 60 * 1_000  // 5 minutes

        /** Window for oscillation detection. */
        private const val OSCILLATION_WINDOW_MS = 2L * 60 * 1_000  // 2 minutes

        // 3GPP TS 24.301 / TS 24.008 EMM/GMM cause codes
        const val CAUSE_IMSI_UNKNOWN = 2
        const val CAUSE_ILLEGAL_UE = 3
        const val CAUSE_IMEI_NOT_ACCEPTED = 5
        const val CAUSE_ILLEGAL_ME = 6
        const val CAUSE_EPS_NOT_ALLOWED = 7
        const val CAUSE_PLMN_NOT_ALLOWED = 11
        const val CAUSE_LA_NOT_ALLOWED = 12
        const val CAUSE_NETWORK_FAILURE = 17
        const val CAUSE_SYNCH_FAILURE = 21
        const val CAUSE_CONGESTION = 22
        const val CAUSE_PROTOCOL_ERROR = 111

        /** Cause codes strongly associated with fake towers. */
        private val HIGH_SUSPICION_CAUSES = setOf(
            CAUSE_SYNCH_FAILURE,   // Authentication failed — the smoking gun
            CAUSE_ILLEGAL_UE,      // Rejected subscriber — may be post-IMSI-harvest
            CAUSE_ILLEGAL_ME,      // Rejected device — may be post-IMEI-harvest
            CAUSE_IMEI_NOT_ACCEPTED
        )

        /** Cause codes mildly suspicious in context. */
        private val MODERATE_SUSPICION_CAUSES = setOf(
            CAUSE_IMSI_UNKNOWN,
            CAUSE_EPS_NOT_ALLOWED,
            CAUSE_PLMN_NOT_ALLOWED,
            CAUSE_LA_NOT_ALLOWED,
            CAUSE_NETWORK_FAILURE
        )

        /** Number of service-state oscillations in the window that indicates trouble. */
        private const val OSCILLATION_THRESHOLD = 4

        /** Number of distinct failures in window that's suspicious even without cause 21. */
        private const val FAILURE_BURST_THRESHOLD = 3
    }

    // -----------------------------------------------------------------
    // Initialization — register callbacks on API 31+
    // -----------------------------------------------------------------

    init {
        registerCallbacksIfSupported()
    }

    private fun registerCallbacksIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !callbackRegistered) {
            try {
                registerApi31Callbacks()
                callbackRegistered = true
            } catch (_: SecurityException) {
                // Missing READ_PHONE_STATE — callback can't be registered yet.
                // Will be retried on next analyze() call.
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31Callbacks() {
        val callback = object : TelephonyCallback(),
            TelephonyCallback.RegistrationFailedListener,
            TelephonyCallback.ServiceStateListener {

            override fun onRegistrationFailed(
                cellIdentity: CellIdentity,
                chosenPlmn: String,
                domain: Int,
                causeCode: Int,
                additionalCauseCode: Int
            ) {
                val (cid, tacLac, mcc, mnc) = extractCellFields(cellIdentity)
                val event = RegistrationFailureEvent(
                    timestampMs = System.currentTimeMillis(),
                    causeCode = causeCode,
                    additionalCauseCode = additionalCauseCode,
                    domain = domain,
                    chosenPlmn = chosenPlmn,
                    cellId = cid,
                    tacLac = tacLac,
                    mcc = mcc,
                    mnc = mnc
                )
                synchronized(eventsLock) {
                    if (events.size >= BUFFER_CAPACITY) events.removeFirst()
                    events.addLast(event)
                }
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                val transition = ServiceStateTransition(
                    timestampMs = System.currentTimeMillis(),
                    newState = serviceState.state
                )
                synchronized(transitionsLock) {
                    if (serviceTransitions.size >= BUFFER_CAPACITY) serviceTransitions.removeFirst()
                    serviceTransitions.addLast(transition)
                }
            }
        }

        telephonyManager.registerTelephonyCallback(callbackExecutor, callback)
    }

    // -----------------------------------------------------------------
    // ThreatDetector implementation
    // -----------------------------------------------------------------

    override suspend fun analyze(
        cells: List<CellTower>,
        history: List<CellTower>
    ): DetectionResult? {
        // Graceful degradation: no callback support below API 31
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

        // Retry callback registration if it failed during init
        if (!callbackRegistered) {
            registerCallbacksIfSupported()
        }

        val now = System.currentTimeMillis()
        val cutoff = now - ANALYSIS_WINDOW_MS
        val oscillationCutoff = now - OSCILLATION_WINDOW_MS

        // Snapshot recent events
        val recentEvents: List<RegistrationFailureEvent>
        synchronized(eventsLock) {
            recentEvents = events.filter { it.timestampMs >= cutoff }
        }

        val recentTransitions: List<ServiceStateTransition>
        synchronized(transitionsLock) {
            recentTransitions = serviceTransitions.filter { it.timestampMs >= oscillationCutoff }
        }

        if (recentEvents.isEmpty() && recentTransitions.size < OSCILLATION_THRESHOLD) {
            return null
        }

        val indicators = mutableMapOf<String, String>()
        var score = 0.0

        // ---- Check 1: SYNCH_FAILURE (cause 21) — the smoking gun ----
        val synchFailures = recentEvents.filter { it.causeCode == CAUSE_SYNCH_FAILURE }
        if (synchFailures.isNotEmpty()) {
            val contribution = 4.0  // Very high — this is the #1 indicator
            score += contribution
            val latest = synchFailures.maxBy { it.timestampMs }
            val cellDesc = describeCellId(latest)
            indicators["synch_failure"] =
                "Authentication SYNCH_FAILURE (cause 21) detected ${synchFailures.size} time(s) " +
                "in last 5 min$cellDesc — network failed mutual authentication (AKA)"
        }

        // ---- Check 2: Other high-suspicion cause codes ----
        val highSuspicionEvents = recentEvents.filter {
            it.causeCode in HIGH_SUSPICION_CAUSES && it.causeCode != CAUSE_SYNCH_FAILURE
        }
        for (event in highSuspicionEvents) {
            val key = "reg_reject_cause_${event.causeCode}"
            if (key !in indicators) {
                val count = recentEvents.count { it.causeCode == event.causeCode }
                val contribution = 2.0
                score += contribution
                indicators[key] =
                    "Registration rejected with cause ${event.causeCode} " +
                    "(${describeCauseCode(event.causeCode)}) — $count occurrence(s)${describeCellId(event)}"
            }
        }

        // ---- Check 3: Moderate-suspicion causes in bursts ----
        val moderateEvents = recentEvents.filter { it.causeCode in MODERATE_SUSPICION_CAUSES }
        if (moderateEvents.size >= 2) {
            val contribution = 1.0
            score += contribution
            indicators["moderate_reg_failures"] =
                "${moderateEvents.size} registration failure(s) with moderate-suspicion causes " +
                "(${moderateEvents.map { it.causeCode }.distinct().joinToString()})"
        }

        // ---- Check 4: Failure burst (many failures regardless of cause) ----
        if (recentEvents.size >= FAILURE_BURST_THRESHOLD) {
            val uniqueCauses = recentEvents.map { it.causeCode }.distinct()
            val contribution = 1.5
            score += contribution
            indicators["rapid_failure_burst"] =
                "${recentEvents.size} registration failures in last 5 min across cause codes " +
                "${uniqueCauses.joinToString()} — possible rogue tower nearby"
        }

        // ---- Check 5: Correlate failures with current serving cells ----
        if (cells.isNotEmpty() && recentEvents.isNotEmpty()) {
            val currentCids = cells.map { it.cid }.toSet()
            val failedOnCurrentCell = recentEvents.filter { it.cellId != null && it.cellId in currentCids }
            if (failedOnCurrentCell.isNotEmpty()) {
                val contribution = 1.5
                score += contribution
                val cids = failedOnCurrentCell.mapNotNull { it.cellId }.distinct()
                indicators["failure_on_serving_cell"] =
                    "Registration failure(s) on currently visible cell(s): ${cids.joinToString()} — " +
                    "device may be actively targeted"
            }
        }

        // ---- Check 6: Service state oscillation ----
        if (recentTransitions.size >= OSCILLATION_THRESHOLD) {
            // Count transitions between in-service and out-of-service
            var oscillations = 0
            for (i in 1 until recentTransitions.size) {
                val prev = recentTransitions[i - 1].newState
                val curr = recentTransitions[i].newState
                val isTransition = (prev == ServiceState.STATE_IN_SERVICE &&
                    curr != ServiceState.STATE_IN_SERVICE) ||
                    (prev != ServiceState.STATE_IN_SERVICE &&
                        curr == ServiceState.STATE_IN_SERVICE)
                if (isTransition) oscillations++
            }
            if (oscillations >= OSCILLATION_THRESHOLD) {
                val contribution = 2.0
                score += contribution
                indicators["service_oscillation"] =
                    "$oscillations service state oscillations in last 2 min — " +
                    "device repeatedly connecting/disconnecting (rogue tower forcing re-attach)"
            }
        }

        if (score <= 0.0) return null

        val confidence = when {
            synchFailures.isNotEmpty() -> Confidence.HIGH  // Cause 21 alone = HIGH
            score >= 4.0 -> Confidence.HIGH
            score >= 2.0 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return DetectionResult(
            threatType = type,
            score = score,
            confidence = confidence,
            summary = buildSummary(indicators, synchFailures.isNotEmpty()),
            details = indicators
        )
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Extract CID, TAC/LAC, MCC, MNC from a [CellIdentity] across all RAT types.
     */
    private fun extractCellFields(cellIdentity: CellIdentity): CellFields {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cellIdentity is CellIdentityNr -> {
                CellFields(
                    cid = cellIdentity.nci.let { if (it == Long.MAX_VALUE) null else it.toInt() },
                    tacLac = cellIdentity.tac.let { if (it == Int.MAX_VALUE) null else it },
                    mcc = cellIdentity.mccString?.toIntOrNull(),
                    mnc = cellIdentity.mncString?.toIntOrNull()
                )
            }
            cellIdentity is CellIdentityLte -> {
                CellFields(
                    cid = cellIdentity.ci.let { if (it == Int.MAX_VALUE) null else it },
                    tacLac = cellIdentity.tac.let { if (it == Int.MAX_VALUE) null else it },
                    mcc = cellIdentity.mccString?.toIntOrNull(),
                    mnc = cellIdentity.mncString?.toIntOrNull()
                )
            }
            cellIdentity is CellIdentityWcdma -> {
                CellFields(
                    cid = cellIdentity.cid.let { if (it == Int.MAX_VALUE) null else it },
                    tacLac = cellIdentity.lac.let { if (it == Int.MAX_VALUE) null else it },
                    mcc = cellIdentity.mccString?.toIntOrNull(),
                    mnc = cellIdentity.mncString?.toIntOrNull()
                )
            }
            cellIdentity is CellIdentityGsm -> {
                CellFields(
                    cid = cellIdentity.cid.let { if (it == Int.MAX_VALUE) null else it },
                    tacLac = cellIdentity.lac.let { if (it == Int.MAX_VALUE) null else it },
                    mcc = cellIdentity.mccString?.toIntOrNull(),
                    mnc = cellIdentity.mncString?.toIntOrNull()
                )
            }
            else -> CellFields(null, null, null, null)
        }
    }

    private data class CellFields(
        val cid: Int?,
        val tacLac: Int?,
        val mcc: Int?,
        val mnc: Int?
    )

    private fun describeCellId(event: RegistrationFailureEvent): String {
        val parts = mutableListOf<String>()
        event.cellId?.let { parts.add("CID=$it") }
        event.tacLac?.let { parts.add("TAC=$it") }
        if (event.mcc != null && event.mnc != null) {
            parts.add("PLMN=${event.mcc}/${event.mnc}")
        }
        return if (parts.isNotEmpty()) " on ${parts.joinToString(", ")}" else ""
    }

    private fun describeCauseCode(code: Int): String = when (code) {
        CAUSE_IMSI_UNKNOWN -> "IMSI unknown in HLR"
        CAUSE_ILLEGAL_UE -> "Illegal UE"
        CAUSE_IMEI_NOT_ACCEPTED -> "IMEI not accepted"
        CAUSE_ILLEGAL_ME -> "Illegal ME"
        CAUSE_EPS_NOT_ALLOWED -> "EPS services not allowed"
        CAUSE_PLMN_NOT_ALLOWED -> "PLMN not allowed"
        CAUSE_LA_NOT_ALLOWED -> "Location/tracking area not allowed"
        CAUSE_NETWORK_FAILURE -> "Network failure"
        CAUSE_SYNCH_FAILURE -> "SYNCH_FAILURE"
        CAUSE_CONGESTION -> "Congestion"
        CAUSE_PROTOCOL_ERROR -> "Protocol error"
        else -> "Unknown cause $code"
    }

    private fun buildSummary(indicators: Map<String, String>, hasSynchFailure: Boolean): String {
        return when {
            hasSynchFailure ->
                "⚠\uFE0F AUTHENTICATION FAILURE: Network failed mutual authentication (AKA) — " +
                "this is the strongest indicator of a fake cell tower / IMSI catcher"

            indicators.containsKey("service_oscillation") &&
                indicators.any { it.key.startsWith("reg_reject_cause_") } ->
                "Registration rejections with service state oscillation — " +
                "device is being repeatedly rejected by a suspicious tower"

            indicators.containsKey("rapid_failure_burst") ->
                "Multiple registration failures detected in rapid succession — " +
                "possible rogue base station nearby"

            indicators.any { it.key.startsWith("reg_reject_cause_") } ->
                "Registration rejected with suspicious cause code — " +
                "possible fake tower harvesting device identifiers"

            indicators.containsKey("service_oscillation") ->
                "Rapid service state oscillation detected — device repeatedly " +
                "connecting and disconnecting from network"

            else ->
                "Registration failure anomalies detected (${indicators.size} indicators)"
        }
    }
}
