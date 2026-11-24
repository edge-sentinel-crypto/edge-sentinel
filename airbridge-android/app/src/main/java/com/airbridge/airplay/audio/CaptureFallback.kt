package com.airbridge.airplay.audio

/**
 * Sample-rate fallback for Android playback capture (Android-free, so it is unit tested).
 *
 * AirBridge first opens AudioPlaybackCapture at 44.1 kHz stereo, AirPlay's own rate, so no
 * conversion is needed. Some phones (OEM audio builds) refuse that format: the AudioRecord does not
 * initialize, or it does not start recording. AirBridge then releases it and opens capture at
 * 48 kHz stereo instead, converting to 44.1 kHz with [PcmResampler].
 */
object CaptureFallback {
    /** AirPlay's sample rate, tried first. */
    const val AIRPLAY_RATE = 44_100

    /** Tried when 44.1 kHz capture cannot be opened; converted to [AIRPLAY_RATE]. */
    const val FALLBACK_RATE = 48_000

    val RATES: List<Int> = listOf(AIRPLAY_RATE, FALLBACK_RATE)

    /** The capture that opened, its sample rate and why earlier rates were refused. */
    class Opened<T>(val handle: T, val sampleRate: Int, val refused: List<String>) {
        /** True when the captured audio must be converted to 44.1 kHz. */
        val needsResampling: Boolean get() = sampleRate != AIRPLAY_RATE
    }

    /** No sample rate could be opened. The message lists every rate and its error. */
    class CaptureUnavailableException(message: String) : IllegalStateException(message)

    /**
     * Calls [open] for each rate of [rates] in order and returns the first capture that opens.
     * [open] must release whatever it created before it throws, so a refused rate leaves nothing
     * behind. A [SecurityException] (missing permission) is not a format problem and is rethrown
     * at once.
     */
    fun <T> openFirst(rates: List<Int> = RATES, open: (sampleRate: Int) -> T): Opened<T> {
        require(rates.isNotEmpty()) { "no sample rates to try" }
        val refused = ArrayList<String>()
        for (rate in rates) {
            try {
                return Opened(open(rate), rate, refused.toList())
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                refused += "$rate Hz: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw CaptureUnavailableException("Audio capture could not start (${refused.joinToString("; ")})")
    }
}
