package com.airbridge.airplay.multiroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Master volume: proportional scaling, drag snapshot round trip, all-zero, clamping, no feedback. */
class MasterVolumeTest {
    @Test
    fun masterIsTheLoudestSpeaker() {
        assertEquals(65, MasterVolumeCalculator.masterOf(listOf(65, 40, 10)))
        assertEquals(0, MasterVolumeCalculator.masterOf(emptyList()))
        assertEquals(0, MasterVolumeCalculator.masterOf(listOf(0, 0)))
        assertEquals("corrupt stored values are clamped", 100, MasterVolumeCalculator.masterOf(listOf(140, 20)))
        assertEquals(0, MasterVolumeCalculator.masterOf(listOf(-5)))
    }

    @Test
    fun scalesProportionally() {
        val snap = mapOf("kitchen" to 80, "living" to 40, "den" to 20)
        assertEquals(mapOf("kitchen" to 40, "living" to 20, "den" to 10), MasterVolumeCalculator.scale(snap, 40))
        assertEquals(mapOf("kitchen" to 100, "living" to 50, "den" to 25), MasterVolumeCalculator.scale(snap, 100))
        assertEquals(mapOf("kitchen" to 0, "living" to 0, "den" to 0), MasterVolumeCalculator.scale(snap, 0))
        // Rounding: 33 * 50 / 100 = 16.5 -> 17 (half up), loudest always lands exactly on the master.
        assertEquals(mapOf("a" to 50, "b" to 17), MasterVolumeCalculator.scale(mapOf("a" to 100, "b" to 33), 50))
        for (m in 0..100) {
            val out = MasterVolumeCalculator.scale(snap, m)
            assertEquals("loudest speaker follows the master exactly at $m", m, out["kitchen"])
            assertEquals("master display equals the slider at $m", m, MasterVolumeCalculator.masterOf(out.values))
            assertTrue("balance kept at $m", out["kitchen"]!! >= out["living"]!! && out["living"]!! >= out["den"]!!)
        }
    }

    @Test
    fun dragDownAndBackUpRestoresTheExactStartingBalance() {
        val start = mapOf("kitchen" to 65, "living" to 40, "patio" to 10)
        val drag = MasterVolumeDrag(start)
        assertEquals(65, drag.startMaster)
        // A full drag: down to 0, up to 100, back to where it started. Each step uses the snapshot.
        val path = (65 downTo 0) + (0..100) + (100 downTo 65)
        var last = start
        for (m in path) last = drag.volumesFor(m)
        assertEquals(start, last)
        assertEquals(start, drag.volumesFor(65))
        assertEquals(mapOf("kitchen" to 100, "living" to 62, "patio" to 15), drag.volumesFor(100))
        assertEquals(mapOf("kitchen" to 0, "living" to 0, "patio" to 0), drag.volumesFor(0))
        // Rounding drift would appear if each step were computed from the previous one; it is not.
        val stepwise = (65 downTo 1).fold(start) { acc, m -> MasterVolumeCalculator.scale(acc, m) }
        assertTrue("chained scaling loses the balance", stepwise != MasterVolumeCalculator.scale(start, 1))
        assertEquals(start, drag.volumesFor(65))
    }

    @Test
    fun allSpeakersAtZeroRaiseEvenly() {
        val drag = MasterVolumeDrag(mapOf("a" to 0, "b" to 0, "c" to 0))
        assertEquals(0, drag.startMaster)
        assertEquals(mapOf("a" to 40, "b" to 40, "c" to 40), drag.volumesFor(40))
        assertEquals(mapOf("a" to 100, "b" to 100, "c" to 100), drag.volumesFor(100))
        assertEquals(mapOf("a" to 0, "b" to 0, "c" to 0), drag.volumesFor(0))
    }

    @Test
    fun clampsMasterAndSnapshotValues() {
        val drag = MasterVolumeDrag(mapOf("a" to 120, "b" to -10, "c" to 50))
        assertEquals(mapOf("a" to 100, "b" to 0, "c" to 50), drag.snapshot)
        assertEquals("master above 100 is 100", mapOf("a" to 100, "b" to 0, "c" to 50), drag.volumesFor(150))
        assertEquals("master below 0 is 0", mapOf("a" to 0, "b" to 0, "c" to 0), drag.volumesFor(-20))
        for (m in -50..150) drag.volumesFor(m).values.forEach { assertTrue(it in 0..100) }
    }

    @Test
    fun singleSpeakerFollowsTheMasterDirectly() {
        val drag = MasterVolumeDrag(mapOf("kitchen" to 30))
        assertEquals(30, drag.startMaster)
        for (m in 0..100) assertEquals(mapOf("kitchen" to m), drag.volumesFor(m))
        assertEquals(mapOf("kitchen" to 70), MasterVolumeDrag(mapOf("kitchen" to 0)).volumesFor(70))
        assertEquals(emptyMap<String, Int>(), MasterVolumeDrag(emptyMap()).volumesFor(70))
    }

    @Test
    fun speakerChangesOnlyChangeTheDerivedMaster() {
        // The master display is derived from the speaker volumes; nothing is written back.
        val volumes = mutableMapOf("kitchen" to 65, "living" to 40)
        assertEquals(65, MasterVolumeCalculator.masterOf(volumes.values))
        volumes["living"] = 90 // the user drags one speaker's own slider
        assertEquals(90, MasterVolumeCalculator.masterOf(volumes.values))
        assertEquals("other speaker untouched", 65, volumes["kitchen"])
        // A drag snapshot is a copy: later speaker changes do not alter it.
        val drag = MasterVolumeDrag(volumes)
        volumes["kitchen"] = 5
        assertEquals(mapOf("kitchen" to 65, "living" to 90), drag.snapshot)
        assertEquals(mapOf("kitchen" to 36, "living" to 50), drag.volumesFor(50))
    }
}
