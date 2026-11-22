package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AudioCodec
import com.airbridge.airplay.airplay.AuthenticationFailedAirPlayException
import com.airbridge.airplay.airplay.PasswordRequiredException
import com.airbridge.airplay.airplay.PinRequiredException
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.ap2.Ap2Config
import com.airbridge.airplay.airplay.ap2.Ap2Sender
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import com.airbridge.airplay.airplay.ap2.PairingStore
import com.airbridge.airplay.integration.LoopbackSupport.RTP_BASE
import com.airbridge.airplay.integration.LoopbackSupport.assertReceivedExactly
import com.airbridge.airplay.integration.LoopbackSupport.streamSine
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.testutil.MockAp2Receiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/**
 * Full AirPlay 2 sessions against a real-socket mock receiver on 127.0.0.1: HAP pairing, the
 * encrypted RTSP channel, both SETUP phases, feedback keep-alive, the event channel and
 * ChaCha20-Poly1305 sealed RTP audio that the mock opens and decodes back to the exact PCM.
 */
class Ap2LoopbackTest {
    private val identity = SenderIdentity.random()
    private val loopback = InetAddress.getLoopbackAddress()

    private fun sender(mock: MockAp2Receiver, store: PairingStore, pin: String? = null, password: String? = null, codec: AudioCodec = AudioCodec.ALAC) =
        Ap2Sender(
            Ap2Config(
                name = "Mock HomePod", host = loopback, port = mock.port, receiverKey = "mock-hp",
                pin = pin, password = password, codec = codec, feedbackIntervalMs = 150,
            ),
            identity,
            store,
        )

    @Test
    fun transientPairingFullSession() {
        MockAp2Receiver().start().use { mock ->
            val store = InMemoryPairingStore()
            val s = sender(mock, store)
            val timeline = LoopbackSupport.timeline()
            s.connect(timeline, 0)

            assertEquals("transient pair-setup with PIN 3939", 1, mock.transientSetups)
            assertEquals(0, mock.fullSetups)
            assertEquals(0, mock.verifies)
            assertEquals("transient pair-setup", s.pairingMode)
            assertEquals("Mock HomePod", s.info["name"])
            assertEquals(listOf("GET /info", "POST /pair-setup", "POST /pair-setup"), mock.requests.take(3))
            // Everything after pairing travelled over the encrypted channel.
            assertEquals(listOf("SETUP", "RECORD", "SETUP", "SET_PARAMETER"), mock.encryptedRequests.take(4).map { it.substringBefore(' ') })
            assertEquals("NTP", mock.sessionSetup!!["timingProtocol"])
            assertEquals(identity.deviceId, mock.sessionSetup!!["deviceID"])
            val stream = mock.streamSetup!!
            assertEquals(96L, stream["type"])
            assertEquals(2L, stream["ct"])
            assertEquals(0x40000L, stream["audioFormat"])
            assertEquals(352L, stream["spf"])
            assertEquals(44100L, stream["sr"])
            assertEquals(32, (stream["shk"] as ByteArray).size)
            assertEquals(listOf(-15.0), mock.volumes)

            val sent = streamSine(s, timeline, 200)
            assertReceivedExactly(mock.sink!!, sent, RTP_BASE)

            assertTrue("POST /feedback keep-alive", waitUntil(3_000) { mock.feedbacks >= 2 })
            assertTrue("event channel acknowledged", waitUntil(3_000) { mock.eventAcknowledged })
            s.setVolume(-3.0)
            assertTrue(waitUntil { mock.volumes.contains(-3.0) })
            s.flush(200)
            assertTrue(waitUntil { mock.flushes == 1 })
            s.close()
            assertTrue(waitUntil { mock.teardown })
            assertNull("transient pairing stores nothing", store.load("mock-hp"))
            assertTrue("mock errors: ${mock.errors}", mock.errors.isEmpty())
        }
    }

