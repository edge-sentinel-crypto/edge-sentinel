package com.airbridge.airplay.util

/** One system lock (Android WakeLock, WifiLock or MulticastLock) behind a small, testable interface. */
interface HeldLock {
    val name: String
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Acquires a fixed set of locks together and always releases them in a balanced way:
 *  - [acquireAll] takes the locks in order; if one fails, the locks already taken are released
 *    (in a `finally`) before the error is rethrown, so a failed start leaks nothing;
 *  - [releaseAll] releases every held lock in reverse order, each in its own `try`, so one failing
 *    release cannot keep the others held; it is idempotent and safe to call after a failed start.
 * Thread-safe. Android-free, so it is unit tested with fake locks.
 */
class LockSet(private val locks: List<HeldLock>) {
    private val taken = ArrayList<HeldLock>()

    /** Number of locks this set currently holds. */
    val heldCount: Int @Synchronized get() = taken.count { it.isHeld }

    @Synchronized
    fun acquireAll() {
        var complete = false
        try {
            for (lock in locks) {
                if (lock in taken) continue
                lock.acquire()
                taken += lock
            }
            complete = true
        } finally {
            if (!complete) releaseAll()
        }
    }

    /** Releases every lock this set took. Returns the names of locks whose release threw (normally empty). */
    @Synchronized
    fun releaseAll(): List<String> {
        val failed = ArrayList<String>()
        for (lock in taken.asReversed()) {
            try {
                if (lock.isHeld) lock.release()
            } catch (e: Exception) {
                failed += lock.name
                AirLog.w(TAG, "could not release ${lock.name}", e)
            }
        }
        taken.clear()
        return failed
    }

    companion object {
        private const val TAG = "Locks"
    }
}
