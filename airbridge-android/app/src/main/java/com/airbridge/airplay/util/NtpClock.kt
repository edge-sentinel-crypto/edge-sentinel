package com.airbridge.airplay.util

/**
 * Monotonic NTP-format clock (64-bit: seconds since 1900 in the high word, binary fraction in the
 * low word). It is anchored to wall-clock time once and then advances with System.nanoTime(), so it
 * never jumps when the phone's wall clock is adjusted. Receivers only use it relative to our
 * timing replies and sync packets, so the same instance must serve both.
 */
class NtpClock(
    private val nanoSource: () -> Long = System::nanoTime,
    wallMillisAtStart: Long = System.currentTimeMillis(),
) {
    private val baseMonotonic = nanoSource()
    private val baseNanosSince1900 = (wallMillisAtStart + NTP_EPOCH_OFFSET_SECONDS * 1000L) * 1_000_000L

    /** Nanoseconds since 1900-01-01 (fits in a signed Long until year ~2192). */
    fun nowNanos(): Long = baseNanosSince1900 + (nanoSource() - baseMonotonic)

    /** Current time as a 64-bit NTP timestamp. */
    fun now(): Long = toNtp64(nowNanos())

    companion object {
        const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L

        fun toNtp64(nanosSince1900: Long): Long {
            val seconds = nanosSince1900 / 1_000_000_000L
            val remainder = nanosSince1900 % 1_000_000_000L
            val fraction = (remainder shl 32) / 1_000_000_000L
            return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
        }

        fun ntp64ToNanos(ntp: Long): Long {
            val seconds = ntp ushr 32
            val fraction = ntp and 0xFFFFFFFFL
            return seconds * 1_000_000_000L + ((fraction * 1_000_000_000L) ushr 32)
        }
    }
}
