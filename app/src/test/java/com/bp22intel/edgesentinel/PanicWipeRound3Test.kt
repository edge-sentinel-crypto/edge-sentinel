package com.bp22intel.edgesentinel

import com.bp22intel.edgesentinel.data.WipeInProgress
import com.bp22intel.edgesentinel.data.WipeInProgressException
import com.bp22intel.edgesentinel.travel.PanicWipeSequence
import com.bp22intel.edgesentinel.travel.SecureDeleter
import com.bp22intel.edgesentinel.travel.WipeResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PanicWipeRound3Test {

    @Before fun setUp() = WipeInProgress.resetForTests()
    @After fun tearDown() = WipeInProgress.resetForTests()

    @Test
    fun deleteFailure_returnsSuccessFalseWithErrors() {
        val dir = Files.createTempDirectory("wipe").toFile()
        val keep = File(dir, "secret.db").apply { writeText("sensitive") }
        File(dir, "other.txt").writeText("x")
        val deleter = SecureDeleter(passes = 1, deleter = { f -> if (f.name == "secret.db") false else f.delete() })
        val result = deleter.wipe(listOf(dir))
        assertFalse(result.success)
        assertTrue(result.errors.any { it.contains("secret.db") })
        assertTrue(result.errors.any { it.contains("directory") })
        assertTrue(keep.exists())
        dir.deleteRecursively()
    }

    @Test
    fun deleteSuccess_returnsSuccessTrueOnlyWhenNoErrors() {
        val dir = Files.createTempDirectory("wipe").toFile()
        File(dir, "a.txt").writeText("abc")
        val result = SecureDeleter(passes = 1).wipe(listOf(dir))
        assertTrue(result.success)
        assertTrue(result.errors.isEmpty())
        assertFalse(dir.exists())
        assertFalse(WipeResult.of(0, listOf("e")).success)
    }

    @Test
    fun stopTimeout_isRecorded_andDbNotClosedWhileWritersActive() = runTest {
        var closed = false
        val seq = PanicWipeSequence(
            stopService = { false },                       // service stop timed out
            cancelWorker = { throw IllegalStateException("wm boom") },
            writersActive = { true },                      // a writer job is still running
            closeDatabase = { closed = true },
            wipe = { WipeResult.of(1, emptyList()) },
            writerDrainTimeoutMs = 200L, pollMs = 10L
        )
        val result = seq.run()
        assertFalse(closed)
        assertFalse(seq.databaseClosed)
        assertFalse(result.success)
        assertTrue(result.errors.any { it.contains("did not stop") })
        assertTrue(result.errors.any { it.contains("wm boom") })
        assertTrue(result.errors.any { it.contains("Writers still active") })
        assertTrue(WipeInProgress.isActive)
    }

    @Test
    fun cleanStop_closesDb_andRetriesFailedWipeOnce() = runTest {
        var closed = false
        var calls = 0
        val seq = PanicWipeSequence(
            stopService = { true }, cancelWorker = { true },
            writersActive = { false }, closeDatabase = { closed = true },
            wipe = { calls++; if (calls == 1) WipeResult.of(0, listOf("locked")) else WipeResult.of(2, emptyList()) },
            writerDrainTimeoutMs = 100L, pollMs = 10L
        )
        val result = seq.run()
        assertTrue(closed)
        assertEquals(2, calls)
        assertTrue(result.success)
    }

    @Test
    fun wipeInProgress_blocksRepositoryWrites() = runTest {
        var wrote = false
        WipeInProgress.access { wrote = true }
        assertTrue(wrote)
        wrote = false
        WipeInProgress.begin()
        try {
            WipeInProgress.access { wrote = true }
            fail("write was not blocked")
        } catch (_: WipeInProgressException) { }
        assertFalse(wrote)
        assertEquals(0, WipeInProgress.activeOperations)
    }

    @Test
    fun wipeInProgress_blocksRealRepositoryInsert() = runTest {
        WipeInProgress.begin()
        val dao = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.bp22intel.edgesentinel.data.local.dao.ScanDao::class.java)
        ) { _, _, _ -> throw AssertionError("DAO touched during wipe") } as com.bp22intel.edgesentinel.data.local.dao.ScanDao
        val repo = com.bp22intel.edgesentinel.data.repository.ScanRepositoryImpl(dao)
        try {
            repo.insertScan(com.bp22intel.edgesentinel.domain.model.ScanResult(
                timestamp = 1L, cellCount = 0,
                threatLevel = com.bp22intel.edgesentinel.domain.model.ThreatLevel.CLEAR, durationMs = 0L))
            fail("insert was not blocked")
        } catch (_: WipeInProgressException) { }
    }
}
