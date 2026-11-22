package com.airbridge.airplay.testutil

import com.airbridge.airplay.crypto.SrpGroup
import com.airbridge.airplay.crypto.SrpMath
import com.airbridge.airplay.util.ByteUtil
import java.math.BigInteger
import java.security.SecureRandom

/**
 * SRP-6a server side (the receiver's role), following Apple's HomeKit ADK formulas:
 * B = k*v + g^b, S = (A * v^u)^b, K = H(S), M2 = H(PAD(A) | M1 | K). Test-only: AirBridge itself
 * is always the client.
 */
class SrpServer(
    private val group: SrpGroup,
    private val username: String,
    password: ByteArray,
    val salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
    privateKey: BigInteger? = null,
) {
    private val b: BigInteger = privateKey ?: BigInteger(256, SecureRandom())
    private val v: BigInteger = SrpMath.verifier(group, salt, username, password)
    val publicB: BigInteger = SrpMath.k(group).multiply(v).add(group.g.modPow(b, group.n)).mod(group.n)
    val publicKeyBytes: ByteArray get() = group.pad(publicB)

    var sessionKey: ByteArray? = null
        private set

    /** Verifies the client's A and M1; returns M2 or null if the proof (i.e. the PIN) is wrong. */
    fun verifyClient(clientA: ByteArray, clientM1: ByteArray): ByteArray? {
        val a = BigInteger(1, clientA)
        if (a.mod(group.n).signum() == 0) return null
        val u = SrpMath.u(group, a, publicB)
        val s = a.multiply(v.modPow(u, group.n)).modPow(b, group.n)
        val k = SrpMath.sessionKey(group, s)
        val expectedM1 = SrpMath.m1(group, username, salt, a, publicB, k)
        if (!ByteUtil.constantTimeEquals(expectedM1, clientM1)) return null
        sessionKey = k
        return SrpMath.m2(group, a, clientM1, k)
    }
}
