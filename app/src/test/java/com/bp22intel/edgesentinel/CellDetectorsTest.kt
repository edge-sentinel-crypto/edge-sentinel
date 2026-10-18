package com.bp22intel.edgesentinel

import com.bp22intel.edgesentinel.detection.detectors.FakeBtsDetector
import com.bp22intel.edgesentinel.detection.detectors.NetworkDowngradeDetector
import com.bp22intel.edgesentinel.detection.detectors.TemporalAnomalyDetector
import com.bp22intel.edgesentinel.detection.detectors.ThreatDetector
import com.bp22intel.edgesentinel.di.DetectionModule
import com.bp22intel.edgesentinel.domain.model.NetworkType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellDetectorsTest {
    // LTE ECI 0x1001 = eNB 16, sector 1 (valid format); history holds a different cell.
    private val newCid = 0x1001
    private val history = listOf(cell(0x1002, signal = -90))

    @Test fun fakeBtsUnseenCidAloneGivesNoScore() = runTest {
        val r = FakeBtsDetector().analyze(listOf(cell(newCid, signal = -95)), history)
        assertNull("Unseen CID alone must not score", r)
    }

    @Test fun fakeBtsUnseenCidPlusStrongSignalScores() = runTest {
        val r = FakeBtsDetector().analyze(listOf(cell(newCid, signal = -45)), history)
        assertNotNull(r)
        assertTrue(r!!.details.containsKey("unknown_cid_$newCid"))
        assertTrue(r.details.containsKey("strong_signal_$newCid"))
        assertTrue(r.score > 1.5)
    }

    @Test fun downgradeRegistered4gTo2gFires() = runTest {
        val hist = listOf(cell(500, NetworkType.LTE), cell(501, NetworkType.LTE))
        val r = NetworkDowngradeDetector().analyze(
            listOf(cell(500, NetworkType.GSM, registered = true)), hist)
        assertNotNull(r)
        assertTrue(r!!.score >= 3.0)
    }

    @Test fun downgradeNeighbor2gDoesNotFire() = runTest {
        val hist = listOf(cell(500, NetworkType.LTE), cell(501, NetworkType.LTE))
        val r = NetworkDowngradeDetector().analyze(
            listOf(cell(500, NetworkType.LTE, registered = true), cell(900, NetworkType.GSM)), hist)
        assertNull(r)
    }

    @Test fun downgradeLteToLteHandoverDoesNotFire() = runTest {
        val d = NetworkDowngradeDetector()
        val hist = listOf(cell(500, NetworkType.LTE), cell(501, NetworkType.LTE))
        assertNull(d.analyze(listOf(cell(500, NetworkType.LTE, registered = true)), hist))
        assertNull(d.analyze(listOf(cell(777, NetworkType.LTE, registered = true)), hist))
    }

    @Test fun temporalAnomalyDetectorIsBound() {
        val bound = DetectionModule::class.java.declaredMethods.any { m ->
            m.returnType == ThreatDetector::class.java &&
                m.parameterTypes.contentEquals(arrayOf(TemporalAnomalyDetector::class.java))
        }
        assertTrue("DetectionModule must bind TemporalAnomalyDetector into the set", bound)
    }

    // ── F3 / N6: unseen CID needs a second indicator; PCI reuse and missing
    //    neighbours now count, a weak signal alone does not. ──────────────────

    @Test fun fakeBtsNewCidAtMinus65WithPciReuseScores() = runTest {
        val here = cell(newCid, signal = -65).copy(pci = 101, latitude = 30.2672, longitude = -97.7431)
        val other = cell(0x1002, signal = -80).copy(pci = 101, latitude = 30.2680, longitude = -97.7440)
        val r = FakeBtsDetector().analyze(listOf(here), listOf(other))
        assertNotNull("new CID + PCI reuse must score", r)
        assertTrue(r!!.details.containsKey("unknown_cid_$newCid"))
        assertTrue(r.details.keys.any { it.startsWith("pci_reuse_${newCid}_") })
    }

    @Test fun fakeBtsNewCidAtMinus65WithMissingNeighboursScores() = runTest {
        val hist = listOf(cell(0x1002), cell(0x1003), cell(0x1004))   // 3 per LAC/TAC on average
        val r = FakeBtsDetector().analyze(listOf(cell(newCid, signal = -65)), hist)
        assertNotNull("new CID + missing neighbours must score", r)
        assertTrue(r!!.details.containsKey("unknown_cid_$newCid"))
        assertTrue(r.details.containsKey("missing_neighbors"))
    }

    @Test fun fakeBtsNewCidAloneAtMinus90DoesNotScore() = runTest {
        val r = FakeBtsDetector().analyze(listOf(cell(newCid, signal = -90)), history)
        assertNull("unseen CID at -90 dBm with no other indicator must not score", r)
    }

    // ── F4: downgrade on the serving cell is caught on the FIRST scan and on
    //    every repeated scan; the lastObservedRank update does not hide it. ───

    @Test fun downgradeDetectedOnFirstScanAndStillOnRepeat() = runTest {
        val d = NetworkDowngradeDetector()
        val hist = listOf(cell(500, NetworkType.LTE), cell(501, NetworkType.LTE))
        val gsm = listOf(cell(500, NetworkType.GSM, registered = true))
        val first = d.analyze(gsm, hist)
        assertNotNull("downgrade must fire on the first scan", first)
        assertTrue(first!!.details.containsKey("downgrade_500"))
        val second = d.analyze(gsm, hist)
        assertNotNull("repeated scan must still fire after lastObservedRank = 2G", second)
        assertTrue(second!!.score >= 3.0)
    }

    @Test fun downgradeAfterLteScanGetsSpeedMultiplier() = runTest {
        val d = NetworkDowngradeDetector()
        val hist = listOf(cell(500, NetworkType.LTE), cell(501, NetworkType.LTE))
        assertNull(d.analyze(listOf(cell(500, NetworkType.LTE, registered = true)), hist))
        Thread.sleep(5)
        val r = d.analyze(listOf(cell(500, NetworkType.GSM, registered = true)), hist)
        assertNotNull(r)
        assertTrue("instant LTE->GSM must add a speed indicator: ${r!!.details.keys}",
            r.details.keys.any { it.startsWith("speed_") })
    }

    // ── N8: no registered cell -> strongest cell is the serving cell ─────────

    @Test fun noRegisteredCellUsesStrongestCell() = runTest {
        val hist = listOf(cell(500, NetworkType.LTE), cell(501, NetworkType.LTE))
        // Weak 2G neighbour listed FIRST, strong LTE cell second: no downgrade.
        assertNull(NetworkDowngradeDetector().analyze(
            listOf(cell(900, NetworkType.GSM, signal = -110), cell(500, NetworkType.LTE, signal = -70)), hist))
        // Strong 2G cell, weak LTE listed first: the 2G cell is serving -> downgrade.
        val r = NetworkDowngradeDetector().analyze(
            listOf(cell(500, NetworkType.LTE, signal = -110), cell(900, NetworkType.GSM, signal = -60)), hist)
        assertNotNull(r)
        assertTrue(r!!.details.containsKey("downgrade_900"))
    }
}
