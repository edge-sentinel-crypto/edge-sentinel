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
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects fake cell towers by their temporal behavior patterns.
 *
 * Real towers are permanent infrastructure with stable, predictable behavior.
 * IMSI catchers betray themselves through HOW they appear and behave over time:
 *
 * 1. **Transient towers** — appear suddenly, vanish within minutes/hours
 * 2. **Cell ID cycling while stationary** — rapid serving cell changes when user isn't moving
 * 3. **Tower appearance schedule** — only visible during certain hours (operational window)
 * 4. **Rapid neighbor list changes** — RF environment manipulation at a fixed location
 * 5. **Signal strength instability** — erratic RSRP/RSSI from unstable power control
 *
 * This detector is @Singleton so internal ring buffers persist across scans,
 * building up temporal context over the lifetime of the app process.
 */
@Singleton
class TemporalAnomalyDetector @Inject constructor() : ThreatDetector {

    override val type: ThreatType = ThreatType.TEMPORAL_ANOMALY

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    companion object {
        // --- Transient tower detection ---
        /** A tower seen for less than this and then gone is "transient". */
        private const val TRANSIENT_LIFESPAN_MS = 30 * 60 * 1000L // 30 minutes

        /** Minimum observations before we consider a tower reliably "gone". */
        private const val MIN_SCANS_TO_CONFIRM_GONE = 3

        /** How long after last-seen before we declare a tower vanished. */
        private const val VANISHED_GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes

        // --- Cell ID cycling detection ---
        /** Max distinct serving cells in this window while stationary = suspicious. */
        private const val SERVING_CELL_CHANGE_THRESHOLD = 4

        /** Window for serving cell cycling check. */
        private const val CYCLING_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

        /** Movement speed (m/s) below which user is considered stationary. */
        private const val STATIONARY_SPEED_MS = 1.5 // ~5.4 km/h walking pace

        // --- Tower schedule detection ---
        /** Minimum days of observation before schedule analysis is meaningful. */
        private const val MIN_DAYS_FOR_SCHEDULE = 2

        /** If a tower is active less than this fraction of total observed hours, flag it. */
        private const val SCHEDULE_ACTIVITY_RATIO = 0.4

        /** Minimum observation spans (distinct hours) to even consider schedule analysis. */
        private const val MIN_OBSERVATION_HOURS = 6

        // --- Neighbor list stability ---
        /** Jaccard similarity below this = dramatic neighbor list change. */
        private const val NEIGHBOR_SIMILARITY_THRESHOLD = 0.5

        /** Number of recent neighbor snapshots to keep. */
        private const val NEIGHBOR_HISTORY_SIZE = 10

        // --- Signal instability ---
        /** Sliding window size for signal variance calculation. */
        private const val SIGNAL_WINDOW_SIZE = 20

        /** Standard deviation (dBm) above which signal is considered unstable. */
        private const val SIGNAL_STDDEV_THRESHOLD = 8.0

        // --- Ring buffer limits (memory footprint) ---
        /** Max cells tracked in the observation map. LRU eviction beyond this. */
        private const val MAX_TRACKED_CELLS = 500

        /** Max serving cell history entries. */
        private const val MAX_SERVING_HISTORY = 200

        /** Max signal samples per cell. */
        private const val MAX_SIGNAL_SAMPLES = 50
    }

    // -------------------------------------------------------------------------
    // Internal state — persists across analyze() calls within the Singleton
    // -------------------------------------------------------------------------

    /**
     * Per-cell observation record. Tracks first/last seen times, scan count,
     * and the hours-of-day in which this cell has been observed.
     */
    private data class CellObservation(
        val cid: Int,
        val lacTac: Int,
        val mcc: Int,
        val mnc: Int,
        var firstObserved: Long,
        var lastObserved: Long,
        var scanCount: Int = 0,
        /** Set of (dayOfYear * 24 + hourOfDay) values — tracks which hours this cell was seen. */
        val activeHours: MutableSet<Int> = mutableSetOf(),
        /** Set of distinct calendar days (dayOfYear) this cell was observed. */
        val activeDays: MutableSet<Int> = mutableSetOf(),
        /** Consecutive scans where this cell was NOT seen (resets on re-observation). */
        var missedScans: Int = 0,
        /** Whether we've already flagged this cell as transient. */
        var flaggedTransient: Boolean = false
    )

    /** Serving cell change record. */
    private data class ServingCellEvent(
        val cid: Int,
        val timestamp: Long
    )

