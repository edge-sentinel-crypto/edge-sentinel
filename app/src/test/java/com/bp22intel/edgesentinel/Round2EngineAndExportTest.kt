package com.bp22intel.edgesentinel

import android.content.ContextWrapper
import com.bp22intel.edgesentinel.baseline.BaselineManager
import com.bp22intel.edgesentinel.detection.detectors.ThreatDetector
import com.bp22intel.edgesentinel.detection.engine.ThreatDetectionEngine
import com.bp22intel.edgesentinel.detection.scoring.ThreatScorer
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.DetectionSensitivity
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType
import com.bp22intel.edgesentinel.service.ScanThreatLevels
import com.bp22intel.edgesentinel.travel.DataWipeManager
import com.bp22intel.edgesentinel.travel.TravelExporter
import com.bp22intel.edgesentinel.travel.WipeScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class Round2EngineAndExportTest {

    // ── N2 / N7: runScanWithScore ───────────────────────────────────────────

    /** Detector that returns a fixed result (or null). */
    private class FixedDetector(private val r: DetectionResult?) : ThreatDetector {
        override val type = ThreatType.FAKE_BTS
        override suspend fun analyze(cells: List<CellTower>, history: List<CellTower>) = r
    }

    private fun threatResult(score: Double) = DetectionResult(
        ThreatType.FAKE_BTS, score, Confidence.HIGH, "fake bts",
        mapOf("unknown_cid_4097" to "x", "strong_signal_4097" to "y"))

    private fun engine(vararg d: ThreatDetector) = ThreatDetectionEngine(
        d.toSet(), ThreatScorer(), allocateWithoutConstructor(BaselineManager::class.java))

    @Test fun runScanWithScoreReturnsItsOwnScoringResult() = runTest {
        val e = engine(FixedDetector(threatResult(7.0)))
        val outcome = e.runScanWithScore(listOf(cell(4097, signal = -45)), emptyList())
        assertNotNull(outcome.scoringResult)
        assertTrue(outcome.results.any { it.threatType == ThreatType.FAKE_BTS })
        assertSame(outcome.scoringResult, e.lastScoringResult)

        // A second, clean scan on ANOTHER engine sharing nothing cannot change the
        // first outcome; and a clean scan on the same engine resets only the shared
        // field, not the outcome already returned.
        val clean = engine(FixedDetector(null))
        val cleanOutcome = clean.runScanWithScore(listOf(cell(1)), emptyList())
        assertNull(cleanOutcome.scoringResult)
        assertTrue(cleanOutcome.results.isEmpty())

        val e2 = engine(FixedDetector(threatResult(7.0)))
        val first = e2.runScanWithScore(listOf(cell(4097, signal = -45)), emptyList())
        val firstScore = first.scoringResult!!.total
        // runScan() delegates to runScanWithScore and returns the same results.
        val viaRunScan = e2.runScan(listOf(cell(4097, signal = -45)), emptyList())
        assertEquals(first.results.map { it.threatType }, viaRunScan.map { it.threatType })
        assertEquals(firstScore, first.scoringResult!!.total, 0.0)
    }

    @Test fun singleThreatAlertRaisesOverallToAtLeastThreat() = runTest {
        val outcome = engine(FixedDetector(threatResult(7.0)))
            .runScanWithScore(listOf(cell(4097, signal = -45)), emptyList())
        val level = ScanThreatLevels.overallLevel(
            outcome.scoringResult, outcome.results, DetectionSensitivity.MEDIUM)
        assertTrue("single THREAT alert must give overall >= THREAT, got $level",
            level.ordinal >= ThreatLevel.THREAT.ordinal)
        // The composite alone may be lower; the alert path must not be lost.
        assertEquals(ThreatLevel.THREAT,
            ScanThreatLevels.alertSeverity(7.0, DetectionSensitivity.MEDIUM))
    }

    // ── N1: FileProvider roots ─────────────────────────────────────────────

    private fun moduleFile(rel: String): File =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("cannot find $rel from ${File(".").absolutePath}")

    @Test fun fileProviderRootNamesAreUnique() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(moduleFile("src/main/res/xml/file_provider_paths.xml"))
        val nodes = doc.documentElement.childNodes
        val names = mutableListOf<String>()
        val roots = mutableListOf<Pair<String, String>>()
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val el = n as org.w3c.dom.Element
            names += el.getAttribute("name")
            roots += el.tagName to el.getAttribute("path")
        }
        assertTrue(names.isNotEmpty())
        assertEquals("duplicate FileProvider root names: $names", names.size, names.toSet().size)
        // TravelExporter writes to filesDir/travel_exports/, so a files-path root must cover it.
        assertTrue(roots.contains("files-path" to "travel_exports/"))
    }

    // ── NF2: export query uses table `cells` with real columns ─────────────

    @Test fun cellsExportQueryUsesRealTableAndColumns() {
        val sql = TravelExporter.CELLS_EXPORT_SQL
        assertTrue(Regex("""\bFROM cells\b""").containsMatchIn(sql))
        assertFalse(sql.contains("cell_towers"))
        val schema = moduleFile(
            "schemas/com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase/9.json").readText()
        val start = schema.indexOf("\"tableName\": \"cells\"")
        assertTrue("cells table missing from schema 9", start >= 0)
        val end = schema.indexOf("\"tableName\"", start + 10).let { if (it < 0) schema.length else it }
        val createSql = Regex("\"createSql\": \"([^\"]+)\"").find(schema, start)!!.groupValues[1]
        assertTrue(schema.indexOf(createSql) < end)
        val schemaCols = Regex("`(\\w+)` (INTEGER|TEXT|REAL)").findAll(createSql).map { it.groupValues[1] }.toSet()
        val selectCols = sql.substringAfter("SELECT").substringBefore("FROM")
            .split(",").map { it.trim() }
        assertEquals(14, selectCols.size)
        for (c in selectCols) assertTrue("column $c not in cells schema $schemaCols", c in schemaCols)
    }

    // ── NF4: a table-wipe failure is reported in WipeResult.errors ──────────

    /** Context whose database path points at a file that cannot be opened. */
    class BrokenDbContext : ContextWrapper(null) {
        override fun getDatabasePath(name: String): File = dbFile!!
        companion object { var dbFile: File? = null }
    }

    @Test fun wipeTableFailureIsReportedInErrors() {
        val bad = File.createTempFile("edge_sentinel", ".db").apply {
            writeText("this is not a sqlite database"); deleteOnExit()
        }
        BrokenDbContext.dbFile = bad
        val ctx = allocateWithoutConstructor(BrokenDbContext::class.java)
        val mgr = DataWipeManager(ctx)
        val m = DataWipeManager::class.java.getDeclaredMethod("clearDatabaseTables",
            WipeScope::class.java, Long::class.javaPrimitiveType, MutableList::class.java)
        m.isAccessible = true
        val errors = mutableListOf<String>()
        val cleared = m.invoke(mgr, WipeScope.TRAVEL_DATA, 1_000L, errors) as Int
        assertEquals(0, cleared)
        assertTrue("table failure must be reported, got $errors", errors.isNotEmpty())
        assertTrue(errors.first().startsWith("Failed to clear database tables"))
        // WipeResult.success is derived from errors, so this wipe is a failure.
        val result = com.bp22intel.edgesentinel.travel.WipeResult(errors.isEmpty(), cleared, errors)
        assertFalse(result.success)
        assertTrue(result.errors.isNotEmpty())
    }
}
