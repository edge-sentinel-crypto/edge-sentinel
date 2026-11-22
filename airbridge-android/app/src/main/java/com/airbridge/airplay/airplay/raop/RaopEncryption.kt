package com.airbridge.airplay.airplay.raop

import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.params.RSAKeyParameters
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional AirPlay 1 payload encryption (receivers advertising `et=1` / `ek=1`, e.g. first and
 * second generation AirPort Express): a random AES-128 key is wrapped with RSA-OAEP (SHA-1) for the
 * receiver's well-known public key and sent in the ANNOUNCE SDP; each audio payload is then
 * AES-128-CBC encrypted from a fresh IV state, whole 16-byte blocks only (the tail stays clear).
 */
class RaopEncryption(
    random: SecureRandom = SecureRandom(),
    receiverPublicKey: RSAKeyParameters = AIRPORT_PUBLIC_KEY,
) {
    val aesKey: ByteArray = ByteArray(16).also { random.nextBytes(it) }
    val aesIv: ByteArray = ByteArray(16).also { random.nextBytes(it) }

    val wrappedKey: ByteArray = OAEPEncoding(RSAEngine(), SHA1Digest()).run {
        init(true, ParametersWithRandom(receiverPublicKey, random))
        processBlock(aesKey, 0, aesKey.size)
    }

    private val keySpec = SecretKeySpec(aesKey, "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/CBC/NoPadding")

    /** SDP attribute lines (base64 without padding, as iTunes sends them). */
    fun sdpAttributes(): String {
        val enc = Base64.getEncoder().withoutPadding()
        return "a=rsaaeskey:${enc.encodeToString(wrappedKey)}\r\na=aesiv:${enc.encodeToString(aesIv)}\r\n"
    }

    @Synchronized
    fun encryptPayload(payload: ByteArray): ByteArray {
        val out = payload.copyOf()
        val blockBytes = payload.size / 16 * 16
        if (blockBytes > 0) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(aesIv))
            val enc = cipher.doFinal(payload, 0, blockBytes)
            System.arraycopy(enc, 0, out, 0, blockBytes)
        }
        return out
    }

    companion object {
        /**
         * Public half of the AirPort Express RAOP key (2048-bit, e = 65537). It is the key every
         * AirPlay 1 receiver that supports RSA key exchange decrypts with; verified byte-for-byte
         * against owntone's raop.c and against the modulus of Shairport-Sync's receiver key.
         */
        private const val AIRPORT_MODULUS_B64 =
            "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t" +
                "9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0u" +
                "owxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhim" +
                "NVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="

        val AIRPORT_PUBLIC_KEY: RSAKeyParameters by lazy {
            RSAKeyParameters(false, BigInteger(1, Base64.getDecoder().decode(AIRPORT_MODULUS_B64)), BigInteger.valueOf(65537))
        }

        /** Decrypts a payload produced by [encryptPayload] (used by the loopback mock receiver). */
        fun decryptPayload(aesKey: ByteArray, aesIv: ByteArray, payload: ByteArray): ByteArray {
            val out = payload.copyOf()
            val blockBytes = payload.size / 16 * 16
            if (blockBytes > 0) {
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
                System.arraycopy(c.doFinal(payload, 0, blockBytes), 0, out, 0, blockBytes)
            }
            return out
        }
    }
}
