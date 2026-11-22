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
 * frames). With the default base of 77,175 frames the announced latency is 66,150..88,200 frames;
 * receivers add their fixed 11,025 frames, so the total buffer is 1.75 s..2.25 s instead of 2.0 s.
 * The announced latency never goes below [MIN_LATENCY_FRAMES], whatever the base and offset.
 */
object SyncOffset {
    const val MIN_MS = -250
    const val MAX_MS = 250
    const val STEP_MS = 5
    const val DEFAULT_MS = 0

    /** Safe floor for the announced latency: 22,050 frames = 0.5 s at 44.1 kHz. */
    const val MIN_LATENCY_FRAMES = 22_050

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

    /** Dialog label: "+40 ms (delay)", "-25 ms (earlier)" or "0 ms". */
    fun label(ms: Int): String = when {
        ms > 0 -> "+$ms ms (delay)"
        ms < 0 -> "$ms ms (earlier)"
        else -> "0 ms"
    }

    /** Compact badge text for a speaker card: "+40 ms" / "-25 ms". */
    fun badge(ms: Int): String = if (ms > 0) "+$ms ms" else "$ms ms"
}
