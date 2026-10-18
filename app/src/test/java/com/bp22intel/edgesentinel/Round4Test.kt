package com.bp22intel.edgesentinel

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.bp22intel.edgesentinel.data.WipeGuardOpenHelper
import com.bp22intel.edgesentinel.data.WipeInProgress
import com.bp22intel.edgesentinel.data.WipeInProgressException
import com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase
import com.bp22intel.edgesentinel.data.local.Migrations
import com.bp22intel.edgesentinel.data.local.dao.BleDeviceDao
import com.bp22intel.edgesentinel.data.local.dao.BssidSightingDao
import com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao
import com.bp22intel.edgesentinel.data.local.entity.BleDeviceEntity
import com.bp22intel.edgesentinel.data.local.entity.BssidSightingEntity
import com.bp22intel.edgesentinel.detection.detectors.NetworkDowngradeDetector
import com.bp22intel.edgesentinel.detection.wifi.ObservedAp
import com.bp22intel.edgesentinel.detection.wifi.SecurityType
import com.bp22intel.edgesentinel.detection.wifi.WifiScanSnapshot
import com.bp22intel.edgesentinel.detection.wifi.WifiThreatDetector
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.WifiThreatType
import com.bp22intel.edgesentinel.travel.DataWipeManager
import com.bp22intel.edgesentinel.travel.LeftoverDatabaseCheck
import com.bp22intel.edgesentinel.travel.TravelExporter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException

/** In-memory stand-in for the Room table (the "disk" that survives a restart). */
class InMemorySightingDao : BssidSightingDao {
    val rows = LinkedHashMap<String, BssidSightingEntity>()
    override suspend fun getAll(): List<BssidSightingEntity> = rows.values.toList()
    override suspend fun upsertAll(records: List<BssidSightingEntity>) { records.forEach { rows[it.recordKey] = it } }
    override suspend fun deleteKeys(keys: List<String>) { keys.forEach { rows.remove(it) } }
    override suspend fun deleteAll() { rows.clear() }
}

class Round4Test {
    @Before fun setUp() = WipeInProgress.resetForTests()
    @After fun tearDown() = WipeInProgress.resetForTests()

    // ── D4: one choke point for every DAO ──────────────────────────────────

    private fun countingHelper(calls: AtomicInteger): SupportSQLiteOpenHelper =
        Proxy.newProxyInstance(SupportSQLiteOpenHelper::class.java.classLoader,
            arrayOf(SupportSQLiteOpenHelper::class.java)) { _, m, _ ->
            if (m.name == "getWritableDatabase" || m.name == "getReadableDatabase") calls.incrementAndGet()
            if (m.name == "getDatabaseName") "edge_sentinel.db" else null
        } as SupportSQLiteOpenHelper

    /** Builds the generated Room database without Android: the guard wraps a counting delegate. */
    private fun guardedDatabase(calls: AtomicInteger): EdgeSentinelDatabase {
        val implClass = Class.forName("com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase_Impl")
        @Suppress("UNCHECKED_CAST")
        val db = allocateWithoutConstructor(implClass) as EdgeSentinelDatabase
        fun set(name: String, value: Any?) {
            val f = RoomDatabase::class.java.getDeclaredField(name); f.isAccessible = true; f.set(db, value)
        }
        set("internalOpenHelper", WipeGuardOpenHelper(countingHelper(calls)))
        set("internalTransactionExecutor", Executor { it.run() })
        set("internalQueryExecutor", Executor { it.run() })
        set("allowMainThreadQueries", true)
        // Field initializers did not run (no constructor): restore the ones Room needs.
        set("backingFieldMap", java.util.Collections.synchronizedMap(mutableMapOf<String, Any>()))
        set("typeConverters", mutableMapOf<Class<*>, Any>())
        set("suspendingTransactionId", ThreadLocal<Int>())
        set("readWriteLock", java.util.concurrent.locks.ReentrantReadWriteLock())
        return db
    }

    private fun ble() = BleDeviceEntity(macAddress = "aa:bb", advertisingDataHash = "h", manufacturerId = null,
        deviceName = null, firstSeen = 1, lastSeen = 1, locationClusters = "", seenCount = 1,
        isTrackerType = false, trackerProtocol = null)

    @Test fun wipeGuard_blocksDirectDaoWrite() = runTest {
        val calls = AtomicInteger()
        val db = guardedDatabase(calls)
        // A direct DAO (BleDeviceTracker path), not a repository: generated Room code.
        val dao: BleDeviceDao = db.bleDeviceDao()
        WipeInProgress.begin()
        try {
            dao.insert(ble())
            fail("direct DAO write must be blocked during a panic wipe")
        } catch (e: Exception) {
            assertTrue("expected WipeInProgressException, got $e", WipeInProgress.isWipeBlock(e))
        }
        assertEquals("the real open helper must never be reached", 0, calls.get())
    }

