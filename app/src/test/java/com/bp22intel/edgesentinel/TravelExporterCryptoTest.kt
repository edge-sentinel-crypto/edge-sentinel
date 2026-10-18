package com.bp22intel.edgesentinel

import org.junit.Assert.assertEquals
import com.bp22intel.edgesentinel.travel.TravelExporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** encrypt/decrypt do not touch Context, so the exporter is allocated without its constructor. */
class TravelExporterCryptoTest {
    private val exporter = allocateWithoutConstructor(TravelExporter::class.java)
    private val plain = """{"alerts":[{"summary":"IMSI catcher near hotel"}]}""".toByteArray()

    @Test fun ciphertextIsNotPlaintextAndRoundTrips() {
        val enc = exporter.encrypt(plain, "correct horse")
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("IMSI catcher"))
        assertTrue(enc.size > plain.size)
        assertArrayEquals(plain, exporter.decrypt(enc, "correct horse"))
    }

    @Test fun wrongPassphraseFails() {
        val enc = exporter.encrypt(plain, "correct horse")
        try {
            exporter.decrypt(enc, "wrong horse")
            fail("decrypt with wrong passphrase must throw")
        } catch (e: javax.crypto.AEADBadTagException) {
            // expected: GCM tag mismatch
        }
    }

    @Test fun blankPassphraseExportRejected() {
        for (p in listOf("", "   ")) {
            try {
                exporter.exportEncrypted(p)
                fail("blank passphrase must be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("passphrase"))
            }
        }
    }

    // ── N9: format header, fresh salt + IV, rejection of bad input ──────────

    private val headerLen = TravelExporter.FILE_HEADER.size

    @Test fun headerIsVersionedMagic() {
        val enc = exporter.encrypt(plain, "correct horse")
        assertArrayEquals(byteArrayOf(0x45, 0x53, 0x02, 0x01), enc.copyOfRange(0, headerLen))
        assertEquals("ES", String(enc, 0, 2, Charsets.US_ASCII))
    }

    @Test fun twoEncryptionsUseDifferentSaltAndIv() {
        val a = exporter.encrypt(plain, "correct horse")
        val b = exporter.encrypt(plain, "correct horse")
        val salt = headerLen until headerLen + 32
        val iv = headerLen + 32 until headerLen + 44
        assertFalse("salt must differ", a.sliceArray(salt).contentEquals(b.sliceArray(salt)))
        assertFalse("IV must differ", a.sliceArray(iv).contentEquals(b.sliceArray(iv)))
        assertFalse(a.contentEquals(b))
    }

    @Test fun truncatedFileRejectedWithClearError() {
        val enc = exporter.encrypt(plain, "correct horse")
        try {
            exporter.decrypt(enc.copyOf(headerLen + 20), "correct horse")
            fail("truncated file must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too short"))
        }
        try {
            exporter.decrypt(enc.copyOf(enc.size - 1), "correct horse")
            fail("file missing its last byte must not decrypt")
        } catch (e: javax.crypto.AEADBadTagException) {
            // expected: GCM tag check fails
        }
    }

    @Test fun oldFormatWithoutHeaderRejected() {
        val enc = exporter.encrypt(plain, "correct horse")
        val oldFormat = enc.copyOfRange(headerLen, enc.size)   // salt || iv || ct+tag
        try {
            exporter.decrypt(oldFormat, "correct horse")
            fail("old-format file must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported export format"))
        }
        val wrongVersion = enc.copyOf().also { it[2] = 0x01 }
        try {
            exporter.decrypt(wrongVersion, "correct horse")
            fail("unknown format version must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported export format"))
        }
    }
}
