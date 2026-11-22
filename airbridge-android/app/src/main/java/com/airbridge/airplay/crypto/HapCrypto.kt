package com.airbridge.airplay.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** Thrown when an authenticated ciphertext fails verification. */
class AuthenticationFailedException(message: String) : Exception(message)

/**
 * Crypto primitives used by HAP pairing and the AirPlay 2 encrypted transport. Everything is the
 * BouncyCastle lightweight API so it behaves identically on Android 10+ and on the JVM test host.
 */
object HapCrypto {
    const val CHACHA_KEY_LENGTH = 32
    const val TAG_LENGTH = 16

    fun hkdfSha512(ikm: ByteArray, salt: String, info: String, length: Int = 32): ByteArray =
        hkdf(SHA512Digest(), ikm, salt.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.US_ASCII), length)

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdf(SHA256Digest(), ikm, salt, info, length)

    fun hkdf(digest: Digest, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(digest)
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    /** 12-byte nonce: four zero bytes followed by an 8-byte ASCII label such as "PS-Msg05". */
    fun labelNonce(label: String): ByteArray {
        val l = label.toByteArray(Charsets.US_ASCII)
        require(l.size == 8) { "nonce label must be 8 bytes" }
        return ByteArray(4) + l
    }

    /** 12-byte nonce: four zero bytes followed by a 64-bit little-endian counter. */
    fun counterNonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
        return n
    }

    /** ChaCha20-Poly1305 (RFC 8439) encryption; returns ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray =
        seal(key, nonce, plaintext, 0, plaintext.size, aad)

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, offset: Int, length: Int, aad: ByteArray?): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), TAG_LENGTH * 8, nonce, aad))
        val out = ByteArray(c.getOutputSize(length))
        var n = c.processBytes(plaintext, offset, length, out, 0)
        n += c.doFinal(out, n)
        return if (n == out.size) out else out.copyOf(n)
    }

    /** ChaCha20-Poly1305 decryption of ciphertext||tag; throws [AuthenticationFailedException]. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray =
        open(key, nonce, sealed, 0, sealed.size, aad)

    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, offset: Int, length: Int, aad: ByteArray?): ByteArray {
        if (length < TAG_LENGTH) throw AuthenticationFailedException("ciphertext shorter than tag")
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), TAG_LENGTH * 8, nonce, aad))
        val out = ByteArray(c.getOutputSize(length))
        try {
            var n = c.processBytes(sealed, offset, length, out, 0)
            n += c.doFinal(out, n)
            return if (n == out.size) out else out.copyOf(n)
        } catch (e: InvalidCipherTextException) {
            throw AuthenticationFailedException("ChaCha20-Poly1305 authentication failed")
        }
    }
}

/** Curve25519 Diffie-Hellman key pair. */
class X25519KeyPair private constructor(private val privateKey: X25519PrivateKeyParameters) {
    val publicKey: ByteArray = privateKey.generatePublicKey().encoded

    /** Shared secret with [peerPublicKey]; rejects small-order (all-zero) results. */
    fun agree(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == 32) { "X25519 public key must be 32 bytes" }
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val out = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw AuthenticationFailedException("X25519 produced an all-zero shared secret")
        }
        return out
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()) = X25519KeyPair(X25519PrivateKeyParameters(random))
        fun fromPrivate(privateKey: ByteArray) = X25519KeyPair(X25519PrivateKeyParameters(privateKey, 0))
    }
}

/** Ed25519 signing key pair (HAP long-term keys). */
class Ed25519KeyPair private constructor(private val privateKey: Ed25519PrivateKeyParameters) {
    val publicKey: ByteArray = privateKey.generatePublicKey().encoded

    /** The 32-byte private seed (persisted for stored pairings). */
    val seed: ByteArray get() = privateKey.encoded

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()) = Ed25519KeyPair(Ed25519PrivateKeyParameters(random))
        fun fromSeed(seed: ByteArray) = Ed25519KeyPair(Ed25519PrivateKeyParameters(seed, 0))

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            if (publicKey.size != 32 || signature.size != 64) return false
            return try {
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
                verifier.update(message, 0, message.size)
                verifier.verifySignature(signature)
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
}
