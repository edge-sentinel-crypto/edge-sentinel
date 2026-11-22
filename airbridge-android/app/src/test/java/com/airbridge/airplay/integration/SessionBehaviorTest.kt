package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.ConnectOptions
import com.airbridge.airplay.session.DefaultSenderFactory
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.testutil.MockAp2Receiver
import com.airbridge.airplay.testutil.MockRaopReceiver
import com.airbridge.airplay.testutil.TestAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session-level behaviour: automatic AirPlay 2 -> 1 fallback and the PIN prompt/retry cycle. */
class SessionBehaviorTest {
    private fun manager() = AirPlaySessionManager(DefaultSenderFactory(SenderIdentity.random(), InMemoryPairingStore()))

    @Test
    fun fallsBackToAirPlay1WhenAirPlay2SetupIsRejected() {
        MockAp2Receiver(rejectNtpSetup = true).start().use { ap2 ->
            MockRaopReceiver().start().use { ap1 ->
                val receiver = AirPlayReceiver(
                    id = "pi", name = "Pi Speaker", host = "127.0.0.1", raopPort = ap1.port, airplayPort = ap2.port,
                    airplayTxt = mapOf("features" to "0x401C4A00,0x1C340", "model" to "GenericAirPlay2"),
                    raopTxt = mapOf("cn" to "0,1", "et" to "0,4"),
                )
                assertEquals(AirPlayProtocol.AIRPLAY_2, receiver.recommendedProtocol)
                val m = manager()
                m.start()
                m.connect(receiver, ConnectOptions())
                assertTrue(waitUntil(15_000) { m.statuses.value["pi"]?.state == SessionState.STREAMING })
                assertEquals("fell back to AirPlay 1", AirPlayProtocol.AIRPLAY_1, m.statuses.value["pi"]!!.protocol)
                assertTrue("AirPlay 2 was tried first", ap2.requests.contains("POST /pair-setup"))
                val chunks = TestAudio.sineChunks(30)
                chunks.forEach { m.push(it); Thread.sleep(2) }
                assertTrue(ap1.sink!!.waitForFrames(30))
                assertArrayEquals(TestAudio.concat(chunks), ap1.sink!!.pcmInOrder())
                m.stop()
                assertTrue(waitUntil { ap1.teardown })
            }
        }
    }

    @Test
    fun forcedAirPlay2DoesNotFallBack() {
        MockAp2Receiver(rejectNtpSetup = true).start().use { ap2 ->
            MockRaopReceiver().start().use { ap1 ->
                val receiver = AirPlayReceiver(
                    id = "pi", name = "Pi Speaker", host = "127.0.0.1", raopPort = ap1.port, airplayPort = ap2.port,
                    airplayTxt = mapOf("features" to "0x401C4A00,0x1C340"),
                )
                val m = manager()
                m.start()
                m.connect(receiver, ConnectOptions(preference = ProtocolPreference.AIRPLAY_2))
                assertTrue(waitUntil(10_000) { m.statuses.value["pi"]?.state == SessionState.RETRYING })
                assertTrue(m.statuses.value["pi"]!!.message!!.contains("SETUP"))
                assertTrue("AirPlay 1 untouched", ap1.requests.isEmpty())
                m.stop()
            }
        }
    }

    @Test
    fun pinPromptThenStreamingAndWrongPinAsksForNewCode() {
        MockAp2Receiver(statusFlags = MockAp2Receiver.FLAG_PIN, screenPin = "1357").start().use { tv ->
            val receiver = AirPlayReceiver(
                id = "atv", name = "Family Room TV", host = "127.0.0.1", airplayPort = tv.port,
                airplayTxt = mapOf("features" to "0x4A7FFFF7,0x3C155FDE", "flags" to "0x8"),
            )
            val m = manager()
            m.start()
            m.connect(receiver, ConnectOptions())
            assertTrue(waitUntil { m.statuses.value["atv"]?.state == SessionState.NEEDS_PIN })
            assertEquals(1, tv.pinStarts)

            m.providePin("atv", "0000")
            assertTrue(waitUntil { tv.pinStarts == 2 && m.statuses.value["atv"]?.state == SessionState.NEEDS_PIN })
            assertTrue(m.statuses.value["atv"]!!.message!!.startsWith("Wrong code"))

            m.providePin("atv", "1357")
            assertTrue(waitUntil(15_000) { m.statuses.value["atv"]?.state == SessionState.STREAMING })
            assertEquals(1, tv.fullSetups)
            val chunks = TestAudio.sineChunks(20)
            chunks.forEach { m.push(it); Thread.sleep(2) }
            assertTrue(tv.sink!!.waitForFrames(20))
            assertArrayEquals(TestAudio.concat(chunks), tv.sink!!.pcmInOrder())
            m.stop()
            assertTrue(waitUntil { tv.teardown })
        }
    }
}
