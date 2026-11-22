package com.airbridge.airplay.airplay.ap2

import com.airbridge.airplay.airplay.AuthenticationFailedAirPlayException
import com.airbridge.airplay.airplay.PinRequiredException
import com.airbridge.airplay.airplay.ProtocolFailureException
import com.airbridge.airplay.airplay.ReceiverBusyException
import com.airbridge.airplay.airplay.RtspClient
import com.airbridge.airplay.airplay.RtspResponse
import com.airbridge.airplay.codec.Tlv8
import com.airbridge.airplay.codec.Tlv8.Type
import com.airbridge.airplay.crypto.AuthenticationFailedException
import com.airbridge.airplay.crypto.Ed25519KeyPair
import com.airbridge.airplay.crypto.HapCrypto
import com.airbridge.airplay.crypto.SrpClient
import com.airbridge.airplay.crypto.SrpGroup
import com.airbridge.airplay.crypto.X25519KeyPair
import com.airbridge.airplay.util.AirLog
import com.airbridge.airplay.util.ByteUtil
import java.security.SecureRandom

/** AirBridge's long-term HAP identity (pairing id + Ed25519 key), created once per install. */
class ControllerIdentity(val pairingId: String, val signingKey: Ed25519KeyPair) {
    companion object {
        fun generate(random: SecureRandom = SecureRandom()): ControllerIdentity =
            ControllerIdentity(java.util.UUID.randomUUID().toString().uppercase(java.util.Locale.US), Ed25519KeyPair.generate(random))
    }
}

/** A completed PIN/password pairing with one receiver, used later for pair-verify. */
data class AccessoryPairing(val accessoryId: String, val accessoryLtpk: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is AccessoryPairing && other.accessoryId == accessoryId && other.accessoryLtpk.contentEquals(accessoryLtpk)

    override fun hashCode(): Int = accessoryId.hashCode() * 31 + accessoryLtpk.contentHashCode()
}

/** Result of pairing: the secret all session keys are derived from. */
class PairingResult(val sharedSecret: ByteArray, val transient: Boolean) {
    fun controlWriteKey(): ByteArray = HapCrypto.hkdfSha512(sharedSecret, "Control-Salt", "Control-Write-Encryption-Key")
    fun controlReadKey(): ByteArray = HapCrypto.hkdfSha512(sharedSecret, "Control-Salt", "Control-Read-Encryption-Key")

