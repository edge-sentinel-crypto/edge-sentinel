package com.airbridge.airplay.crypto

import com.airbridge.airplay.airplay.HapSecureInputStream
import com.airbridge.airplay.airplay.HapSecureOutputStream
import com.airbridge.airplay.airplay.ap2.PairingResult
import com.airbridge.airplay.util.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class HapCryptoTest {
    private fun h(s: String) = Hex.decode(s)

    /** RFC 8439 section 2.8.2 AEAD_CHACHA20_POLY1305 test vector. */
    @Test
    fun chacha20Poly1305Rfc8439Vector() {
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val key = h("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = h("070000004041424344454647")
        val aad = h("50515253c0c1c2c3c4c5c6c7")
        val ct = h(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116",
        )
        val tag = h("1ae10b594f09e26a7e902ecbd0600691")
        val sealed = HapCrypto.seal(key, nonce, pt, aad)
        assertArrayEquals(ct + tag, sealed)
        assertArrayEquals(pt, HapCrypto.open(key, nonce, sealed, aad))
        val tampered = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        try {
            HapCrypto.open(key, nonce, tampered, aad)
            fail("tampered ciphertext accepted")
        } catch (e: AuthenticationFailedException) {
        }
    }

    /** Cross-check with Python `cryptography` (independent implementation) using a HAP counter nonce. */
    @Test
    fun chachaWithHapCounterNonceMatchesPython() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = HapCrypto.seal(key, HapCrypto.counterNonce(5), "RTSP/1.0 200 ".toByteArray(), byteArrayOf(13, 0))
        assertEquals("715cc55a6a14690eab98097805a2cea50a45bebe124e4a7a7dd3e3bde7", Hex.encode(sealed))
    }

    /** RFC 5869 A.1 (SHA-256) validates the HKDF wiring; SHA-512 values were cross-checked with Python. */
    @Test
    fun hkdfVectors() {
        val okm = HapCrypto.hkdfSha256(h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), h("000102030405060708090a0b0c"), h("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", Hex.encode(okm))
        val ikm = ByteArray(64) { it.toByte() }
        assertEquals("5a6cb19bcbe7d4df2dd8279f39562f7fae2dbf73eb5a4f98849c245c82b2fe96", Hex.encode(HapCrypto.hkdfSha512(ikm, "Control-Salt", "Control-Write-Encryption-Key")))
        assertEquals("b6335536162d6629e4c0bade85f1b712c85a364bab0dedb25014cfb814489273", Hex.encode(HapCrypto.hkdfSha512(ikm, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")))
        assertArrayEquals(HapCrypto.hkdfSha512(ikm, "Control-Salt", "Control-Write-Encryption-Key"), PairingResult(ikm, true).controlWriteKey())
    }

    /** RFC 7748 section 6.1 X25519 test vector. */
    @Test
    fun x25519Rfc7748Vector() {
        val alice = X25519KeyPair.fromPrivate(h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = X25519KeyPair.fromPrivate(h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", Hex.encode(alice.publicKey))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", Hex.encode(bob.publicKey))
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, Hex.encode(alice.agree(bob.publicKey)))
        assertEquals(shared, Hex.encode(bob.agree(alice.publicKey)))
        try {
            alice.agree(ByteArray(32)) // small-order point
            fail("all-zero shared secret accepted")
        } catch (e: AuthenticationFailedException) {
        }
    }

    /** RFC 8032 section 7.1 TEST 1 Ed25519 vector. */
    @Test
    fun ed25519Rfc8032Vector() {
        val kp = Ed25519KeyPair.fromSeed(h("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", Hex.encode(kp.publicKey))
        val sig = kp.sign(ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            Hex.encode(sig),
        )
        assertTrue(Ed25519KeyPair.verify(kp.publicKey, ByteArray(0), sig))
        assertFalse(Ed25519KeyPair.verify(kp.publicKey, byteArrayOf(1), sig))
        assertFalse(Ed25519KeyPair.verify(kp.publicKey, ByteArray(0), sig.copyOf().also { it[0] = 0 }))
    }

    @Test
    fun nonceLayouts() {
        assertEquals("00000000" + Hex.encode("PS-Msg05".toByteArray()), Hex.encode(HapCrypto.labelNonce("PS-Msg05")))
        assertEquals("000000000100000000000000", Hex.encode(HapCrypto.counterNonce(1)))
        assertEquals("00000000feffffffffffffff", Hex.encode(HapCrypto.counterNonce(-2)))
    }

    /** Length-prefixed encrypted framing: multi-frame messages, independent counters, tamper detection. */
    @Test
    fun hapFramingRoundTrip() {
        val key = ByteArray(32) { (it * 5).toByte() }
        val wire = ByteArrayOutputStream()
        val out = HapSecureOutputStream(wire, key)
        val big = ByteArray(2500) { (it % 251).toByte() }
        out.write("first".toByteArray()); out.flush()
        out.write(big); out.flush()
        assertEquals(1 + 3, out.framesWritten) // 2500 bytes -> 1024 + 1024 + 452
        val bytes = wire.toByteArray()
        assertEquals(5, bytes[0].toInt()) // little-endian length of the first frame
        assertEquals(0, bytes[1].toInt())
        assertEquals(2 + 5 + 16 + 3 * (2 + 16) + 2500, bytes.size)
        val input = HapSecureInputStream(ByteArrayInputStream(bytes), key)
        val back = input.readBytes()
        assertArrayEquals("first".toByteArray() + big, back)

        val tampered = bytes.copyOf().also { it[10] = (it[10].toInt() xor 0x40).toByte() }
        try {
            HapSecureInputStream(ByteArrayInputStream(tampered), key).readBytes()
            fail("tampered frame accepted")
        } catch (e: AuthenticationFailedException) {
        }
        val truncated = bytes.copyOf(bytes.size - 3)
        try {
            HapSecureInputStream(ByteArrayInputStream(truncated), key).readBytes()
            fail("truncated frame accepted")
        } catch (e: IOException) {
        }
    }
}