    /** Neighbor list snapshot. */
    private data class NeighborSnapshot(
        val cids: Set<Int>,
        val timestamp: Long
    )

    /** Signal strength sample. */
    private data class SignalSample(
        val rssi: Int,
        val timestamp: Long
    )

    /** Observation state per cell, keyed by "mcc:mnc:lacTac:cid". */
    private val cellObservations = LinkedHashMap<String, CellObservation>(64, 0.75f, true)

    /** Recent serving cell events (ring buffer). */
    private val servingCellHistory = ArrayDeque<ServingCellEvent>(MAX_SERVING_HISTORY)

    /** Recent neighbor list snapshots (ring buffer). */
    private val neighborSnapshots = ArrayDeque<NeighborSnapshot>(NEIGHBOR_HISTORY_SIZE)

    /** Per-cell signal strength samples, keyed by cell key. */
    private val signalHistory = LinkedHashMap<String, ArrayDeque<SignalSample>>(32, 0.75f, true)

    /** Last known user position for stationary inference. */
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastPositionTime: Long = 0L

    // -------------------------------------------------------------------------
    // analyze()
    // -------------------------------------------------------------------------

    override suspend fun analyze(
        cells: List<CellTower>,
        history: List<CellTower>
    ): DetectionResult? {
        if (cells.isEmpty()) return null

        val now = System.currentTimeMillis()
        val indicators = mutableMapOf<String, String>()
        var score = 0.0

        // Update user position from current cells (use first cell with GPS)
        cells.firstOrNull { it.latitude != null && it.longitude != null }?.let { cell ->
            lastLatitude = cell.latitude
            lastLongitude = cell.longitude
            lastPositionTime = now
        }

        // Determine if user is stationary by checking position delta
        val isStationary = inferStationary(cells)

        // Update internal observation state
        val currentCids = cells.map { cellKey(it) }.toSet()
        updateObservations(cells, now)
        updateServingCell(cells, now)
        updateNeighborSnapshot(cells, now)
        updateSignalHistory(cells, now)

        // Mark cells not currently visible as missed
        for ((key, obs) in cellObservations) {
            if (key !in currentCids) {
                obs.missedScans++
            } else {
                obs.missedScans = 0
            }
        }

        // ----- Check 1: Transient towers -----
        val transientResults = detectTransientTowers(now)
        if (transientResults.isNotEmpty()) {
            val contribution = (transientResults.size * 1.0).coerceAtMost(3.0)
            score += contribution
            indicators["transient_towers"] = transientResults.joinToString("; ")
        }

        // ----- Check 2: Cell ID cycling while stationary -----
        if (isStationary) {
            val cyclingResult = detectServingCellCycling(now)
            if (cyclingResult != null) {
                score += cyclingResult.first
                indicators["cell_cycling"] = cyclingResult.second
            }
        }

        // ----- Check 3: Tower appearance schedule -----
        val scheduleResults = detectScheduledTowers(now)
        if (scheduleResults.isNotEmpty()) {
            val contribution = (scheduleResults.size * 0.75).coerceAtMost(2.0)
            score += contribution
            indicators["scheduled_towers"] = scheduleResults.joinToString("; ")
        }

        // ----- Check 4: Rapid neighbor list changes while stationary -----
        if (isStationary) {
            val neighborResult = detectNeighborListInstability()
            if (neighborResult != null) {
                score += neighborResult.first
                indicators["neighbor_instability"] = neighborResult.second
            }
        }

        // ----- Check 5: Signal strength instability -----
        val signalResults = detectSignalInstability(cells)
        if (signalResults.isNotEmpty()) {
            val contribution = (signalResults.size * 0.5).coerceAtMost(2.0)
            score += contribution
            indicators["signal_instability"] = signalResults.joinToString("; ")
        }

        // ----- Also incorporate long-term history for transient detection -----
        val historyTransients = detectHistoryTransients(history, now)
        if (historyTransients.isNotEmpty()) {
            val contribution = (historyTransients.size * 0.5).coerceAtMost(1.5)
            score += contribution
            indicators["history_transients"] = historyTransients.joinToString("; ")
        }

        if (score <= 0.0) return null

        // Evict old entries to bound memory
        evictStaleEntries(now)

        val confidence = when {
            score >= 4.0 -> Confidence.HIGH
            score >= 2.0 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return DetectionResult(
            threatType = selectThreatType(indicators),
            score = score,
            confidence = confidence,
            summary = buildSummary(indicators, score),
            details = indicators
        )
    }

    // -------------------------------------------------------------------------
    // State update helpers
    // -------------------------------------------------------------------------

    private fun cellKey(cell: CellTower): String =
        "${cell.mcc}:${cell.mnc}:${cell.lacTac}:${cell.cid}"

    private fun updateObservations(cells: List<CellTower>, now: Long) {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val hourOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfYear = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val hourKey = dayOfYear * 24 + hourOfDay

        for (cell in cells) {
            val key = cellKey(cell)
            val obs = cellObservations.getOrPut(key) {
                CellObservation(
                    cid = cell.cid,
                    lacTac = cell.lacTac,
                    mcc = cell.mcc,
                    mnc = cell.mnc,
                    firstObserved = now,
                    lastObserved = now
                )
            }
            obs.lastObserved = now
            obs.scanCount++
            obs.activeHours.add(hourKey)
            obs.activeDays.add(dayOfYear)
        }

        // Enforce max tracked cells (LRU — LinkedHashMap with accessOrder=true)
        while (cellObservations.size > MAX_TRACKED_CELLS) {
            val eldest = cellObservations.entries.iterator().next()
            cellObservations.remove(eldest.key)
        }
    }

    private fun updateServingCell(cells: List<CellTower>, now: Long) {
        // The first cell in the list is typically the serving/registered cell
        val serving = cells.firstOrNull() ?: return
        val lastServing = servingCellHistory.lastOrNull()

        // Only record if the serving cell changed
        if (lastServing == null || lastServing.cid != serving.cid) {
            if (servingCellHistory.size >= MAX_SERVING_HISTORY) {
                servingCellHistory.removeFirst()
            }
            servingCellHistory.addLast(ServingCellEvent(serving.cid, now))
        }
    }

    private fun updateNeighborSnapshot(cells: List<CellTower>, now: Long) {
        // Neighbors = all cells except the first (serving)
        val neighborCids = cells.drop(1).map { it.cid }.toSet()
        if (neighborCids.isEmpty()) return

        if (neighborSnapshots.size >= NEIGHBOR_HISTORY_SIZE) {
            neighborSnapshots.removeFirst()
        }
        neighborSnapshots.addLast(NeighborSnapshot(neighborCids, now))
    }

    private fun updateSignalHistory(cells: List<CellTower>, now: Long) {
        for (cell in cells) {
            if (cell.signalStrength == 0 || cell.signalStrength == Int.MAX_VALUE) continue
            val key = cellKey(cell)
            val samples = signalHistory.getOrPut(key) { ArrayDeque(MAX_SIGNAL_SAMPLES) }
            if (samples.size >= MAX_SIGNAL_SAMPLES) {
                samples.removeFirst()
            }
            samples.addLast(SignalSample(cell.signalStrength, now))
        }

        // Bound signal history map
        while (signalHistory.size > MAX_TRACKED_CELLS) {
            val eldest = signalHistory.entries.iterator().next()
            signalHistory.remove(eldest.key)
        }
    }

    // -------------------------------------------------------------------------
    // Detection algorithms
    // -------------------------------------------------------------------------

    /**
     * Check 1: Transient towers — cells that appeared recently, lived briefly,
     * and are now gone. Classic IMSI catcher signature.
     */
    private fun detectTransientTowers(now: Long): List<String> {
        val results = mutableListOf<String>()

        for ((key, obs) in cellObservations) {
            if (obs.flaggedTransient) continue

            val lifespan = obs.lastObserved - obs.firstObserved
            val timeSinceLastSeen = now - obs.lastObserved

            // Tower existed briefly and has now vanished
            if (lifespan < TRANSIENT_LIFESPAN_MS &&
                timeSinceLastSeen > VANISHED_GRACE_PERIOD_MS &&
                obs.missedScans >= MIN_SCANS_TO_CONFIRM_GONE &&
                obs.scanCount >= 2 // Must have been seen at least twice (not a glitch)
            ) {
                obs.flaggedTransient = true
                val lifespanMin = lifespan / 60_000
                results.add(
                    "CID ${obs.cid} (${obs.mcc}/${obs.mnc} TAC:${obs.lacTac}) " +
                        "appeared and vanished after ~${lifespanMin}min " +
                        "(seen ${obs.scanCount} times)"
                )
            }
        }

        return results
    }

    /**
     * Check 2: Serving cell cycling while stationary.
     * If the user is still but the serving cell keeps flipping, something
     * is pulling the device back and forth (IMSI catcher handover war).
     */
    private fun detectServingCellCycling(now: Long): Pair<Double, String>? {
        val cutoff = now - CYCLING_WINDOW_MS
        val recentEvents = servingCellHistory.filter { it.timestamp >= cutoff }

        if (recentEvents.size < SERVING_CELL_CHANGE_THRESHOLD) return null

        val distinctCells = recentEvents.map { it.cid }.toSet().size
        if (distinctCells < 2) return null

        // Score based on how many changes occurred
        val changeCount = recentEvents.size
        val contribution = ((changeCount - SERVING_CELL_CHANGE_THRESHOLD + 1) * 0.75)
            .coerceAtMost(3.0)

        return contribution to
            "$changeCount serving cell changes across $distinctCells distinct CIDs " +
            "in ${CYCLING_WINDOW_MS / 60_000}min while stationary"
    }

    /**
     * Check 3: Towers with a limited operational schedule.
     * Real towers are 24/7. If we have 2+ days of data and a tower only appears
     * during a narrow window, it's suspicious.
     */
    private fun detectScheduledTowers(now: Long): List<String> {
        val results = mutableListOf<String>()

        for ((_, obs) in cellObservations) {
            if (obs.activeDays.size < MIN_DAYS_FOR_SCHEDULE) continue

            // Calculate total distinct hours we've been observing (across all cells)
            val totalObservationHours = getTotalObservationHours()
            if (totalObservationHours < MIN_OBSERVATION_HOURS) continue

            val cellActiveHours = obs.activeHours.size
            val activityRatio = cellActiveHours.toDouble() / totalObservationHours

            if (activityRatio < SCHEDULE_ACTIVITY_RATIO && cellActiveHours >= 2) {
                // Extract the hours-of-day this tower is active
                val hoursOfDay = obs.activeHours
                    .map { it % 24 }
                    .distinct()
                    .sorted()

                results.add(
                    "CID ${obs.cid} only active during hours " +
                        "${hoursOfDay.joinToString(",")} " +
                        "(${cellActiveHours}/${totalObservationHours} observation hours, " +
                        "across ${obs.activeDays.size} days)"
                )
            }
        }

        return results
    }

    /**
     * Check 4: Neighbor list instability while stationary.
     * Compares consecutive neighbor snapshots using Jaccard similarity.
     */
    private fun detectNeighborListInstability(): Pair<Double, String>? {
        if (neighborSnapshots.size < 3) return null

        var lowSimilarityCount = 0
        var worstSimilarity = 1.0
        val snapshots = neighborSnapshots.toList()

        for (i in 1 until snapshots.size) {
            val prev = snapshots[i - 1].cids
            val curr = snapshots[i].cids
            if (prev.isEmpty() || curr.isEmpty()) continue

            val intersection = prev.intersect(curr).size
            val union = prev.union(curr).size
            val jaccard = if (union > 0) intersection.toDouble() / union else 1.0

            if (jaccard < NEIGHBOR_SIMILARITY_THRESHOLD) {
                lowSimilarityCount++
                if (jaccard < worstSimilarity) worstSimilarity = jaccard
            }
        }

        if (lowSimilarityCount == 0) return null

        val contribution = (lowSimilarityCount * 0.75).coerceAtMost(2.0)
        return contribution to
            "$lowSimilarityCount dramatic neighbor list changes detected " +
            "(worst Jaccard similarity: ${"%.2f".format(worstSimilarity)}) " +
            "— RF environment may be manipulated"
    }

    /**
     * Check 5: Signal strength instability per cell.
     * Real towers have smooth signal variation (slow fading). Fake towers
     * on SDR hardware show erratic power control.
     */
    private fun detectSignalInstability(currentCells: List<CellTower>): List<String> {
        val results = mutableListOf<String>()

        for (cell in currentCells) {
            val key = cellKey(cell)
            val samples = signalHistory[key] ?: continue
            if (samples.size < SIGNAL_WINDOW_SIZE / 2) continue

            val values = samples.map { it.rssi.toDouble() }
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            val stddev = sqrt(variance)

            if (stddev > SIGNAL_STDDEV_THRESHOLD) {
                results.add(
                    "CID ${cell.cid} signal stddev=${"%.1f".format(stddev)}dBm " +
                        "over ${values.size} samples (threshold: ${SIGNAL_STDDEV_THRESHOLD}dBm) " +
                        "— unstable power control"
                )
            }
        }

        return results
    }

    /**
     * Cross-reference the `history` parameter for cells that were seen briefly
     * in the past and are now gone — adds confidence to transient detection.
     */
    private fun detectHistoryTransients(
        history: List<CellTower>,
        now: Long
    ): List<String> {
        val results = mutableListOf<String>()

        for (cell in history) {
            val lifespan = cell.lastSeen - cell.firstSeen
            val timeSinceLastSeen = now - cell.lastSeen

            // Brief lifespan, gone for a while, seen a few times
            if (lifespan in 1..TRANSIENT_LIFESPAN_MS &&
                timeSinceLastSeen > VANISHED_GRACE_PERIOD_MS * 2 &&
                cell.timesSeen in 2..10
            ) {
                val key = cellKey(cell)
                // Don't double-report if we already caught it internally
                val obs = cellObservations[key]
                if (obs?.flaggedTransient == true) continue

                val lifespanMin = lifespan / 60_000
                results.add(
                    "Historical: CID ${cell.cid} lived ~${lifespanMin}min, " +
                        "seen ${cell.timesSeen}x, gone for " +
                        "${timeSinceLastSeen / 60_000}min"
                )
            }
        }

        return results
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Infer whether the user is stationary based on GPS coordinates from cells.
     * Falls back to true if we don't have enough position data (conservative —
     * triggers more checks, which is the safer default).
     */
    private fun inferStationary(cells: List<CellTower>): Boolean {
        val currentPos = cells.firstOrNull { it.latitude != null && it.longitude != null }
            ?: return true // No GPS → assume stationary (conservative)

        val prevLat = lastLatitude ?: return true
        val prevLon = lastLongitude ?: return true

        if (lastPositionTime == 0L) return true

        val now = System.currentTimeMillis()
        val elapsed = (now - lastPositionTime) / 1000.0
        if (elapsed < 1.0) return true

        val distance = haversineMeters(
            prevLat, prevLon,
            currentPos.latitude!!, currentPos.longitude!!
        )
        val speed = distance / elapsed

        return speed < STATIONARY_SPEED_MS
    }

    /** Haversine distance in meters between two lat/lon points. */
    private fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6_371_000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** Get total distinct observation hours across all cells. */
    private fun getTotalObservationHours(): Int {
        val allHours = mutableSetOf<Int>()
        for ((_, obs) in cellObservations) {
            allHours.addAll(obs.activeHours)
        }
        return allHours.size
    }

    /** Evict observations for cells not seen in a long time to bound memory. */
    private fun evictStaleEntries(now: Long) {
        val evictionThreshold = 72 * 60 * 60 * 1000L // 72 hours
        val iterator = cellObservations.entries.iterator()
        while (iterator.hasNext()) {
            val (key, obs) = iterator.next()
            if (now - obs.lastObserved > evictionThreshold) {
                iterator.remove()
                signalHistory.remove(key)
            }
        }
    }

    /**
     * Select the most appropriate ThreatType based on which indicators fired.
     * Maps to the scoring categories:
     * - temporalPattern (0.10) → TRACKING_PATTERN
     * - towerBehavior (0.20) → SIGNAL_ANOMALY (transient towers)
     * - signalAnomaly (0.20) → SIGNAL_ANOMALY
     */
    private fun selectThreatType(indicators: Map<String, String>): ThreatType {
        // All temporal anomalies now use the dedicated TEMPORAL_ANOMALY type.
        // The ThreatDetectionEngine maps detail keys to the appropriate scoring
        // categories (signal, tower behavior, temporal pattern).
        return ThreatType.TEMPORAL_ANOMALY
    }

    private fun buildSummary(indicators: Map<String, String>, score: Double): String {
        val parts = mutableListOf<String>()

        if (indicators.containsKey("transient_towers") ||
            indicators.containsKey("history_transients")
        ) {
            parts.add("transient tower(s) detected")
        }
        if (indicators.containsKey("cell_cycling")) {
            parts.add("rapid serving cell cycling while stationary")
        }
        if (indicators.containsKey("scheduled_towers")) {
            parts.add("tower(s) with limited operational schedule")
        }
        if (indicators.containsKey("neighbor_instability")) {
            parts.add("neighbor list manipulation")
        }
        if (indicators.containsKey("signal_instability")) {
            parts.add("erratic signal strength")
        }

        return if (parts.isEmpty()) {
            "Temporal anomalies detected (${indicators.size} indicators, score=${"%.1f".format(score)})"
        } else {
            "Temporal anomaly: ${parts.joinToString(", ")} " +
                "(${indicators.size} indicators, score=${"%.1f".format(score)})"
        }
    }
}
