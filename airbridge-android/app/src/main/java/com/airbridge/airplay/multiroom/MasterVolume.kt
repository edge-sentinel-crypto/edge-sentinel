package com.airbridge.airplay.multiroom

/**
 * Master volume for several speakers that keeps their relative balance.
 *
 * The master level is the loudest enabled speaker. Moving the master to `m` sets every speaker to
 * `round(v * m / top)`, where `v` is that speaker's volume and `top` the loudest volume in the
 * snapshot taken when the drag started. Because every step is computed from the same snapshot,
 * dragging down and back up returns exactly to the starting volumes. If every speaker is at 0 the
 * balance is unknown, so they are all raised together to `m`. Values are clamped to 0..100.
 */
object MasterVolumeCalculator {
    const val MIN = 0
    const val MAX = 100

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** Master level shown for these speaker volumes: the loudest one, or 0 when there is none. */
    fun masterOf(volumes: Collection<Int>): Int = volumes.maxOfOrNull { clamp(it) } ?: 0

    /** Per-speaker volumes for master level [master], computed from the drag-start [snapshot]. */
    fun scale(snapshot: Map<String, Int>, master: Int): Map<String, Int> {
        val m = clamp(master)
        val base = snapshot.mapValues { clamp(it.value) }
        val top = base.values.maxOrNull() ?: return emptyMap()
        if (top == 0) return base.mapValues { m } // all at 0: raise evenly
        return base.mapValues { (_, v) -> clamp(Math.round(v.toDouble() * m / top).toInt()) }
    }
}

/**
 * One drag of the master slider. The per-speaker snapshot is taken once, when the drag starts, and
 * every later position is computed from it, never from the previous step (no rounding drift and no
 * feedback from the speakers' own sliders).
 */
class MasterVolumeDrag(snapshot: Map<String, Int>) {
    val snapshot: Map<String, Int> = snapshot.mapValues { MasterVolumeCalculator.clamp(it.value) }

    /** Master level when the drag started. */
    val startMaster: Int = MasterVolumeCalculator.masterOf(this.snapshot.values)

    /** Speaker volumes for the slider at [master]. */
    fun volumesFor(master: Int): Map<String, Int> = MasterVolumeCalculator.scale(snapshot, master)
}
