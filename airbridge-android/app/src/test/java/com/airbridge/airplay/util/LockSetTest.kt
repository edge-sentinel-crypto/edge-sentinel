package com.airbridge.airplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WakeLock, WifiLock and MulticastLock handling: acquire and release stay balanced on every path
 * (normal stop, a failed start, a release that throws, repeated stops).
 */
class LockSetTest {
    private class FakeLock(
        override val name: String,
        private val log: MutableList<String>,
        private val failAcquire: Boolean = false,
        private val failRelease: Boolean = false,
    ) : HeldLock {
        override var isHeld = false
            private set

        override fun acquire() {
            if (failAcquire) throw SecurityException("$name refused")
            isHeld = true
            log += "acquire $name"
        }

        override fun release() {
            log += "release $name"
            if (failRelease) throw IllegalStateException("$name release failed")
            isHeld = false
        }
    }

    @Test
    fun acquiresInOrderAndReleasesInReverse() {
        val log = ArrayList<String>()
        val wake = FakeLock("wake", log)
        val wifi = FakeLock("wifi", log)
        val set = LockSet(listOf(wake, wifi))
        set.acquireAll()
        assertEquals(2, set.heldCount)
        assertEquals(emptyList<String>(), set.releaseAll())
        assertEquals(listOf("acquire wake", "acquire wifi", "release wifi", "release wake"), log)
        assertFalse(wake.isHeld || wifi.isHeld)
        assertEquals(0, set.heldCount)
    }

    @Test
    fun aFailedAcquireReleasesWhatWasAlreadyTaken() {
        val log = ArrayList<String>()
        val wake = FakeLock("wake", log)
        val wifi = FakeLock("wifi", log, failAcquire = true)
        val set = LockSet(listOf(wake, wifi))
        try {
            set.acquireAll()
            fail("acquire should fail")
        } catch (e: SecurityException) {
            assertEquals("wifi refused", e.message)
        }
        assertFalse("wake lock not leaked", wake.isHeld)
        assertEquals(listOf("acquire wake", "release wake"), log)
        assertEquals(0, set.heldCount)
        set.releaseAll() // the service's stop path after a failed start: nothing more to do
        assertEquals(2, log.size)
    }

    @Test
    fun aReleaseThatThrowsDoesNotKeepTheOthersHeld() {
        val log = ArrayList<String>()
        val wake = FakeLock("wake", log)
        val wifi = FakeLock("wifi", log, failRelease = true)
        val multicast = FakeLock("multicast", log)
        val set = LockSet(listOf(wake, wifi, multicast))
        set.acquireAll()
        assertEquals(listOf("wifi"), set.releaseAll())
        assertFalse(wake.isHeld)
        assertFalse(multicast.isHeld)
        assertTrue(log.containsAll(listOf("release multicast", "release wifi", "release wake")))
    }

    @Test
    fun repeatedStartsAndStopsStayBalanced() {
        val log = ArrayList<String>()
        val wake = FakeLock("wake", log)
        val set = LockSet(listOf(wake))
        repeat(3) {
            set.acquireAll()
            set.acquireAll() // a second start while running takes nothing twice
            assertTrue(wake.isHeld)
            set.releaseAll()
            set.releaseAll() // a second stop releases nothing twice
            assertFalse(wake.isHeld)
        }
        assertEquals(3, log.count { it == "acquire wake" })
        assertEquals(3, log.count { it == "release wake" })
    }
}