    @Test fun wipeGuard_passesThroughWhenNotWiping() = runTest {
        val calls = AtomicInteger()
        val db = guardedDatabase(calls)
        try { db.bleDeviceDao().insert(ble()) } catch (_: Throwable) { /* fake delegate returns null */ }
        assertTrue("without the gate the delegate is used", calls.get() > 0)
    }

    @Test fun wipeGuard_blocksReadableAndWritableGetters() {
        val calls = AtomicInteger()
        val helper = WipeGuardOpenHelper(countingHelper(calls))
        WipeInProgress.begin()
        for (getter in listOf({ helper.writableDatabase }, { helper.readableDatabase })) {
            try { getter(); fail("must throw") } catch (_: WipeInProgressException) { }
        }
        assertEquals(0, calls.get())
    }

    @Test fun leftoverCheck_deletesRecreatedDbAndRecordsError() {
        val dir = Files.createTempDirectory("leftover").toFile()
        val db = File(dir, "edge_sentinel.db").apply { writeText("recreated") }
        val missing = File(dir, "edge_sentinel.db-wal")
        val errors = LeftoverDatabaseCheck.run(listOf(db, missing))
        assertFalse(db.exists())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("edge_sentinel.db"))
        assertTrue(LeftoverDatabaseCheck.run(listOf(db, missing)).isEmpty())
    }

    // ── D2: persisted BSSID records ────────────────────────────────────────

    private val trusted = Proxy.newProxyInstance(TrustedNetworkDao::class.java.classLoader,
        arrayOf(TrustedNetworkDao::class.java)) { _, _, _ -> throw UnsupportedOperationException() } as TrustedNetworkDao
    private fun ap(b: String) = ObservedAp(bssid = b, ssid = "CorpNet", signalStrength = -70, frequency = 2437,
        securityType = SecurityType.WPA2, capabilities = "")
    private val a = ap("aa:aa:aa:aa:aa:01"); private val b = ap("aa:aa:aa:aa:aa:02")
    private fun evil(d: WifiThreatDetector, ts: Long) =
        d.analyze(WifiScanSnapshot(listOf(a, b), ts), emptyList()).any { it.threatType == WifiThreatType.EVIL_TWIN }

    @Test fun bssidRecords_surviveProcessRestart() = runTest {
        val disk = InMemorySightingDao()
        val t0 = 1_700_000_000_000L
        // Process 1: two scans in one session.
        val p1 = WifiThreatDetector(trusted, disk).also { it.sessionId = 1L; it.loadPersistedSightings() }
        assertTrue(evil(p1, t0)); assertTrue(evil(p1, t0 + 15_000))
        p1.persistSightings()
        assertEquals(2, disk.rows.size)
        assertEquals(2, disk.rows.values.first().sightings)
        // Process 2 (restart): records are loaded from the persisted store.
        val p2 = WifiThreatDetector(trusted, disk).also { it.sessionId = 2L; it.loadPersistedSightings() }
        assertTrue("2 scans, 1 session: not yet known", evil(p2, t0 + 60_000))
        p2.persistSightings()
        assertEquals(2, disk.rows.values.first().sessions)
        assertFalse("3 scans across 2 sessions: known after restart", evil(p2, t0 + 75_000))
        // Control: without the persisted store the same scan still fires.
        val fresh = WifiThreatDetector(trusted, InMemorySightingDao()).also { it.sessionId = 3L }
        assertTrue(evil(fresh, t0 + 75_000))
    }

    @Test fun bssidRecords_threeScansOneSessionNotKnown() = runTest {
        val d = WifiThreatDetector(trusted, InMemorySightingDao()).also { it.sessionId = 9L }
        val t0 = 1_700_000_000_000L
        for (i in 0..3) assertTrue(evil(d, t0 + i * 15_000L))
    }

    // ── D3: travel wipe covers every promised table ───────────────────────

    @Test fun travelWipe_deletesAllPromisedTables() {
        val executed = mutableListOf<Pair<String, Array<Any>>>()
        val errors = mutableListOf<String>()
        val n = DataWipeManager.runTravelWipe(1234L, errors) { sql, args -> executed += sql to args }
        assertTrue(errors.isEmpty())
        assertEquals(DataWipeManager.TRAVEL_WIPE_SQL.size, n)
        val byTable = executed.associate { (sql, args) ->
            Regex("""DELETE FROM (\w+) WHERE (\w+) >= \?""").find(sql)!!.groupValues.let { it[1] to it[2] }
                .also { assertEquals(1234L, args.single()) }
        }
        for (t in listOf("alerts", "scans", "cells", "estimated_tower_positions", "baselines", "ble_devices"))
            assertTrue("travel wipe must delete $t", t in byTable)
        assertEquals("cells must be deleted by last_seen", "last_seen", byTable["cells"])
        // Every table/column exists in the exported v10 schema.
        val schema = moduleFile("schemas/com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase/10.json").readText()
        for ((table, col) in byTable) {
            val start = schema.indexOf("\"tableName\": \"$table\"")
            assertTrue("$table missing from schema 10", start >= 0)
            val createSql = Regex("\"createSql\": \"([^\"]+)\"").find(schema, start)!!.groupValues[1]
            assertTrue("$table.$col missing", createSql.contains("`$col`"))
        }
        assertEquals(0, DataWipeManager.runTravelWipe(0L, errors) { _, _ -> fail() })
    }

    @Test fun travelWipe_failureOfOneTableIsRecordedAndOthersStillRun() {
        val errors = mutableListOf<String>()
        val n = DataWipeManager.runTravelWipe(5L, errors) { sql, _ -> if (sql.contains("baselines")) error("boom") }
        assertEquals(DataWipeManager.TRAVEL_WIPE_SQL.size - 1, n)
        assertEquals(1, errors.size)
    }

    @Test fun migration9to10_matchesExportedSchema() {
        val schema = moduleFile("schemas/com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase/10.json").readText()
        val start = schema.indexOf("\"tableName\": \"wifi_bssid_sightings\"")
        assertTrue(start >= 0)
        val createSql = Regex("\"createSql\": \"([^\"]+)\"").find(schema, start)!!.groupValues[1]
            .replace("\${TABLE_NAME}", "wifi_bssid_sightings")
        assertEquals(createSql, Migrations.CREATE_WIFI_BSSID_SIGHTINGS_V10)
        assertTrue(Migrations.ALL.any { it.startVersion == 9 && it.endVersion == 10 })
        assertTrue(schema.contains("\"version\": 10"))
    }

    // ── D5: header is GCM AAD ──────────────────────────────────────────────

    @Test fun decryptFailsWhenHeaderTampered_AAD() {
        val ex = allocateWithoutConstructor(TravelExporter::class.java)
        val enc = ex.encrypt("secret".toByteArray(), "correct horse")
        assertEquals("secret", String(ex.decrypt(enc, "correct horse")))
        // Bypass the header pre-check: decrypt with the right key and IV but a
        // different AAD. GCM must reject it, proving the header is authenticated.
        val h = TravelExporter.FILE_HEADER.size
        val salt = enc.copyOfRange(h, h + 32); val iv = enc.copyOfRange(h + 32, h + 44)
        val ct = enc.copyOfRange(h + 44, enc.size)
        val key = ex.deriveKey("correct horse", salt)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(0x45, 0x53, 0x02, 0x02))
        try { cipher.doFinal(ct); fail("tampered AAD must fail") } catch (_: AEADBadTagException) { }
        // The full decrypt path also rejects a tampered header.
        val tampered = enc.copyOf().also { it[3] = 0x02 }
        try { ex.decrypt(tampered, "correct horse"); fail("tampered header must fail") } catch (_: Exception) { }
    }

    // ── D6: N8 fallback skips sentinel signals ─────────────────────────────

    @Test fun n8FallbackSkipsSentinelSignal() = runTest {
        val hist = listOf(cell(500, NetworkType.LTE), cell(501, NetworkType.LTE))
        // No cell is registered. The GSM cell reports CellInfo.UNAVAILABLE (Int.MAX_VALUE).
        // Before D6 it won maxWith and a false 4G->2G downgrade fired.
        val cells = listOf(cell(500, NetworkType.LTE, signal = -90), cell(900, NetworkType.GSM, signal = Int.MAX_VALUE))
        assertNull(NetworkDowngradeDetector().analyze(cells, hist))
        // A real strongest 2G serving cell still fires.
        val real = listOf(cell(500, NetworkType.LTE, signal = -110), cell(900, NetworkType.GSM, signal = -60))
        assertTrue(NetworkDowngradeDetector().analyze(real, hist) != null)
    }
}

private fun moduleFile(rel: String): File =
    listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
        ?: error("cannot find $rel from ${File(".").absolutePath}")
