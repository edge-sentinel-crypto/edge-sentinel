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
import com.airbridge.airplay.ui.ReceiverUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speaker card says "AirPlay 1 (fallback)" when Auto mode could not use AirPlay 2 (here: the
 * receiver rejects the NTP timing SETUP) and the speaker plays over AirPlay 1 instead; it does not
 * say so for a speaker that simply uses AirPlay 1, or when AirPlay 2 works.
 */
class FallbackStatusTest {
    private fun manager() = AirPlaySessionManager(DefaultSenderFactory(SenderIdentity.random(), InMemoryPairingStore()))

    private fun dualSpeaker(ap2: MockAp2Receiver, ap1: MockRaopReceiver) = AirPlayReceiver(
        id = "porch", name = "Porch", host = "127.0.0.1", raopPort = ap1.port, airplayPort = ap2.port,
        airplayTxt = mapOf("features" to "0x401C4A00,0x1C340", "model" to "GenericAirPlay2"),
        raopTxt = mapOf("cn" to "0,1", "et" to "0,4"),
    )

    private fun row(receiver: AirPlayReceiver, m: AirPlaySessionManager, pref: ProtocolPreference = ProtocolPreference.AUTO) =
        ReceiverUi(receiver, enabled = true, status = m.statuses.value[receiver.id], volume = 50, preference = pref)

    @Test
    fun ntpRejectingSpeakerShowsAirPlay1Fallback() {
        MockAp2Receiver(rejectNtpSetup = true).start().use { ap2 ->
            MockRaopReceiver().start().use { ap1 ->
                val receiver = dualSpeaker(ap2, ap1)
                val m = manager()
                m.start()
                m.connect(receiver, ConnectOptions())
                assertTrue(waitUntil(15_000) { m.statuses.value["porch"]?.state == SessionState.STREAMING })
                val status = m.statuses.value.getValue("porch")
                assertEquals(AirPlayProtocol.AIRPLAY_1, status.protocol)
                assertEquals(AirPlayProtocol.AIRPLAY_2, status.fallbackFrom)
                assertTrue(status.isFallback)
                assertEquals("AirPlay 1 (fallback)", status.protocolLabel)
                assertTrue("reason kept for Diagnostics: ${status.fallbackReason}", status.fallbackReason!!.contains("SETUP (session) failed with 500"))
                val ui = row(receiver, m)
                assertTrue(ui.isFallback)
                assertEquals("AirPlay 1 (fallback)", ui.protocolLabel)

                val chunks = TestAudio.sineChunks(20)
                chunks.forEach { m.push(it); Thread.sleep(2) }
                assertTrue("audio flows over AirPlay 1", ap1.sink!!.waitForFrames(20))
                m.stop()
                assertTrue(waitUntil { ap1.teardown })
            }
        }
    }

    @Test
    fun noFallbackLabelWhenAirPlay2WorksOrAirPlay1IsChosen() {
        MockAp2Receiver().start().use { ap2 ->
            MockRaopReceiver().start().use { ap1 ->
                val receiver = dualSpeaker(ap2, ap1)
                val m = manager()
                m.start()
                m.connect(receiver, ConnectOptions())
                assertTrue(waitUntil(15_000) { m.statuses.value["porch"]?.state == SessionState.STREAMING })
                var status = m.statuses.value.getValue("porch")
                assertEquals(AirPlayProtocol.AIRPLAY_2, status.protocol)
                assertNull(status.fallbackFrom)
                assertFalse(status.isFallback)
                assertEquals("AirPlay 2", row(receiver, m).protocolLabel)

                // Forced AirPlay 1 is the user's choice, not a fallback.
                m.connect(receiver, ConnectOptions(preference = ProtocolPreference.AIRPLAY_1))
                assertTrue(
                    waitUntil(15_000) {
                        m.statuses.value["porch"]?.let { it.state == SessionState.STREAMING && it.protocol == AirPlayProtocol.AIRPLAY_1 } == true
                    },
                )
                Thread.sleep(300) // the replaced session's last "stopped" update must not overwrite the new one
                status = m.statuses.value.getValue("porch")
                assertEquals(SessionState.STREAMING, status.state)
                assertFalse(status.isFallback)
                assertEquals("AirPlay 1", status.protocolLabel)
                assertEquals("AirPlay 1", row(receiver, m, ProtocolPreference.AIRPLAY_1).protocolLabel)
                m.stop()
            }
        }
    }

    @Test
    fun labelFollowsTheSpeakerBeforeItConnects() {
        val receiver = AirPlayReceiver(
            id = "k", name = "Kitchen", host = "192.0.2.40", raopPort = 7000, airplayPort = 7000,
            airplayTxt = mapOf("features" to "0x4A7FCA00,0x56BD0", "model" to "AudioAccessory5,1"),
        )
        val idle = ReceiverUi(receiver, enabled = true, status = null, volume = 50, preference = ProtocolPreference.AUTO)
        assertEquals("AirPlay 2", idle.protocolLabel)
        assertFalse(idle.isFallback)
        val forced = idle.copy(preference = ProtocolPreference.AIRPLAY_1)
        assertEquals("AirPlay 1", forced.protocolLabel)
    }
}