    /** Event channel: the receiver initiates requests, so the key names are mirrored (as in pyatv). */
    fun eventWriteKey(): ByteArray = HapCrypto.hkdfSha512(sharedSecret, "Events-Salt", "Events-Read-Encryption-Key")
    fun eventReadKey(): ByteArray = HapCrypto.hkdfSha512(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key")

    /** 32-byte audio key (`shk`): the first 32 bytes of the pairing secret, as owntone does. */
    fun audioKey(): ByteArray = sharedSecret.copyOf(32)
}

/**
 * HomeKit Accessory Protocol pairing as used by AirPlay 2 (over the RTSP connection):
 *
 *  - Transient pair-setup (M1..M4, flags 0x10, SRP-6a with PIN "3939" or the receiver password):
 *    the SRP session key secures this one session; nothing is stored.
 *  - PIN / password pair-setup (M1..M6): exchanges Ed25519 long-term keys so later sessions only
 *    need pair-verify.
 *  - Pair-verify (M1..M4): X25519 ECDH authenticated with both Ed25519 keys; the session keys
 *    come from the X25519 secret.
 */
class HapPairing(
    private val rtsp: RtspClient,
    private val identity: ControllerIdentity,
    private val random: SecureRandom = SecureRandom(),
) {
    private val group = SrpGroup.RFC5054_3072_SHA512

    /** Asks an Apple TV-style receiver to show a 4-digit PIN on screen. */
    fun pinStart() {
        val resp = rtsp.request("POST", "/pair-pin-start", extraHeaders = mapOf("X-Apple-HKP" to "3"))
        if (!resp.isSuccess) throw ProtocolFailureException("receiver did not start PIN pairing (${resp.statusCode})")
    }

    /** Transient pairing. Returns the SRP session key K (64 bytes). */
    fun pairSetupTransient(pin: String = TRANSIENT_PIN): PairingResult {
        val srp = srpExchange(pin, transient = true)
        AirLog.i(TAG, "transient pair-setup complete")
        return PairingResult(srp.sessionKey, transient = true)
    }

    /**
     * Full pair-setup with a PIN (shown on screen) or password. Returns the accessory's long-term
     * identity to store; follow with [pairVerify].
     */
    fun pairSetupWithPin(pin: String): AccessoryPairing {
        val srp = srpExchange(pin, transient = false)
        val k = srp.sessionKey
        val encKey = HapCrypto.hkdfSha512(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")

        // M5: our pairing id, long-term public key and signature, encrypted.
        val controllerX = HapCrypto.hkdfSha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val idBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature = identity.signingKey.sign(ByteUtil.concat(controllerX, idBytes, identity.signingKey.publicKey))
        val sub = Tlv8.encode(
            Type.IDENTIFIER to idBytes,
            Type.PUBLIC_KEY to identity.signingKey.publicKey,
            Type.SIGNATURE to signature,
        )
        val m5 = Tlv8.encode(
            Type.STATE to Tlv8.byte(5),
            Type.ENCRYPTED_DATA to HapCrypto.seal(encKey, HapCrypto.labelNonce("PS-Msg05"), sub),
        )
        val m6 = post("/pair-setup", m5, hkp = "3", expectState = 6)
        val encrypted = m6[Type.ENCRYPTED_DATA] ?: throw ProtocolFailureException("pair-setup M6 has no encrypted data")
        val plain = try {
            HapCrypto.open(encKey, HapCrypto.labelNonce("PS-Msg06"), encrypted)
        } catch (e: AuthenticationFailedException) {
            throw AuthenticationFailedAirPlayException("pair-setup M6 could not be decrypted")
        }
        val m6sub = Tlv8.decode(plain)
        val accessoryId = m6sub[Type.IDENTIFIER] ?: throw ProtocolFailureException("M6 missing accessory identifier")
        val accessoryLtpk = m6sub[Type.PUBLIC_KEY] ?: throw ProtocolFailureException("M6 missing accessory public key")
        val accessorySig = m6sub[Type.SIGNATURE] ?: throw ProtocolFailureException("M6 missing accessory signature")
        val accessoryX = HapCrypto.hkdfSha512(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        if (!Ed25519KeyPair.verify(accessoryLtpk, ByteUtil.concat(accessoryX, accessoryId, accessoryLtpk), accessorySig)) {
            throw AuthenticationFailedAirPlayException("receiver's pairing signature is invalid")
        }
        AirLog.i(TAG, "PIN pair-setup complete")
        return AccessoryPairing(String(accessoryId, Charsets.UTF_8), accessoryLtpk)
    }

    /** Pair-verify against a stored pairing; returns the X25519 shared secret. */
    fun pairVerify(pairing: AccessoryPairing): PairingResult {
        val eph = X25519KeyPair.generate(random)
        val m2 = post(
            "/pair-verify",
            Tlv8.encode(Type.STATE to Tlv8.byte(1), Type.PUBLIC_KEY to eph.publicKey),
            hkp = "3",
            expectState = 2,
        )
        val accessoryEph = m2[Type.PUBLIC_KEY] ?: throw ProtocolFailureException("pair-verify M2 missing public key")
        val encrypted = m2[Type.ENCRYPTED_DATA] ?: throw ProtocolFailureException("pair-verify M2 missing encrypted data")
        val shared = eph.agree(accessoryEph)
        val key = HapCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sub = try {
            Tlv8.decode(HapCrypto.open(key, HapCrypto.labelNonce("PV-Msg02"), encrypted))
        } catch (e: AuthenticationFailedException) {
            throw AuthenticationFailedAirPlayException("pair-verify M2 could not be decrypted")
        }
        val accessoryId = sub[Type.IDENTIFIER] ?: throw ProtocolFailureException("pair-verify M2 missing identifier")
        val accessorySig = sub[Type.SIGNATURE] ?: throw ProtocolFailureException("pair-verify M2 missing signature")
        if (String(accessoryId, Charsets.UTF_8) != pairing.accessoryId) {
            throw AuthenticationFailedAirPlayException("receiver identity changed; pair again")
        }
        if (!Ed25519KeyPair.verify(pairing.accessoryLtpk, ByteUtil.concat(accessoryEph, accessoryId, eph.publicKey), accessorySig)) {
            throw AuthenticationFailedAirPlayException("receiver's pair-verify signature is invalid")
        }
        val idBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val ourSig = identity.signingKey.sign(ByteUtil.concat(eph.publicKey, idBytes, accessoryEph))
        val m3sub = Tlv8.encode(Type.IDENTIFIER to idBytes, Type.SIGNATURE to ourSig)
        post(
            "/pair-verify",
            Tlv8.encode(
                Type.STATE to Tlv8.byte(3),
                Type.ENCRYPTED_DATA to HapCrypto.seal(key, HapCrypto.labelNonce("PV-Msg03"), m3sub),
            ),
            hkp = "3",
            expectState = 4,
        )
        AirLog.i(TAG, "pair-verify complete")
        return PairingResult(shared, transient = false)
    }

    private fun srpExchange(pin: String, transient: Boolean): SrpClient {
        val hkp = if (transient) "4" else "3"
        val m1 = ArrayList<Pair<Int, ByteArray>>()
        m1.add(Type.STATE to Tlv8.byte(1))
        m1.add(Type.METHOD to Tlv8.byte(0))
        if (transient) m1.add(Type.FLAGS to Tlv8.byte(FLAG_TRANSIENT))
        val m2 = post("/pair-setup", Tlv8.encode(m1), hkp = hkp, expectState = 2)
        val salt = m2[Type.SALT] ?: throw ProtocolFailureException("pair-setup M2 missing salt")
        val serverPublic = m2[Type.PUBLIC_KEY] ?: throw ProtocolFailureException("pair-setup M2 missing public key")
        if (serverPublic.size > group.nLength) throw ProtocolFailureException("pair-setup M2 public key too long")
        val srp = SrpClient(group, "Pair-Setup", pin.toByteArray(Charsets.UTF_8), random)
        val proof = srp.processChallenge(salt, serverPublic)
        val m4 = post(
            "/pair-setup",
            Tlv8.encode(Type.STATE to Tlv8.byte(3), Type.PUBLIC_KEY to srp.publicKeyBytes, Type.PROOF to proof),
            hkp = hkp,
            expectState = 4,
        )
        val serverProof = m4[Type.PROOF] ?: throw ProtocolFailureException("pair-setup M4 missing proof")
        if (!srp.verifyServerProof(serverProof)) throw AuthenticationFailedAirPlayException("receiver's SRP proof is invalid")
        return srp
    }

    private fun post(path: String, body: ByteArray, hkp: String, expectState: Int): Map<Int, ByteArray> {
        val resp: RtspResponse = rtsp.request(
            "POST", path, body, "application/octet-stream",
            extraHeaders = mapOf("X-Apple-HKP" to hkp),
        )
        if (resp.statusCode == 470) throw PinRequiredException("receiver requires PIN pairing")
        if (resp.statusCode == 403 || resp.statusCode == 401) {
            throw AuthenticationFailedAirPlayException("receiver refused pairing (${resp.statusCode})")
        }
        if (!resp.isSuccess) throw ProtocolFailureException("$path failed with ${resp.statusCode} ${resp.reason}")
        val tlv = try {
            Tlv8.decode(resp.body)
        } catch (e: Tlv8.TlvFormatException) {
            throw ProtocolFailureException("$path returned malformed TLV8")
        }
        tlv[Type.ERROR]?.let { err ->
            val code = err.firstOrNull()?.toInt()?.and(0xFF) ?: 1
            when (code) {
                Tlv8.Error.AUTHENTICATION -> throw AuthenticationFailedAirPlayException("wrong PIN or password")
                Tlv8.Error.BUSY -> throw ReceiverBusyException("receiver is busy pairing with another device")
                else -> throw AuthenticationFailedAirPlayException("pairing failed: ${Tlv8.Error.describe(code)}")
            }
        }
        val state = tlv[Type.STATE]?.firstOrNull()?.toInt()?.and(0xFF)
        if (state != expectState) throw ProtocolFailureException("$path: expected state $expectState, got $state")
        return tlv
    }

    companion object {
        private const val TAG = "HAP"
        const val TRANSIENT_PIN = "3939"
        const val FLAG_TRANSIENT = 0x10
    }
}
