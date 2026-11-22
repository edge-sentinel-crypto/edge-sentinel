package com.airbridge.airplay.crypto

import com.airbridge.airplay.util.ByteUtil
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** An SRP group: safe prime N, generator g and the hash used for all SRP hashing. */
class SrpGroup(val n: BigInteger, val g: BigInteger, val hashAlgorithm: String) {
    /** Length of N in bytes; PAD() pads to this length. */
    val nLength: Int = (n.bitLength() + 7) / 8

    fun hash(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(hashAlgorithm)
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun pad(v: BigInteger): ByteArray = ByteUtil.toUnsignedPadded(v, nLength)

    companion object {
        /** RFC 5054 Appendix A, 3072-bit group, g = 5, with SHA-512 as used by HomeKit/AirPlay 2. */
        val RFC5054_3072_SHA512: SrpGroup by lazy {
            SrpGroup(
                BigInteger(
                    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                        "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                        "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
                        "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
                        "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                        "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                        "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
                        "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                        "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
                        "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
                        "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
                    16,
                ),
                BigInteger.valueOf(5),
                "SHA-512",
            )
        }
    }
}

/**
 * SRP-6a math shared by the client (and by the loopback mock receiver in tests), using the
 * conventions of Apple's HomeKit ADK (PAL/Crypto/OpenSSL/HAPOpenSSL.c), which iOS and every
 * AirPlay 2 receiver interoperate with:
 *
 *   k  = H(PAD(N) | PAD(g))
 *   x  = H(salt | H(I | ":" | P))
 *   u  = H(PAD(A) | PAD(B))
 *   K  = H(S)                     (S without leading zero bytes)
 *   M1 = H(H(N) xor H(g) | H(I) | salt | A | B | K)   (A, B without leading zeros; g as 1 byte)
 *   M2 = H(PAD(A) | M1 | K)
 *
 * The client always picks an `a` whose public value A has no leading zero byte, so every
 * padded/unpadded variant of A is identical and no receiver convention can disagree.
 */
object SrpMath {
    fun k(group: SrpGroup): BigInteger = BigInteger(1, group.hash(group.pad(group.n), group.pad(group.g)))

    fun x(group: SrpGroup, salt: ByteArray, username: String, password: ByteArray): BigInteger {
        val inner = group.hash(username.toByteArray(Charsets.UTF_8), ":".toByteArray(Charsets.UTF_8), password)
        return BigInteger(1, group.hash(salt, inner))
    }

    fun u(group: SrpGroup, a: BigInteger, b: BigInteger): BigInteger =
        BigInteger(1, group.hash(group.pad(a), group.pad(b)))

    fun sessionKey(group: SrpGroup, s: BigInteger): ByteArray = group.hash(ByteUtil.toUnsignedMinimal(s))

    fun m1(group: SrpGroup, username: String, salt: ByteArray, a: BigInteger, b: BigInteger, key: ByteArray): ByteArray {
        val hN = group.hash(ByteUtil.toUnsignedMinimal(group.n))
        val hG = group.hash(ByteUtil.toUnsignedMinimal(group.g))
        val xor = ByteArray(hN.size) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        val hI = group.hash(username.toByteArray(Charsets.UTF_8))
        return group.hash(xor, hI, salt, ByteUtil.toUnsignedMinimal(a), ByteUtil.toUnsignedMinimal(b), key)
    }

    fun m2(group: SrpGroup, a: BigInteger, m1: ByteArray, key: ByteArray): ByteArray = group.hash(group.pad(a), m1, key)

    fun verifier(group: SrpGroup, salt: ByteArray, username: String, password: ByteArray): BigInteger =
        group.g.modPow(x(group, salt, username, password), group.n)
}

/**
 * SRP-6a client for HAP pair-setup (username "Pair-Setup", password = PIN, e.g. "3939" for
 * transient pairing).
 */
class SrpClient(
    private val group: SrpGroup,
    private val username: String,
    password: ByteArray,
    random: SecureRandom = SecureRandom(),
    fixedPrivateKey: BigInteger? = null,
) {
    class SrpException(message: String) : Exception(message)

    private val password = password.copyOf()
    private val a: BigInteger
    val publicA: BigInteger

    private var sharedS: BigInteger? = null
    private var key: ByteArray? = null
    private var clientProof: ByteArray? = null

    init {
        if (fixedPrivateKey != null) {
            a = fixedPrivateKey
            publicA = group.g.modPow(a, group.n)
        } else {
            var candidateA: BigInteger
            var candidate: BigInteger
            do {
                candidateA = BigInteger(256, random)
                candidate = group.g.modPow(candidateA, group.n)
                // Require a full-length A (no leading zero byte) so padding conventions never matter.
            } while (candidateA.signum() == 0 || ByteUtil.toUnsignedMinimal(candidate).size != group.nLength)
            a = candidateA
            publicA = candidate
        }
    }

    /** A as sent on the wire (PAD(A), 384 bytes for the 3072-bit group). */
    val publicKeyBytes: ByteArray get() = group.pad(publicA)

    /** Processes the server's salt and B; returns the client proof M1. */
    fun processChallenge(salt: ByteArray, serverPublicKey: ByteArray): ByteArray {
        val b = BigInteger(1, serverPublicKey)
        if (b.mod(group.n).signum() == 0) throw SrpException("invalid server public key")
        val u = SrpMath.u(group, publicA, b)
        if (u.signum() == 0) throw SrpException("invalid scrambling parameter")
        val k = SrpMath.k(group)
        val x = SrpMath.x(group, salt, username, password)
        val gx = group.g.modPow(x, group.n)
        var base = b.subtract(k.multiply(gx)).mod(group.n)
        if (base.signum() < 0) base = base.add(group.n)
        val s = base.modPow(a.add(u.multiply(x)), group.n)
        sharedS = s
        val kBytes = SrpMath.sessionKey(group, s)
        key = kBytes
        val m1 = SrpMath.m1(group, username, salt, publicA, b, kBytes)
        clientProof = m1
        return m1
    }

    /** Checks the server proof M2 = H(PAD(A) | M1 | K). */
    fun verifyServerProof(serverProof: ByteArray): Boolean {
        val m1 = clientProof ?: throw SrpException("challenge not processed")
        val expected = SrpMath.m2(group, publicA, m1, key!!)
        return ByteUtil.constantTimeEquals(expected, serverProof)
    }

    /** Shared session key K (64 bytes with SHA-512). */
    val sessionKey: ByteArray get() = key?.copyOf() ?: throw SrpException("challenge not processed")

    /** Premaster secret S (exposed for test-vector verification). */
    val premasterSecret: BigInteger get() = sharedS ?: throw SrpException("challenge not processed")
}
