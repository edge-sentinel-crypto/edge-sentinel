package com.airbridge.airplay.airplay

/**
 * Per-speaker sync offset: a small timing correction for one speaker so that speakers which play
 * slightly out of step can be lined up by ear.
 *
 * Sign convention (the same everywhere: settings, sessions, senders and the UI labels):
 *  - negative milliseconds = this speaker plays EARLIER. Its sync packets announce less latency,
 *    so each RTP frame is scheduled sooner.
 *  - positive milliseconds = this speaker plays LATER (a delay). Its sync packets announce more
 *    latency, so each RTP frame is scheduled later.
 *
 * The offset changes only the latency announced in the RTP sync packets:
 * `latency = base + round(offsetMs * 44.1)` frames, with halves rounded away from zero so +x and
 * -x ms give the same magnitude. The range is -250..+250 ms in 5 ms steps (-11,025..+11,025
 * frames). With the default base of 77,175 frames the announced latency is 66,150..88,200 frames.
 * Receivers add their fixed 11,025 frames to it (the 0x07 flag of the sync packet), so the total
 * delay is 77,175..99,225 frames (1.75 s..2.25 s) instead of 88,200 (2.0 s).
 * The announced latency never goes below [MIN_LATENCY_FRAMES], whatever the base and offset.
 *
 * AirPlay 2 receivers are also told a latency window in the stream SETUP (`latencyMin`,
 * `latencyMax`). [ap2LatencyBounds] derives that window from the same numbers, so every latency a
 * sync packet can announce over the whole -250..+250 ms range lies inside it (see [LatencyBounds]).
 */
object SyncOffset {
    const val MIN_MS = -250
    const val MAX_MS = 250
    const val STEP_MS = 5
    const val DEFAULT_MS = 0

    /** Latency announced in sync packets at offset 0: 77,175 frames = 1.75 s (2.0 s with the receiver's 11,025). */
    const val BASE_LATENCY_FRAMES = 77_175

    /**
     * Frames a receiver adds to the announced latency of a sync packet whose byte 3 is 0x07 (as
     * AirBridge sends them): 11,025 = 0.25 s. 77,175 + 11,025 = 88,200 frames = 2.0 s in total.
     */
    const val RECEIVER_ADDED_FRAMES = 11_025

    /** Safe floor for the announced latency: 22,050 frames = 0.5 s at 44.1 kHz. */
    const val MIN_LATENCY_FRAMES = 22_050

    /**
     * The AirPlay 2 stream SETUP window that owntone and pyatv send: latencyMin 11,025, latencyMax
     * 88,200. AirBridge never announces a narrower window than this.
     */
    const val AP2_LATENCY_MIN_FRAMES = 11_025
    const val AP2_LATENCY_MAX_FRAMES = 88_200

    /** Limits [ms] to -250..+250. */
    fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)

    /** Clamps [ms] and rounds it to the nearest 5 ms step (the slider grid). */
    fun snap(ms: Int): Int {
        val c = clamp(ms)
        val steps = if (c >= 0) (c + STEP_MS / 2) / STEP_MS else -((-c + STEP_MS / 2) / STEP_MS)
        return clamp(steps * STEP_MS)
    }

    /** Offset in RTP frames: round([ms] x sampleRate / 1000), halves away from zero; [ms] is clamped first. */
    fun frames(ms: Int, sampleRate: Int = AudioFrame.SAMPLE_RATE): Int {
        val scaled = clamp(ms).toLong() * sampleRate
        val rounded = if (scaled >= 0) (scaled + 500) / 1000 else -((-scaled + 500) / 1000)
        return rounded.toInt()
    }

    /** Latency to announce in sync packets: [baseLatencyFrames] shifted by the offset, never below the floor. */
    fun latencyFrames(baseLatencyFrames: Int, ms: Int, sampleRate: Int = AudioFrame.SAMPLE_RATE): Int =
        maxOf(MIN_LATENCY_FRAMES, baseLatencyFrames + frames(ms, sampleRate))

    /** Total delay a receiver applies for a sync packet: the announced latency plus its fixed 11,025 frames. */
    fun totalLatencyFrames(baseLatencyFrames: Int, ms: Int, sampleRate: Int = AudioFrame.SAMPLE_RATE): Int =
        latencyFrames(baseLatencyFrames, ms, sampleRate) + RECEIVER_ADDED_FRAMES

    /**
     * The AirPlay 2 stream SETUP window (`latencyMin`, `latencyMax`) for [baseLatencyFrames]: the usual
     * window 11,025..88,200, widened where needed so that it contains the announced latency at
     * -250 ms and the total latency at +250 ms. The announced latency and the total grow with the
     * offset, so every offset in -250..+250 ms is then inside the window, whichever of the two
     * values a receiver compares with it. Default base 77,175: latencyMin 11,025, latencyMax 99,225
     * (announced 66,150..88,200, total 77,175..99,225).
     */
    fun ap2LatencyBounds(baseLatencyFrames: Int = BASE_LATENCY_FRAMES, sampleRate: Int = AudioFrame.SAMPLE_RATE): LatencyBounds {
        val lowestAnnounced = latencyFrames(baseLatencyFrames, MIN_MS, sampleRate)
        val highestTotal = totalLatencyFrames(baseLatencyFrames, MAX_MS, sampleRate)
        return LatencyBounds(
            min = minOf(AP2_LATENCY_MIN_FRAMES, lowestAnnounced),
            max = maxOf(AP2_LATENCY_MAX_FRAMES, highestTotal),
        )
    }

    /** Dialog label: "+40 ms (delay)", "-25 ms (earlier)" or "0 ms". */
    fun label(ms: Int): String = when {
        ms > 0 -> "+$ms ms (delay)"
        ms < 0 -> "$ms ms (earlier)"
        else -> "0 ms"
    }

    /** Compact badge text for a speaker card: "+40 ms" / "-25 ms". */
    fun badge(ms: Int): String = if (ms > 0) "+$ms ms" else "$ms ms"
}

/**
 * The latency window an AirPlay 2 sender declares in its stream SETUP, in frames at 44.1 kHz.
 *
 * Receivers may compare either the latency announced in a sync packet (rtpNow - rtpLessLatency)
 * or the total delay they apply (announced + 11,025 frames) with this window. [admits] accepts an
 * announced latency only when both values are inside it, so there is no mismatch either way.
 */
data class LatencyBounds(val min: Int, val max: Int) {
    init {
        require(min <= max - SyncOffset.RECEIVER_ADDED_FRAMES) { "latency window $min..$max is too narrow" }
    }

    /** True when [announced] and [announced] + 11,025 both lie within [min]..[max]. */
    fun admits(announced: Int): Boolean = announced >= min && announced + SyncOffset.RECEIVER_ADDED_FRAMES <= max

    /** The nearest announced latency that [admits] accepts (a safety net; never needed for -250..+250 ms). */
    fun clamp(announced: Int): Int = announced.coerceIn(min, max - SyncOffset.RECEIVER_ADDED_FRAMES)
}
