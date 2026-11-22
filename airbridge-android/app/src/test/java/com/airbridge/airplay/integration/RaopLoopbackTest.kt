package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AudioCodec
import com.airbridge.airplay.airplay.AuthenticationFailedAirPlayException
import com.airbridge.airplay.airplay.PasswordRequiredException
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.StreamSettings
import com.airbridge.airplay.airplay.raop.RaopConfig
import com.airbridge.airplay.airplay.raop.RaopSender
import com.airbridge.airplay.integration.LoopbackSupport.RTP_BASE
import com.airbridge.airplay.integration.LoopbackSupport.assertReceivedExactly
import com.airbridge.airplay.integration.LoopbackSupport.streamSine
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.testutil.MockRaopReceiver
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.security.SecureRandom

/** Full AirPlay 1 (RAOP) sessions against a real-socket mock receiver on 127.0.0.1. */
class RaopLoopbackTest {
    private val identity = SenderIdentity.random()
    private val loopback = InetAddress.getLoopbackAddress()

    private fun config(mock: MockRaopReceiver, block: RaopConfig.() -> RaopConfig = { this }) =
        RaopConfig(name = "Mock AirPort", host = loopback, port = mock.port, keepAliveSeconds = 1).block()

    @Test
    fun plainAlacSessionEndToEnd() {
        MockRaopReceiver().start().use { mock ->
            val sender = RaopSender(config(mock), identity)
            val timeline = LoopbackSupport.timeline()
            sender.connect(timeline, 0)
            assertEquals("ALAC", mock.codec)
            assertTrue(mock.sdp!!.contains("m=audio 0 RTP/AVP 96"))
            assertTrue("no key exchange for unencrypted receivers", !mock.sdp!!.contains("rsaaeskey"))
            assertEquals(listOf(-15.0), mock.volumes)
            assertNotNull(mock.recordRtpInfo)
            assertTrue(mock.recordRtpInfo!!.endsWith("rtptime=$RTP_BASE"))

            val sent = streamSine(sender, timeline, 200)
            assertReceivedExactly(mock.sink!!, sent, RTP_BASE)
            assertTrue(mock.recordRtpInfo!!.startsWith("seq=${mock.sink!!.firstSeq};"))

            sender.setVolume(-9.5)
            assertTrue(waitUntil { mock.volumes.contains(-9.5) })
            assertTrue("keep-alive OPTIONS sent", waitUntil(3_000) { mock.requests.count { it == "OPTIONS *" } >= 2 })

            sender.flush(200)
            assertEquals(1, mock.flushes)
            val more = streamSine(sender, timeline, 3, startIndex = 200)
            assertTrue(waitUntil { mock.sink!!.frames.size >= 203 })
            assertTrue("first packet after FLUSH carries the marker bit", mock.sink!!.frames.values.toList()[200].marker)
            assertEquals(3, more.size)

            sender.close()
            assertTrue(waitUntil { mock.teardown })
            assertEquals(
                listOf("OPTIONS *", "ANNOUNCE", "SETUP", "RECORD", "SET_PARAMETER"),
                mock.requests.take(5).map { it.substringBefore(" rtsp://") },
            )
            assertEquals("TEARDOWN", mock.requests.last().substringBefore(" rtsp://"))
            assertTrue(mock.errors.isEmpty())
        }
    }

    @Test
    fun rsaAesEncryptedSession() {
        val gen = RSAKeyPairGenerator()
        gen.init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), SecureRandom(), 2048, 80))
        val kp = gen.generateKeyPair()
        MockRaopReceiver(rsaKeyPair = kp).start().use { mock ->
            val sender = RaopSender(config(mock) { copy(encrypt = true, receiverPublicKey = kp.public as RSAKeyParameters) }, identity)
            val timeline = LoopbackSupport.timeline()
            sender.connect(timeline, 0)
            assertTrue(mock.sdp!!.contains("a=rsaaeskey:"))
            assertEquals("receiver unwrapped a 128-bit AES key", 16, mock.aesKey!!.size)
            assertEquals(16, mock.aesIv!!.size)
            val sent = streamSine(sender, timeline, 120)
            assertReceivedExactly(mock.sink!!, sent, RTP_BASE)
            sender.close()
            assertTrue(waitUntil { mock.teardown })
        }
    }

    @Test
    fun passwordProtectedReceiverWithL16() {
        MockRaopReceiver(password = "hunter2").start().use { mock ->
            val sender = RaopSender(config(mock) { copy(password = "hunter2", codec = AudioCodec.L16) }, identity)
            val timeline = LoopbackSupport.timeline()
            sender.connect(timeline, 0)
            assertTrue("receiver challenged with 401", mock.unauthorizedResponses >= 1)
            assertEquals("L16", mock.codec)
            val sent = streamSine(sender, timeline, 80)
            assertReceivedExactly(mock.sink!!, sent, RTP_BASE)
            sender.close()
            assertTrue(waitUntil { mock.teardown })
        }
    }

    @Test
    fun missingOrWrongPasswordIsReported() {
        MockRaopReceiver(password = "hunter2").start().use { mock ->
            try {
                RaopSender(config(mock), identity).connect(LoopbackSupport.timeline(), 0)
                fail("connected without a password")
            } catch (e: PasswordRequiredException) {
            }
            try {
                RaopSender(config(mock) { copy(password = "wrong") }, identity).connect(LoopbackSupport.timeline(), 0)
                fail("connected with a wrong password")
            } catch (e: AuthenticationFailedAirPlayException) {
            }
        }
    }

    @Test
    fun authSetupIsSentWhenAnnounceIsForbidden() {
        MockRaopReceiver(requireAuthSetup = true).start().use { mock ->
            val sender = RaopSender(config(mock) { copy(settings = StreamSettings(initialVolumeDb = -20.0)) }, identity)
            val timeline = LoopbackSupport.timeline()
            sender.connect(timeline, 0)
            assertTrue(mock.authSetupSeen)
            assertEquals(listOf(-20.0), mock.volumes)
            val sent = streamSine(sender, timeline, 40)
            assertReceivedExactly(mock.sink!!, sent, RTP_BASE)
            sender.close()
        }
    }
}
