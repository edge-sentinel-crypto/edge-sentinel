package com.bp22intel.edgesentinel

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.bp22intel.edgesentinel.data.WipeInProgress
import com.bp22intel.edgesentinel.data.WipeInProgressException
import com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase
import com.bp22intel.edgesentinel.service.MonitoringService
import com.bp22intel.edgesentinel.travel.ProcessRestarter
import com.bp22intel.edgesentinel.ui.travel.TravelModeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Round5Test {

    private lateinit var tempDir: File
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("round5_test").toFile()
        WipeInProgress.resetForTests()
        MonitoringService.resetForTests()
        TravelModeViewModel.resetWipeActiveForTests()
        TravelModeViewModel.wipeErrorDisplayMs = 0L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        WipeInProgress.resetForTests()
        MonitoringService.resetForTests()
        TravelModeViewModel.resetWipeActiveForTests()
        tempDir.deleteRecursively()
    }

    private class FakeProcessRestarter : ProcessRestarter {
        val restartedLatch = CountDownLatch(1)
        @Volatile var restarted = false
        val restartCount = AtomicInteger(0)
        @Volatile var throwOnRestart: Throwable? = null

        override fun restartProcess() {
            restartCount.incrementAndGet()
            throwOnRestart?.let { throw it }
            restarted = true
            restartedLatch.countDown()
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
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

    class FakeContext : ContextWrapper(null) {
        lateinit var tempDir: File
        val prefsMap = ConcurrentHashMap<String, SharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return prefsMap.computeIfAbsent(name) { FakeSharedPreferences() }
        }

        override fun getDatabasePath(name: String): File = File(tempDir, name)
        override fun getFilesDir(): File = tempDir
        override fun getNoBackupFilesDir(): File = tempDir
        override fun getCacheDir(): File = tempDir
        override fun getPackageName(): String = "com.bp22intel.edgesentinel"
        override fun getPackageManager(): android.content.pm.PackageManager? = null
        override fun getApplicationContext(): Context = this
        override fun deleteDatabase(name: String): Boolean = File(tempDir, name).delete()
        override fun databaseList(): Array<String> = tempDir.list() ?: emptyArray()
    }

    private fun createFakeContext(): FakeContext {
        val ctx = allocateWithoutConstructor(FakeContext::class.java)
        ctx.tempDir = tempDir
        val f = FakeContext::class.java.getDeclaredField("prefsMap")
        f.isAccessible = true
        f.set(ctx, ConcurrentHashMap<String, SharedPreferences>())
        return ctx
    }

    private class FakeDataStore(
        val onUpdate: (suspend () -> Unit)? = null
    ) : DataStore<Preferences> {
        val updateCount = AtomicInteger(0)
        override val data: Flow<Preferences> = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updateCount.incrementAndGet()
            onUpdate?.invoke()
            return emptyPreferences()
        }
    }

    private fun createViewModel(
        applicationScope: CoroutineScope,
        fakeRestarter: FakeProcessRestarter,
        dataStore: DataStore<Preferences> = FakeDataStore()
    ): TravelModeViewModel {
        val fakeDb = allocateWithoutConstructor(
            Class.forName("com.bp22intel.edgesentinel.data.local.EdgeSentinelDatabase_Impl")
        ) as EdgeSentinelDatabase

        return TravelModeViewModel(
            context = createFakeContext(),
            dataStore = dataStore,
            database = fakeDb,
            applicationScope = applicationScope,
            processRestarter = fakeRestarter
        )
    }

    @Test
    fun cancellingCallerScopeDuringWipe_doesNotStopWipe_andRestartIsCalled() = runBlocking {
        val fakeRestarter = FakeProcessRestarter()
        val appScopeJob = Job()
        val appScope = CoroutineScope(Dispatchers.IO + appScopeJob)

        val wipeStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val fakeDataStore = FakeDataStore(onUpdate = {
            wipeStarted.complete(Unit)
            gate.await()
        })
        val viewModel = createViewModel(appScope, fakeRestarter, dataStore = fakeDataStore)

        // 1. Call viewModel.panicWipe() directly so wipe starts on applicationScope
        viewModel.panicWipe()
        wipeStarted.await()
        assertTrue("Wipe must be actively in progress", WipeInProgress.isActive)

        // 2. Cancel ViewModel's scope for UI work
        viewModel.viewModelScope.cancel()

        // 3. Release the gate
        gate.complete(Unit)

        // 4. Wait for fake restarter with timeout <= 5s and assert it was called
        assertTrue(
            "Restart should be called even after caller/ViewModel scope cancelled",
            fakeRestarter.restartedLatch.await(5, TimeUnit.SECONDS)
        )
        assertTrue(fakeRestarter.restarted)
    }

    @Test
    fun ifFakeRestarterThrows_abortRuns_dbGateLifts_andPreviousCrashHandlerRestored() = runBlocking {
        val originalHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)

        val fakeRestarter = FakeProcessRestarter().apply {
            throwOnRestart = IllegalStateException("Simulated restart crash")
        }
        val appScopeJob = Job()
        val appScope = CoroutineScope(Dispatchers.IO + appScopeJob + CoroutineExceptionHandler { _, _ -> })
        val viewModel = createViewModel(appScope, fakeRestarter)

        viewModel.panicWipe()

        // Wait for the appScope job to finish (which fails due to restarter throwing)
        appScopeJob.children.forEach { it.join() }

        // After failure, abort() ran:
        assertFalse("Database gate must be lifted after abort()", WipeInProgress.isActive)
        assertEquals("Previous crash handler must be restored after abort()",
            originalHandler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun afterAbort_exceptionOutsideWipeReachesOriginalHandler() {
        var handledException: Throwable? = null
        var handledThread: Thread? = null
        val originalHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            handledThread = thread
            handledException = throwable
        }
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)

        // Begin wipe
        WipeInProgress.begin()
        assertTrue(WipeInProgress.isActive)

        // Abort wipe
        WipeInProgress.abort()
        assertFalse(WipeInProgress.isActive)

        // An exception outside a wipe is thrown to the default uncaught exception handler
        val testException = RuntimeException("Exception outside wipe")
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), testException)

        assertEquals("Exception outside wipe must reach original handler", testException, handledException)
        assertEquals(Thread.currentThread(), handledThread)
    }

    @Test
    fun startStickyRestartWithNullIntent_honorsPostWipeSuppression() {
        val fakeContext = createFakeContext()
        // Simulate panic wipe having marked auto-start suppression
        MonitoringService.markPanicWipeSuppression(fakeContext)

        val service = allocateWithoutConstructor(MonitoringService::class.java)
        service.testContext = fakeContext

        // START_STICKY restart passes a null intent
        val result = service.onStartCommand(null, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue("Service must call stopSelf when auto-start is suppressed on null-intent restart",
            service.stopSelfCalled)
    }

    @Test
    fun doubleTapPanicWipe_startsOnlyOneWipeSequence() = runBlocking {
        val fakeRestarter = FakeProcessRestarter()
        val appScopeJob = Job()
        val appScope = CoroutineScope(Dispatchers.IO + appScopeJob)

        val stepEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val invocations = AtomicInteger(0)

        val fakeDataStore = FakeDataStore(onUpdate = {
            val count = invocations.incrementAndGet()
            if (count == 1) {
                stepEntered.complete(Unit)
                gate.await()
            }
        })
        val viewModel = createViewModel(appScope, fakeRestarter, dataStore = fakeDataStore)

        // First call starts the wipe and suspends on gate
        viewModel.panicWipe()
        stepEntered.await()
        assertTrue("Wipe must be active", WipeInProgress.isActive)

        // Second call while wipe is active must do nothing (double-tap guard)
        viewModel.panicWipe()

        // Release gate
        gate.complete(Unit)

        // Wait for the single wipe sequence to finish
        assertTrue(
            "Restart should be called for the active wipe sequence",
            fakeRestarter.restartedLatch.await(5, TimeUnit.SECONDS)
        )
        assertTrue(fakeRestarter.restarted)

        // Count fake step invocations: exactly one sequence ran (initial edit + clearDataStores x2 for retry = 3)
        assertEquals("Fake step should only be invoked for one wipe sequence", 3, invocations.get())
        assertEquals("Process restarter should be called exactly once", 1, fakeRestarter.restartCount.get())
    }
}
