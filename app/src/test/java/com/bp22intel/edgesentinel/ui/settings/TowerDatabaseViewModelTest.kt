/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.bp22intel.edgesentinel.allocateWithoutConstructor
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import com.bp22intel.edgesentinel.detection.tower.CountryManifestEntry
import com.bp22intel.edgesentinel.detection.tower.DefaultOpenCelliDApi
import com.bp22intel.edgesentinel.detection.tower.DownloadResult
import com.bp22intel.edgesentinel.detection.tower.ManifestParser
import com.bp22intel.edgesentinel.detection.tower.MirrorClient
import com.bp22intel.edgesentinel.detection.tower.MirrorDownloadResult
import com.bp22intel.edgesentinel.detection.tower.MirrorManifest
import com.bp22intel.edgesentinel.detection.tower.OpenCelliDApi
import com.bp22intel.edgesentinel.detection.tower.TowerDatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TowerDatabaseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeKnownTowerDao
    private lateinit var fakeContext: FakeContext
    private lateinit var manager: TowerDatabaseManager
    private lateinit var fakeMirrorClient: FakeMirrorClient
    private lateinit var fakeApi: FakeOpenCelliDApi

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeKnownTowerDao()
        fakeContext = createFakeContext()
        manager = TowerDatabaseManager(fakeContext, fakeDao, testDispatcher)
        fakeMirrorClient = FakeMirrorClient()
        fakeApi = FakeOpenCelliDApi()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        mirrorClient: MirrorClient = fakeMirrorClient,
        api: OpenCelliDApi = fakeApi,
        token: String? = null
    ): TowerDatabaseViewModel {
        if (token != null) {
            fakeContext.getSharedPreferences("edge_sentinel", Context.MODE_PRIVATE)
                .edit().putString("opencellid_token", token).commit()
        }
        return TowerDatabaseViewModel(
            towerDatabaseManager = manager,
            context = fakeContext,
            mirrorClient = mirrorClient,
            openCelliDApi = api,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun manifestParse_parsesAllFieldsCorrectly() {
        val sampleJson = """
        {
          "schema": 1,
          "generated": "2026-10-18T16:29:58Z",
          "source": "OpenCelliD (opencellid.org), CC BY-SA 4.0",
          "license_url": "https://creativecommons.org/licenses/by-sa/4.0/",
          "base_url": "https://github.com/edge-sentinel-crypto/edge-sentinel/releases/download/tower-data/",
          "countries": [
            {
              "iso2": "CA",
              "name": "Canada",
              "mccs": [302],
              "file": "towers-CA.csv.gz",
              "rows": 60138,
              "bytes": 833806,
              "sha256": "e9cc42efdc9acb8bfd7ffeb41b88ec509a14d0d931e8ac1253a68fa2e321a294",
              "data_date": "2026-10-17"
            },
            {
              "iso2": "DE",
              "name": "Germany",
              "mccs": [262],
              "file": "towers-DE.csv.gz",
              "rows": 120500,
              "bytes": 1540000,
              "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
              "data_date": "2026-10-16"
            }
          ]
        }
        """.trimIndent()

        val manifest = ManifestParser.parse(sampleJson)
        assertEquals(1, manifest.schema)
        assertEquals("2026-10-18T16:29:58Z", manifest.generated)
        assertEquals("OpenCelliD (opencellid.org), CC BY-SA 4.0", manifest.source)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", manifest.licenseUrl)
        assertEquals("https://github.com/edge-sentinel-crypto/edge-sentinel/releases/download/tower-data/", manifest.baseUrl)
        assertEquals(2, manifest.countries.size)

        val ca = manifest.countries[0]
        assertEquals("CA", ca.iso2)
        assertEquals("Canada", ca.name)
        assertEquals(listOf(302), ca.mccs)
        assertEquals("towers-CA.csv.gz", ca.file)
        assertEquals(60138, ca.rows)
        assertEquals(833806L, ca.bytes)
        assertEquals("e9cc42efdc9acb8bfd7ffeb41b88ec509a14d0d931e8ac1253a68fa2e321a294", ca.sha256)
        assertEquals("2026-10-17", ca.dataDate)

        val de = manifest.countries[1]
        assertEquals("DE", de.iso2)
        assertEquals("Germany", de.name)
        assertEquals(listOf(262), de.mccs)
        assertEquals(120500, de.rows)
        assertEquals(1540000L, de.bytes)
        assertEquals("2026-10-16", de.dataDate)
    }

    @Test
    fun pickerStatusLogic_notInstalled_installed_updateAvailable() {
        // Not installed
        assertEquals(
            CountryInstallStatus.NOT_INSTALLED,
            TowerDatabaseViewModel.computeCountryStatus(
                isInstalled = false,
                installedDate = null,
                manifestDataDate = "2026-10-17"
            )
        )

        // Installed, data date is equal -> INSTALLED
        assertEquals(
            CountryInstallStatus.INSTALLED,
            TowerDatabaseViewModel.computeCountryStatus(
                isInstalled = true,
                installedDate = "2026-10-17",
                manifestDataDate = "2026-10-17"
            )
        )

        // Installed, manifest data date is newer -> UPDATE_AVAILABLE
        assertEquals(
            CountryInstallStatus.UPDATE_AVAILABLE,
            TowerDatabaseViewModel.computeCountryStatus(
                isInstalled = true,
                installedDate = "2026-10-10",
                manifestDataDate = "2026-10-17"
            )
        )

        // Installed, manifest data date is older -> INSTALLED
        assertEquals(
            CountryInstallStatus.INSTALLED,
            TowerDatabaseViewModel.computeCountryStatus(
                isInstalled = true,
                installedDate = "2026-10-18",
                manifestDataDate = "2026-10-17"
            )
        )
    }

    @Test
    fun sha256Mismatch_blocksImport_andShowsError() = runTest(testDispatcher) {
        val germanyEntry = CountryManifestEntry(
            iso2 = "DE",
            name = "Germany",
            mccs = listOf(262),
            file = "towers-DE.csv.gz",
            rows = 100,
            bytes = 5000,
            sha256 = "expected_sha256",
            dataDate = "2026-10-17"
        )
        fakeMirrorClient.manifest = MirrorManifest(
            schema = 1,
            generated = "2026-10-18",
            source = "test",
            licenseUrl = "",
            baseUrl = "http://localhost/",
            countries = listOf(germanyEntry)
        )

        // Simulate sha256 mismatch from mirror client
        fakeMirrorClient.downloadHandler = { entry ->
            MirrorDownloadResult.Failure(
                message = "SHA-256 mismatch for ${entry.name}: expected ${entry.sha256}, got actual_sha256",
                isSha256Mismatch = true
            )
        }

        val vm = createViewModel(token = null)
        advanceUntilIdle()

        vm.installCountry("DE")
        advanceUntilIdle()

        assertEquals("No towers should be imported into DB on sha256 mismatch", 0, fakeDao.insertCalls)
        assertEquals(0, fakeDao.getTowerCount())
        assertNotNull("Error message must be shown", vm.error.value)
        assertTrue(
            "Error should state SHA-256 mismatch",
            vm.error.value?.contains("SHA-256") == true
        )
    }

    @Test
    fun mirrorFailure_fallsBackToOpenCelliD_onlyWhenTokenExists() = runTest(testDispatcher) {
        val germanyEntry = CountryManifestEntry(
            iso2 = "DE",
            name = "Germany",
            mccs = listOf(262),
            file = "towers-DE.csv.gz",
            rows = 1,
            bytes = 100,
            sha256 = "dummy_sha256",
            dataDate = "2026-10-17"
        )
        fakeMirrorClient.manifest = MirrorManifest(
            schema = 1,
            generated = "2026-10-18",
            source = "test",
            licenseUrl = "",
            baseUrl = "http://localhost/",
            countries = listOf(germanyEntry)
        )
        fakeMirrorClient.downloadHandler = {
            MirrorDownloadResult.Failure("HTTP 500 mirror internal server error")
        }

        // --- Part A: Without token, fallback is NOT attempted and error is shown ---
        val vmNoToken = createViewModel(token = null)
        advanceUntilIdle()

        vmNoToken.installCountry("DE")
        advanceUntilIdle()

        assertEquals("No OpenCelliD call without token", 0, fakeApi.downloadCalls.size)
        assertEquals("No towers imported", 0, fakeDao.getTowerCount())
        assertNotNull("Error message shown", vmNoToken.error.value)
        assertTrue(
            "Error states mirror failure",
            vmNoToken.error.value?.contains("Mirror download failed") == true
        )

        // --- Part B: With token, falls back to OpenCelliD and imports ---
        fakeApi.customHandler = { mcc, _ ->
            val csv = "radio,mcc,mnc,lac,cid,lon,lat,range,samples\n" +
                    "LTE,$mcc,1,1001,5001,13.4,52.5,1000,20\n"
            DownloadResult.Success(GZIPInputStream(createGzipCsv(csv)))
        }

        val vmWithToken = createViewModel(token = "test_token")
        advanceUntilIdle()

        vmWithToken.installCountry("DE")
        advanceUntilIdle()

        assertEquals("OpenCelliD fallback called once", 1, fakeApi.downloadCalls.size)
        assertEquals(262, fakeApi.downloadCalls[0].first)
        assertEquals("test_token", fakeApi.downloadCalls[0].second)
        assertEquals("1 tower imported via fallback", 1, fakeDao.getTowerCount())
        assertNotNull(fakeDao.findTower(262, 1, 1001, 5001))
        assertEquals("Germany towers installed", vmWithToken.importSuccess.value)
    }

    @Test
    fun jsonErrorBody_isRejected_andNotImported() {
        val jsonError = """{"status":"error","message":"FILE_NOT_FOUND"}"""
        val defaultApi = DefaultOpenCelliDApi()

        val stream = ByteArrayInputStream(jsonError.toByteArray(Charsets.UTF_8))
        val result = defaultApi.parseResponse(200, stream)

        assertTrue("JSON response must be parsed as Failure", result is DownloadResult.Failure)
        val failure = result as DownloadResult.Failure
        assertEquals("FILE_NOT_FOUND", failure.message)
        assertFalse(failure.isRateLimit)
    }

    @Test
    fun updateAll_skipsBaseCountries_andContinuesAfterSingleFailure() = runTest(testDispatcher) {
        val usEntry = CountryManifestEntry(
            iso2 = "US",
            name = "United States",
            mccs = listOf(310),
            file = "towers-US.csv.gz",
            rows = 1000,
            bytes = 100000,
            sha256 = "us_sha",
            dataDate = "2026-10-17"
        )
        val caEntry = CountryManifestEntry(
            iso2 = "CA",
            name = "Canada",
            mccs = listOf(302),
            file = "towers-CA.csv.gz",
            rows = 500,
            bytes = 50000,
            sha256 = "ca_sha",
            dataDate = "2026-10-17"
        )
        val deEntry = CountryManifestEntry(
            iso2 = "DE",
            name = "Germany",
            mccs = listOf(262),
            file = "towers-DE.csv.gz",
            rows = 200,
            bytes = 20000,
            sha256 = "de_sha",
            dataDate = "2026-10-17"
        )
        val frEntry = CountryManifestEntry(
            iso2 = "FR",
            name = "France",
            mccs = listOf(208),
            file = "towers-FR.csv.gz",
            rows = 300,
            bytes = 30000,
            sha256 = "fr_sha",
            dataDate = "2026-10-17"
        )

        fakeMirrorClient.manifest = MirrorManifest(
            schema = 1,
            generated = "2026-10-18",
            source = "test",
            licenseUrl = "",
            baseUrl = "http://localhost/",
            countries = listOf(usEntry, caEntry, deEntry, frEntry)
        )

        // Install towers for US (310), CA (302), Germany (262), France (208)
        fakeDao.towers.add(createTower(310, 260, 1, 101))
        fakeDao.towers.add(createTower(302, 720, 1, 201))
        fakeDao.towers.add(createTower(262, 1, 1, 301))
        fakeDao.towers.add(createTower(208, 1, 1, 401))

        val requestedCountries = mutableListOf<String>()
        fakeMirrorClient.downloadHandler = { entry ->
            requestedCountries.add(entry.iso2)
            if (entry.iso2 == "DE") {
                // Germany fails
                MirrorDownloadResult.Failure("HTTP 503 Service Unavailable")
            } else {
                // France succeeds
                val csv = "radio,mcc,mnc,lac,cid,lon,lat,range,samples\n" +
                        "LTE,${entry.mccs[0]},1,1,999,2.35,48.85,1000,10\n"
                val file = createTempGzipFile(csv)
                MirrorDownloadResult.Success(file, entry)
            }
        }

        val vm = createViewModel(token = null)
        advanceUntilIdle()

        vm.updateAllInstalledCountries()
        advanceUntilIdle()

        // Verify base countries US and CA were SKIPPED
        assertFalse("US must be skipped in update-all", requestedCountries.contains("US"))
        assertFalse("Canada must be skipped in update-all", requestedCountries.contains("CA"))

        // Both non-base countries DE and FR were requested (continues after DE failure)
        assertTrue("Germany was requested", requestedCountries.contains("DE"))
        assertTrue("France was requested after Germany failed", requestedCountries.contains("FR"))

        // France tower 999 was imported
        assertNotNull(fakeDao.findTower(208, 1, 1, 999))

        // Summary reported: 1 updated, 1 failed
        val errorMsg = vm.error.value
        assertNotNull("Summary must be reported", errorMsg)
        assertTrue("Summary contains '1 updated'", errorMsg?.contains("1 updated") == true)
        assertTrue("Summary contains '1 failed'", errorMsg?.contains("1 failed") == true)
        assertTrue("Summary mentions Germany", errorMsg?.contains("Germany") == true)
    }

    @Test
    fun guard_blocksConcurrentRuns() = runTest(testDispatcher) {
        val vm = createViewModel(token = "valid_token")
        advanceUntilIdle()

        val field = TowerDatabaseViewModel::class.java.getDeclaredField("_downloadingCountry")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        stateFlow.value = "Germany"

        vm.installCountry("FR")
        advanceUntilIdle()
        assertEquals("Install blocked while downloading", 0, fakeMirrorClient.downloadCalls.size)

        vm.updateAllInstalledCountries()
        advanceUntilIdle()
        assertEquals("Update-all blocked while downloading", 0, fakeMirrorClient.downloadCalls.size)

        vm.downloadSelected()
        advanceUntilIdle()
        assertEquals("Download-selected blocked while downloading", 0, fakeMirrorClient.downloadCalls.size)
    }

    @Test
    fun baseCountries_cannotBeSelectedOrRemoved() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Base countries cannot be selected
        vm.toggleSelection("US")
        vm.toggleSelection("CA")
        advanceUntilIdle()
        assertTrue("Base countries cannot be selected", vm.selectedIso2s.value.isEmpty())

        // Non-base country CAN be selected
        vm.toggleSelection("DE")
        advanceUntilIdle()
        assertTrue("Non-base country can be selected", vm.selectedIso2s.value.contains("DE"))

        // Deleting base country is ignored
        fakeDao.towers.add(createTower(310, 260, 1, 101))
        fakeDao.towers.add(createTower(302, 720, 1, 201))
        assertEquals(2, fakeDao.getTowerCount())

        vm.deleteCountry("US", listOf(310))
        vm.deleteCountry("CA", listOf(302))
        advanceUntilIdle()

        assertEquals("Base countries towers must not be deleted", 2, fakeDao.getTowerCount())
    }

    private fun createTower(mcc: Int, mnc: Int, lac: Int, cid: Int): KnownTowerEntity {
        return KnownTowerEntity(
            mcc = mcc,
            mnc = mnc,
            lac = lac,
            cid = cid,
            radio = "LTE",
            latitude = 0.0,
            longitude = 0.0,
            range = 1000,
            samples = 10
        )
    }

    private fun createGzipCsv(csvContent: String): InputStream {
        val baos = ByteArrayOutputStream()
        val gzos = GZIPOutputStream(baos)
        gzos.write(csvContent.toByteArray(Charsets.UTF_8))
        gzos.finish()
        return ByteArrayInputStream(baos.toByteArray())
    }

    private fun createTempGzipFile(csvContent: String): File {
        val file = File.createTempFile("test_tower", ".csv.gz")
        file.deleteOnExit()
        FileOutputStream(file).use { fos ->
            val gzos = GZIPOutputStream(fos)
            gzos.write(csvContent.toByteArray(Charsets.UTF_8))
            gzos.finish()
        }
        return file
    }

    private fun createFakeContext(): FakeContext {
        val ctx = allocateWithoutConstructor(FakeContext::class.java)
        val f = FakeContext::class.java.getDeclaredField("prefsMap")
        f.isAccessible = true
        f.set(ctx, ConcurrentHashMap<String, SharedPreferences>())
        return ctx
    }

    open class FakeContext : ContextWrapper(null) {
        val prefsMap = ConcurrentHashMap<String, SharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return prefsMap.computeIfAbsent(name) { FakeSharedPreferences() }
        }

        override fun getApplicationContext(): Context = this

        override fun getCacheDir(): File {
            val dir = File(System.getProperty("java.io.tmpdir"), "edgesentinel_cache")
            dir.mkdirs()
            return dir
        }
    }

    class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { pending[key!!] = this }
            override fun clear(): SharedPreferences.Editor = apply { pending.clear() }
            override fun commit(): Boolean {
                for ((k, v) in pending) {
                    if (v === this) data.remove(k) else if (v != null) data[k] = v
                }
                pending.clear()
                return true
            }
            override fun apply() { commit() }
        }
    }

    class FakeKnownTowerDao : KnownTowerDao {
        val towers = java.util.concurrent.CopyOnWriteArrayList<KnownTowerEntity>()
        var insertCalls = 0

        override suspend fun findTower(mcc: Int, mnc: Int, lac: Int, cid: Int): KnownTowerEntity? {
            return towers.find { it.mcc == mcc && it.mnc == mnc && it.lac == lac && it.cid == cid }
        }

        override fun observeTower(mcc: Int, mnc: Int, lac: Int, cid: Int): Flow<KnownTowerEntity?> {
            return flowOf(towers.find { it.mcc == mcc && it.mnc == mnc && it.lac == lac && it.cid == cid })
        }

        override suspend fun insertTowers(towers: List<KnownTowerEntity>) {
            insertCalls++
            for (tower in towers) {
                this.towers.removeAll {
                    it.mcc == tower.mcc && it.mnc == tower.mnc && it.lac == tower.lac && it.cid == tower.cid
                }
                this.towers.add(tower)
            }
        }

        override suspend fun getTowerCount(): Int = towers.size

        override suspend fun getTowerCountByCountry(mcc: Int): Int = towers.count { it.mcc == mcc }

        override suspend fun getTowerCountByCountries(mccs: List<Int>): Int = towers.count { it.mcc in mccs }

        override suspend fun deleteTowersByCountry(mcc: Int) {
            towers.removeAll { it.mcc == mcc }
        }

        override suspend fun deleteTowersByCountries(mccs: List<Int>) {
            towers.removeAll { it.mcc in mccs }
        }

        override suspend fun getInstalledCountries(): List<Int> = towers.map { it.mcc }.distinct().sorted()

        override suspend fun findTowersInArea(
            minLat: Double,
            maxLat: Double,
            minLng: Double,
            maxLng: Double
        ): List<KnownTowerEntity> {
            return towers.filter { it.latitude in minLat..maxLat && it.longitude in minLng..maxLng }
        }
    }

    class FakeMirrorClient : MirrorClient {
        var manifest: MirrorManifest? = null
        var downloadCalls = mutableListOf<CountryManifestEntry>()
        var downloadHandler: ((CountryManifestEntry) -> MirrorDownloadResult)? = null

        override suspend fun getManifest(forceRefresh: Boolean): Result<MirrorManifest> {
            return manifest?.let { Result.success(it) } ?: Result.failure(Exception("Manifest not set"))
        }

        override suspend fun downloadCountry(
            entry: CountryManifestEntry,
            cacheDir: File
        ): MirrorDownloadResult {
            downloadCalls.add(entry)
            return downloadHandler?.invoke(entry) ?: MirrorDownloadResult.Failure("No downloadHandler configured")
        }
    }

    class FakeOpenCelliDApi : OpenCelliDApi {
        val downloadCalls = mutableListOf<Pair<Int, String>>()
        var customHandler: ((Int, String) -> DownloadResult)? = null

        override suspend fun download(mcc: Int, token: String): DownloadResult {
            downloadCalls.add(Pair(mcc, token))
            return customHandler?.invoke(mcc, token) ?: DownloadResult.Failure("No handler configured")
        }
    }
}
