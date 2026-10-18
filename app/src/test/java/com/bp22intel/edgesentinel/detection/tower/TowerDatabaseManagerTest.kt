package com.bp22intel.edgesentinel.detection.tower

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.bp22intel.edgesentinel.allocateWithoutConstructor
import com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
import com.bp22intel.edgesentinel.data.local.entity.KnownTowerEntity
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
        val csv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,5001,-73.985,40.748,1000,25")
            appendLine("LTE,311,480,1002,5002,-122.419,37.774,1500,50")
        }
        manager.assetOpener = { createGzipCsv(csv) }

        val prefs = manager.getPrefs()
        assertFalse(prefs.contains(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION))
        assertEquals(0, fakeDao.getTowerCount())

        // When
        manager.autoImportBundledData()

        // Then: Towers imported and version stored as BUNDLED_TOWER_DATA_VERSION (2)
        assertEquals(2, fakeDao.getTowerCount())
        assertNotNull(fakeDao.findTower(310, 260, 1001, 5001))
        assertNotNull(fakeDao.findTower(311, 480, 1002, 5002))
        assertTrue(prefs.contains(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION))
        assertEquals(
            TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION,
            prefs.getInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 0)
        )
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
    }

    @Test
    fun staleVersion_triggersDeleteUsPlusReImport() = runBlocking {
        // Given: Stored version 1 in SharedPreferences and old US towers in DAO
        val prefs = manager.getPrefs()
        prefs.edit().putInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 1).commit()

        val oldUsTower = KnownTowerEntity(
            mcc = 310, mnc = 260, lac = 1001, cid = 1111,
            latitude = 40.71, longitude = -74.00, range = 1000, radio = "LTE", samples = 20
        )
        fakeDao.insertTowers(listOf(oldUsTower))
        assertEquals(1, fakeDao.getTowerCount())

        val newCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,2222,-73.985,40.748,1000,55")
        }
        manager.assetOpener = { createGzipCsv(newCsv) }

        // When
        manager.autoImportBundledData()

        // Then: Old US tower was deleted, new US tower was imported, version updated to 2
        assertEquals(1, fakeDao.getTowerCount())
        assertNull(fakeDao.findTower(310, 260, 1001, 1111))
        assertNotNull(fakeDao.findTower(310, 260, 1001, 2222))
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
        assertEquals(TowerDatabaseManager.US_MCCS, fakeDao.deleteTowersByCountriesCalledWith)
    }

    @Test
    fun nonUsRowsSurvive_whenUsRowsAreDeletedAndReimported() = runBlocking {
        // Given: Existing install (version 1) containing US towers and user-imported foreign towers
        val prefs = manager.getPrefs()
        prefs.edit().putInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 1).commit()

        val oldUsTower = KnownTowerEntity(
            mcc = 310, mnc = 260, lac = 1001, cid = 1111,
            latitude = 40.71, longitude = -74.00, range = 1000, radio = "LTE", samples = 20
        )
        val canadaTower = KnownTowerEntity(
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
        fakeDao.insertTowers(listOf(oldUsTower, canadaTower, ukTower, germanyTower))
        assertEquals(4, fakeDao.getTowerCount())

        val newBundleCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,410,1001,9999,-73.985,40.748,1000,80")
        }
        manager.assetOpener = { createGzipCsv(newBundleCsv) }

        // When
        manager.autoImportBundledData()

        // Then: Old US tower was deleted, new US tower was added, but ALL foreign towers survived!
        assertEquals(4, fakeDao.getTowerCount())
        assertNull("Old US tower must be removed", fakeDao.findTower(310, 260, 1001, 1111))
        assertNotNull("New US tower must be present", fakeDao.findTower(310, 410, 1001, 9999))
        assertNotNull("Canada tower must survive", fakeDao.findTower(302, 720, 2001, 3001))
        assertNotNull("UK tower must survive", fakeDao.findTower(234, 15, 3001, 4001))
        assertNotNull("Germany tower must survive", fakeDao.findTower(262, 1, 4001, 5001))
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
            mcc = 302, mnc = 720, lac = 2001, cid = 3001,
            latitude = 43.65, longitude = -79.38, range = 1200, radio = "LTE", samples = 35
        )
        fakeDao.insertTowers(listOf(oldUsTower, foreignTower))
        assertEquals(2, fakeDao.getTowerCount())
        assertEquals(1, manager.getEffectiveBundledVersion())

        val newBundleCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,8888,-73.985,40.748,1000,90")
        }
        manager.assetOpener = { createGzipCsv(newBundleCsv) }

        // When
        manager.autoImportBundledData()

        // Then: Counts as version 1, refreshes US bundle, preserves foreign tower, and updates version to 2
        assertEquals(2, fakeDao.getTowerCount())
        assertNull(fakeDao.findTower(310, 260, 1001, 1111))
        assertNotNull(fakeDao.findTower(310, 260, 1001, 8888))
        assertNotNull(fakeDao.findTower(302, 720, 2001, 3001))
        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
    }

    @Test
    fun equalVersion_skipsAutoImport() = runBlocking {
        // Given: Stored version is equal to BUNDLED_TOWER_DATA_VERSION (2)
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
        // Given: Stored version 1
        val prefs = manager.getPrefs()
        prefs.edit().putInt(TowerDatabaseManager.KEY_BUNDLED_TOWER_DATA_VERSION, 1).commit()

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

        // Then: Version remains 1 (not upgraded to 2)
        assertEquals(1, manager.getStoredBundledVersion())

        // And on next launch, when asset is fixed, it retries and succeeds:
        val fixedCsv = buildString {
            appendLine("radio,mcc,mnc,lac,cid,lon,lat,range,samples")
            appendLine("LTE,310,260,1001,6666,-73.985,40.748,1000,40")
        }
        manager.assetOpener = { createGzipCsv(fixedCsv) }

        manager.autoImportBundledData()

        assertEquals(TowerDatabaseManager.BUNDLED_TOWER_DATA_VERSION, manager.getStoredBundledVersion())
        assertNotNull(fakeDao.findTower(310, 260, 1001, 6666))
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
