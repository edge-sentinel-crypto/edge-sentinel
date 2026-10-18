/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.tower

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.bp22intel.edgesentinel.allocateWithoutConstructor
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

class TowerDatabaseManagerTest {

    private lateinit var fakeContext: FakeContext
    private lateinit var fakeDao: FakeKnownTowerDao
    private lateinit var manager: TowerDatabaseManager

    @Before
    fun setUp() {
        fakeContext = createFakeContext()
        fakeDao = FakeKnownTowerDao()
        manager = TowerDatabaseManager(fakeContext, fakeDao)
    }

    @Test
    fun freshInstall_importsAndStoresVersion() = runBlocking {
        // Given: Fresh install with no stored version and 0 towers in database
        val usCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,5001,-73.985,40.748,1000,25")
            appendLine("LTE,311,480,1002,5002,-122.419,37.774,1500,50")
        }
        val caCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,302,720,2001,6001,-79.383,43.653,1200,30")
        }
        manager.assetOpener = { name ->
            if (name.contains("ca")) createGzipCsv(caCsv) else createGzipCsv(usCsv)
        }

        val prefs = manager.getPrefs()
        assertFalse(prefs.contains(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION))
        assertEquals(0, fakeDao.getTowerCount())

        // When
        manager.autoImportBundledData()

        // Then: Towers imported and version stored as BUNDLED_TOWER_DATA_VERSION (3)
        assertEquals(3, fakeDao.getTowerCount())
        assertNotNull(fakeDao.findTower(310, 260, 1001, 5001))
        assertNotNull(fakeDao.findTower(311, 480, 1002, 5002))
        assertNotNull(fakeDao.findTower(302, 720, 2001, 6001))
        assertTrue(prefs.contains(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION))
        assertEquals(
            TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION,
            prefs.getInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 0)
        )
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
        assertEquals(TowerDatabaseManager.BUNDLED_DATA_DATE, manager.getInstalledDate("US"))
        assertEquals(TowerDatabaseManager.BUNDLED_DATA_DATE, manager.getInstalledDate("CA"))
    }

    @Test
    fun staleVersion_triggersDeleteBundledPlusReImport() = runBlocking {
        // Given: Stored version 1 in SharedPreferences and old US towers in DAO
        val prefs = manager.getPrefs()
        prefs.edit().putInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 1).commit()

        val oldUsTower = KnownTowerEntity(
            mcc = 310, mnc = 260, lac = 1001, cid = 1111,
            latitude = 40.71, longitude = -74.00, range = 1000, radio = "LTE", samples = 20
        )
        fakeDao.insertTowers(listOf(oldUsTower))
        assertEquals(1, fakeDao.getTowerCount())

        val newUsCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,2222,-73.985,40.748,1000,55")
        }
        val newCaCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,302,720,2001,3333,-79.383,43.653,1000,45")
        }
        manager.assetOpener = { name ->
            if (name.contains("ca")) createGzipCsv(newCaCsv) else createGzipCsv(newUsCsv)
        }

        // When
        manager.autoImportBundledData()

        // Then: Old US tower was deleted, new US and CA towers were imported, version updated to 3
        assertEquals(2, fakeDao.getTowerCount())
        assertNull(fakeDao.findTower(310, 260, 1001, 1111))
        assertNotNull(fakeDao.findTower(310, 260, 1001, 2222))
        assertNotNull(fakeDao.findTower(302, 720, 2001, 3333))
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
        assertEquals(TowerDatabaseManager.BUNDLED_MCCS, fakeDao.deleteTowersByCountriesCalledWith)
    }

    @Test
    fun bundledUpgrade_v2ToV3_deletesOnly302And310To316Rows_andKeepsOthers() = runBlocking {
        // Given: Existing install (version 2) containing US (310), CA (302), and user-imported foreign towers (UK 234, Germany 262)
        val prefs = manager.getPrefs()
        prefs.edit().putInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 2).commit()

        val oldUsTower = KnownTowerEntity(
            mcc = 310, mnc = 260, lac = 1001, cid = 1111,
            latitude = 40.71, longitude = -74.00, range = 1000, radio = "LTE", samples = 20
        )
        val oldCanadaTower = KnownTowerEntity(
            mcc = 302, mnc = 720, lac = 2001, cid = 3001,
            latitude = 43.65, longitude = -79.38, range = 1200, radio = "LTE", samples = 35
        )
        val ukTower = KnownTowerEntity(
            mcc = 234, mnc = 15, lac = 3001, cid = 4001,
            latitude = 51.50, longitude = -0.12, range = 1500, radio = "LTE", samples = 40
        )
        val germanyTower = KnownTowerEntity(
            mcc = 262, mnc = 1, lac = 4001, cid = 5001,
            latitude = 52.52, longitude = 13.40, range = 1100, radio = "LTE", samples = 18
        )
        fakeDao.insertTowers(listOf(oldUsTower, oldCanadaTower, ukTower, germanyTower))
        assertEquals(4, fakeDao.getTowerCount())

        val newUsCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,410,1001,9999,-73.985,40.748,1000,80")
        }
        val newCaCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,302,720,2001,8888,-79.383,43.653,1000,60")
        }
        manager.assetOpener = { name ->
            if (name.contains("ca")) createGzipCsv(newCaCsv) else createGzipCsv(newUsCsv)
        }

        // When
        manager.autoImportBundledData()

        // Then: Old US (310) and old CA (302) were deleted, new US and CA were imported, UK and Germany survived!
        assertEquals(4, fakeDao.getTowerCount())
        assertNull("Old US tower must be removed", fakeDao.findTower(310, 260, 1001, 1111))
        assertNotNull("New US tower must be present", fakeDao.findTower(310, 410, 1001, 9999))
        assertNull("Old Canada tower must be replaced", fakeDao.findTower(302, 720, 2001, 3001))
        assertNotNull("New Canada tower must be present", fakeDao.findTower(302, 720, 2001, 8888))
        assertNotNull("UK tower must survive", fakeDao.findTower(234, 15, 3001, 4001))
        assertNotNull("Germany tower must survive", fakeDao.findTower(262, 1, 4001, 5001))
        assertEquals(TowerDatabaseManager.BUNDLED_MCCS, fakeDao.deleteTowersByCountriesCalledWith)
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
    }

    @Test
    fun legacyInstallWithTowers_butNoStoredVersion_countsAsVersion1_andRefreshes() = runBlocking {
        // Given: Install with towers loaded but no stored version in SharedPreferences
        val prefs = manager.getPrefs()
        assertFalse(prefs.contains(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION))

        val oldUsTower = KnownTowerEntity(
            mcc = 310, mnc = 260, lac = 1001, cid = 1111,
            latitude = 40.71, longitude = -74.00, range = 1000, radio = "LTE", samples = 20
        )
        val foreignTower = KnownTowerEntity(
            mcc = 262, mnc = 1, lac = 2001, cid = 3001,
            latitude = 52.52, longitude = 13.40, range = 1200, radio = "LTE", samples = 35
        )
        fakeDao.insertTowers(listOf(oldUsTower, foreignTower))
        assertEquals(2, fakeDao.getTowerCount())
        assertEquals(1, manager.getEffectiveBundledVersion())

        val newUsCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,8888,-73.985,40.748,1000,90")
        }
        val newCaCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,302,720,2001,7777,-79.383,43.653,1000,50")
        }
        manager.assetOpener = { name ->
            if (name.contains("ca")) createGzipCsv(newCaCsv) else createGzipCsv(newUsCsv)
        }

        // When
        manager.autoImportBundledData()

        // Then: Counts as version 1, refreshes base bundle, preserves foreign tower, and updates version to 3
        assertEquals(3, fakeDao.getTowerCount())
        assertNull(fakeDao.findTower(310, 260, 1001, 1111))
        assertNotNull(fakeDao.findTower(310, 260, 1001, 8888))
        assertNotNull(fakeDao.findTower(302, 720, 2001, 7777))
        assertNotNull(fakeDao.findTower(262, 1, 2001, 3001))
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
    }

    @Test
    fun equalVersion_skipsAutoImport() = runBlocking {
        // Given: Stored version is equal to BUNDLED_TOWER_DATA_VERSION (3)
        val prefs = manager.getPrefs()
        prefs.edit().putInt(
            TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION,
            TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION
        ).commit()

        val tower = KnownTowerEntity(
            mcc = 310, mnc = 260, lac = 1001, cid = 7777,
            latitude = 40.71, longitude = -74.00, range = 1000, radio = "LTE", samples = 20
        )
        fakeDao.insertTowers(listOf(tower))

        // Asset opener should never be called when skipping
        manager.assetOpener = {
            fail("assetOpener should not be invoked when version is already equal")
            throw AssertionError("Skipped")
        }

        // When
        manager.autoImportBundledData()

        // Then: Nothing was changed or deleted
        assertEquals(1, fakeDao.getTowerCount())
        assertNotNull(fakeDao.findTower(310, 260, 1001, 7777))
        assertNull(fakeDao.deleteTowersByCountriesCalledWith)
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
    }

    @Test
    fun thrownImport_leavesVersionUnchanged_andRetriesOnNextLaunch() = runBlocking {
        // Given: Stored version 2
        val prefs = manager.getPrefs()
        prefs.edit().putInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 2).commit()

        // Asset opener fails with IOException mid-import
        manager.assetOpener = {
            throw IOException("Simulated asset reading error / corrupted bundle")
        }

        // When: Import fails with an exception
        try {
            manager.autoImportBundledData()
            fail("autoImportBundledData should rethrow the exception on failure")
        } catch (e: IOException) {
            assertEquals("Simulated asset reading error / corrupted bundle", e.message)
        }

        // Then: Version remains 2 (not upgraded to 3)
        assertEquals(2, manager.getStoredBundledVersion())

        // And on next launch, when asset is fixed, it retries and succeeds:
        val fixedUsCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,6666,-73.985,40.748,1000,40")
        }
        val fixedCaCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,302,720,2001,5555,-79.383,43.653,1000,40")
        }
        manager.assetOpener = { name ->
            if (name.contains("ca")) createGzipCsv(fixedCaCsv) else createGzipCsv(fixedUsCsv)
        }

        manager.autoImportBundledData()

        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
        assertNotNull(fakeDao.findTower(310, 260, 1001, 6666))
        assertNotNull(fakeDao.findTower(302, 720, 2001, 5555))
    }

    @Test
    fun baseCountries_areNotRemovable() = runBlocking {
        // Base countries: US (310 to 316) and Canada (302)
        val usTower = KnownTowerEntity(
            mcc = 310, mnc = 260, lac = 1001, cid = 1111,
            latitude = 40.71, longitude = -74.00, range = 1000, radio = "LTE", samples = 20
        )
        val caTower = KnownTowerEntity(
            mcc = 302, mnc = 720, lac = 2001, cid = 2222,
            latitude = 43.65, longitude = -79.38, range = 1200, radio = "LTE", samples = 30
        )
        val deTower = KnownTowerEntity(
            mcc = 262, mnc = 1, lac = 3001, cid = 3333,
            latitude = 52.52, longitude = 13.40, range = 1000, radio = "LTE", samples = 25
        )
        fakeDao.insertTowers(listOf(usTower, caTower, deTower))
        assertEquals(3, fakeDao.getTowerCount())

        // Attempting to delete US or Canada must be rejected
        manager.deleteCountry(310)
        manager.deleteCountry(302)
        manager.deleteCountries(listOf(310, 311, 302))

        assertEquals(3, fakeDao.getTowerCount())
        assertNotNull("US tower must not be removed", fakeDao.findTower(310, 260, 1001, 1111))
        assertNotNull("Canada tower must not be removed", fakeDao.findTower(302, 720, 2001, 2222))

        // Deleting non-base country (Germany 262) succeeds
        manager.deleteCountry(262)
        assertEquals(2, fakeDao.getTowerCount())
        assertNull("Germany tower should be removed", fakeDao.findTower(262, 1, 3001, 3333))
    }

    @Test
    fun concurrent_autoImportBundledData_runsOnce() = runBlocking {
        val usCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,5001,-73.985,40.748,1000,25")
        }
        val caCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,302,720,2001,6001,-79.383,43.653,1200,30")
        }

        val openCount = AtomicInteger(0)
        manager.assetOpener = { name ->
            openCount.incrementAndGet()
            Thread.sleep(20) // simulate IO
            if (name.contains("ca")) createGzipCsv(caCsv) else createGzipCsv(usCsv)
        }

        // Launch 10 concurrent calls
        val jobs = (1..10).map {
            async {
                manager.autoImportBundledData()
            }
        }
        jobs.awaitAll()

        // Asset opener should have been called exactly 2 times (once for US, once for CA)
        assertEquals(2, openCount.get())
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
    }

    private fun createGzipCsv(csvContent: String): InputStream {
        val baos = ByteArrayOutputStream()
        val gzos = GZIPOutputStream(baos)
        gzos.write(csvContent.toByteArray(Charsets.UTF_8))
        gzos.finish()
        return ByteArrayInputStream(baos.toByteArray())
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
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { pending[key!!] = this }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) data.clear()
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
        val towers = mutableListOf<KnownTowerEntity>()
        var deleteTowersByCountriesCalledWith: List<Int>? = null

        override suspend fun findTower(mcc: Int, mnc: Int, lac: Int, cid: Int): KnownTowerEntity? {
            return towers.find { it.mcc == mcc && it.mnc == mnc && it.lac == lac && it.cid == cid }
        }

        override fun observeTower(mcc: Int, mnc: Int, lac: Int, cid: Int): Flow<KnownTowerEntity?> {
            return flowOf(towers.find { it.mcc == mcc && it.mnc == mnc && it.lac == lac && it.cid == cid })
        }

        override suspend fun insertTowers(towers: List<KnownTowerEntity>) {
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
            deleteTowersByCountriesCalledWith = mccs
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
}