    @Test
    fun pinPairingThenPairVerifyWithSavedKeys() {
        MockAp2Receiver(statusFlags = MockAp2Receiver.FLAG_PIN, screenPin = "4821").start().use { mock ->
            val store = InMemoryPairingStore()

            // 1. No PIN yet: AirBridge asks the receiver to show one and reports PinRequired.
            try {
                sender(mock, store).connect(LoopbackSupport.timeline(), 0)
                fail("connected without PIN")
            } catch (e: PinRequiredException) {
            }
            assertEquals(1, mock.pinStarts)

            // 2. With the PIN: full pair-setup (M1..M6), saved keys, pair-verify, then audio.
            val s = sender(mock, store, pin = "4821")
            val timeline = LoopbackSupport.timeline()
            s.connect(timeline, 0)
            assertEquals(1, mock.fullSetups)
            assertEquals(1, mock.verifies)
            assertEquals("PIN pair-setup + pair-verify", s.pairingMode)
            assertNotNull(store.load("mock-hp"))
            assertEquals(mock.accessoryId, store.load("mock-hp")!!.accessoryId)
            assertTrue(mock.pairedControllers.containsKey(store.controllerIdentity().pairingId))
            val sent = streamSine(s, timeline, 120)
            assertReceivedExactly(mock.sink!!, sent, RTP_BASE)
            s.close()
            assertTrue(waitUntil { mock.teardown })

            // 3. Next session: pair-verify only with the saved pairing.
            mock.teardown = false
            val again = sender(mock, store)
            val t2 = LoopbackSupport.timeline()
            again.connect(t2, 0)
            assertEquals("no second pair-setup", 1, mock.fullSetups)
            assertEquals(2, mock.verifies)
            assertEquals("pair-verify (saved pairing)", again.pairingMode)
            val sent2 = streamSine(again, t2, 60)
            assertReceivedExactly(mock.sink!!, sent2, RTP_BASE)
            again.close()
            assertTrue(waitUntil { mock.teardown })
            assertTrue("mock errors: ${mock.errors}", mock.errors.isEmpty())
        }
    }

    @Test
    fun wrongPinIsRejected() {
        MockAp2Receiver(statusFlags = MockAp2Receiver.FLAG_PIN, screenPin = "4821").start().use { mock ->
            try {
                sender(mock, InMemoryPairingStore(), pin = "0000").connect(LoopbackSupport.timeline(), 0)
                fail("wrong PIN accepted")
            } catch (e: AuthenticationFailedAirPlayException) {
            }
            assertEquals(0, mock.fullSetups)
        }
    }

    @Test
    fun passwordProtectedReceiver() {
        MockAp2Receiver(statusFlags = MockAp2Receiver.FLAG_PASSWORD, password = "hunter2").start().use { mock ->
            val store = InMemoryPairingStore()
            try {
                sender(mock, store).connect(LoopbackSupport.timeline(), 0)
                fail("connected without password")
            } catch (e: PasswordRequiredException) {
            }
            val s = sender(mock, store, password = "hunter2", codec = AudioCodec.L16)
            val timeline = LoopbackSupport.timeline()
            s.connect(timeline, 0)
            assertEquals(1, mock.fullSetups)
            assertEquals(1, mock.verifies)
            assertEquals(1L, mock.streamSetup!!["ct"])
            assertEquals(0x800L, mock.streamSetup!!["audioFormat"])
            val sent = streamSine(s, timeline, 80)
            assertReceivedExactly(mock.sink!!, sent, RTP_BASE)
            s.close()
        }
    }

    @Test
    fun http470SwitchesToPinPairing() {
        MockAp2Receiver(reject470ForTransient = true).start().use { mock ->
            try {
                sender(mock, InMemoryPairingStore()).connect(LoopbackSupport.timeline(), 0)
                fail("connected despite 470")
            } catch (e: PinRequiredException) {
            }
            assertEquals("receiver asked to display a PIN", 1, mock.pinStarts)
        }
    }
}
