package com.bp22intel.edgesentinel

import com.bp22intel.edgesentinel.detection.scoring.ThreatScorer
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.service.ScanThreatLevels
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreatScoringTest {
    private val scorer = ThreatScorer()

    @Test fun scorerClearBelowThree() {
        // protocol 1.0 * 0.25 * 10 = 2.5
        val r = scorer.calculateScore(mapOf(ThreatScorer.KEY_PROTOCOL_VIOLATION to 1.0))
        assertEquals(2.5, r.total, 1e-9)
        assertEquals(ThreatLevel.CLEAR, r.threatLevel)
    }

    @Test fun scorerSuspiciousBetweenThreeAndSix() {
        // signal 1.0 + tower 1.0 = 4.0
        val r = scorer.calculateScore(mapOf(
            ThreatScorer.KEY_SIGNAL_ANOMALY to 1.0, ThreatScorer.KEY_TOWER_BEHAVIOR to 1.0))
        assertEquals(4.0, r.total, 1e-9)
        assertEquals(ThreatLevel.SUSPICIOUS, r.threatLevel)
    }

    @Test fun scorerThreatAtOrAboveSix() {
        val all = listOf(ThreatScorer.KEY_SIGNAL_ANOMALY, ThreatScorer.KEY_TOWER_BEHAVIOR,
            ThreatScorer.KEY_PROTOCOL_VIOLATION, ThreatScorer.KEY_NETWORK_INTEGRITY,
            ThreatScorer.KEY_TEMPORAL_PATTERN, ThreatScorer.KEY_ENVIRONMENTAL).associateWith { 1.0 }
        val r = scorer.calculateScore(all)
        assertEquals(10.0, r.total, 1e-9)
        assertEquals(ThreatLevel.THREAT, r.threatLevel)
    }

    @Test fun scorerSensitivityScaling() {
        // 2.5 is CLEAR on MEDIUM but SUSPICIOUS on HIGH (3.0 * 0.7 = 2.1)
        val ind = mapOf(ThreatScorer.KEY_PROTOCOL_VIOLATION to 1.0)
        assertEquals(ThreatLevel.SUSPICIOUS, scorer.calculateScore(ind, DetectionSensitivity.HIGH).threatLevel)
        // 4.0 is SUSPICIOUS on MEDIUM but CLEAR on LOW (3.0 * 1.5 = 4.5)
        val ind2 = mapOf(ThreatScorer.KEY_SIGNAL_ANOMALY to 1.0, ThreatScorer.KEY_TOWER_BEHAVIOR to 1.0)
        assertEquals(ThreatLevel.CLEAR, scorer.calculateScore(ind2, DetectionSensitivity.LOW).threatLevel)
    }

    @Test fun scanLevelsThresholdsMedium() {
        val m = DetectionSensitivity.MEDIUM
        assertEquals(ThreatLevel.CLEAR, ScanThreatLevels.alertSeverity(2.99, m))
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.alertSeverity(3.0, m))
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.alertSeverity(5.99, m))
        assertEquals(ThreatLevel.THREAT, ScanThreatLevels.alertSeverity(6.0, m))
    }

    @Test fun scanLevelsSensitivityScaling() {
        assertEquals(ThreatLevel.CLEAR, ScanThreatLevels.alertSeverity(4.4, DetectionSensitivity.LOW))
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.alertSeverity(4.5, DetectionSensitivity.LOW))
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.alertSeverity(8.9, DetectionSensitivity.LOW))
        assertEquals(ThreatLevel.THREAT, ScanThreatLevels.alertSeverity(9.0, DetectionSensitivity.LOW))
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.alertSeverity(2.2, DetectionSensitivity.HIGH))
        assertEquals(ThreatLevel.THREAT, ScanThreatLevels.alertSeverity(4.3, DetectionSensitivity.HIGH))
        // Old 0.4/0.7 cutoffs must no longer produce alerts.
        assertEquals(ThreatLevel.CLEAR, ScanThreatLevels.alertSeverity(0.8, DetectionSensitivity.MEDIUM))
    }

    // Replaces overallLevelFollowsScorerResult (near-tautology). N7: the overall
    // level is max(composite level, worst single-alert level) on one scale.
    @Test fun overallLevelIsMaxOfCompositeAndWorstAlert() {
        val m = DetectionSensitivity.MEDIUM
        fun det(score: Double) = com.bp22intel.edgesentinel.domain.model.DetectionResult(
            com.bp22intel.edgesentinel.domain.model.ThreatType.FAKE_BTS, score,
            com.bp22intel.edgesentinel.domain.model.Confidence.MEDIUM, "t", emptyMap())
        val clear = scorer.calculateScore(mapOf(ThreatScorer.KEY_PROTOCOL_VIOLATION to 1.0)) // 2.5
        assertEquals(ThreatLevel.CLEAR, clear.threatLevel)
        // A single THREAT alert lifts a CLEAR composite (and a null composite) to THREAT.
        assertEquals(ThreatLevel.THREAT, ScanThreatLevels.overallLevel(clear, listOf(det(6.5)), m))
        assertEquals(ThreatLevel.THREAT, ScanThreatLevels.overallLevel(null, listOf(det(1.0), det(7.0)), m))
        // A SUSPICIOUS alert lifts CLEAR to SUSPICIOUS, never to THREAT.
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.overallLevel(clear, listOf(det(4.0)), m))
        // Low alerts never lower a higher composite level.
        val susp = scorer.calculateScore(mapOf(
            ThreatScorer.KEY_SIGNAL_ANOMALY to 1.0, ThreatScorer.KEY_TOWER_BEHAVIOR to 1.0))
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.overallLevel(susp, listOf(det(0.5)), m))
        // Nothing at all is CLEAR.
        assertEquals(ThreatLevel.CLEAR, ScanThreatLevels.overallLevel(null, emptyList(), m))
        // Sensitivity applies to the alert path too: 4.3 is THREAT on HIGH, SUSPICIOUS on MEDIUM.
        assertEquals(ThreatLevel.THREAT, ScanThreatLevels.overallLevel(null, listOf(det(4.3)), DetectionSensitivity.HIGH))
        assertEquals(ThreatLevel.SUSPICIOUS, ScanThreatLevels.overallLevel(null, listOf(det(4.3)), m))
    }
}
