package com.airbridge.airplay.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Capture opens at 44.1 kHz when the phone allows it and falls back to 48 kHz (converted to
 * 44.1 kHz) when the 44.1 kHz AudioRecord does not initialize or does not start; a refused attempt
 * is released before the next one, and permission errors are not mistaken for format problems.
 */
class CaptureFallbackTest {
    /** Stands in for an AudioRecord: records whether it was started and released. */
    private class FakeRecord(val rate: Int) {
        var started = false
        var released = false
    }

    /** Opens fake records; [initFails] / [startFails] list the rates that fail at that step. */
    private class FakeDevice(val initFails: Set<Int> = emptySet(), val startFails: Set<Int> = emptySet()) {
        val created = ArrayList<FakeRecord>()

        fun open(rate: Int): FakeRecord {
            if (rate in initFails) throw IllegalStateException("AudioRecord did not initialize")
            val r = FakeRecord(rate)
            created += r
            var ok = false
            try {
                if (rate in startFails) throw IllegalStateException("AudioRecord did not start recording")
                r.started = true
                ok = true
                return r
            } finally {
                if (!ok) r.released = true // what CaptureEngine.openRecord does before rethrowing
            }
        }
    }

    @Test
    fun opensAt44100WhenThePhoneAllowsIt() {
        val device = FakeDevice()
        val opened = CaptureFallback.openFirst { device.open(it) }
        assertEquals(44_100, opened.sampleRate)
        assertFalse(opened.needsResampling)
        assertTrue(opened.refused.isEmpty())
        assertEquals("48 kHz never tried", listOf(44_100), device.created.map { it.rate })
        assertTrue(opened.handle.started && !opened.handle.released)
    }

    @Test
    fun fallsBackTo48000WhenInitializationFails() {
        val device = FakeDevice(initFails = setOf(44_100))
        val opened = CaptureFallback.openFirst { device.open(it) }
        assertEquals(48_000, opened.sampleRate)
        assertTrue(opened.needsResampling)
        assertEquals(listOf("44100 Hz: AudioRecord did not initialize"), opened.refused)
        assertTrue(opened.handle.started && !opened.handle.released)
    }

    @Test
    fun fallsBackTo48000WhenStartingFailsAndReleasesTheRefusedRecord() {
        val device = FakeDevice(startFails = setOf(44_100))
        val opened = CaptureFallback.openFirst { device.open(it) }
        assertEquals(48_000, opened.sampleRate)
        assertEquals(2, device.created.size)
        assertTrue("the 44.1 kHz record was released before 48 kHz was opened", device.created[0].released)
        assertFalse(device.created[1].released)
        assertEquals(listOf("44100 Hz: AudioRecord did not start recording"), opened.refused)
    }

    @Test
    fun reportsBothRatesWhenNeitherWorks() {
        val device = FakeDevice(initFails = setOf(44_100), startFails = setOf(48_000))
        try {
            CaptureFallback.openFirst { device.open(it) }
            fail("no capture should open")
        } catch (e: CaptureFallback.CaptureUnavailableException) {
            assertTrue(e.message!!.contains("44100 Hz: AudioRecord did not initialize"))
            assertTrue(e.message!!.contains("48000 Hz: AudioRecord did not start recording"))
        }
        assertTrue("nothing left open", device.created.all { it.released })
    }

    @Test
    fun permissionErrorsAreNotTreatedAsFormatProblems() {
        val tried = ArrayList<Int>()
        try {
            CaptureFallback.openFirst { rate ->
                tried += rate
                throw SecurityException("RECORD_AUDIO not granted")
            }
            fail("SecurityException expected")
        } catch (e: SecurityException) {
            assertEquals(listOf(44_100), tried)
        }
    }
}
