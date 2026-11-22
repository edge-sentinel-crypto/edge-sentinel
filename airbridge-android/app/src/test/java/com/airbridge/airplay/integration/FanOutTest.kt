package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.mdns.AirPlayReceiver
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

/**
 * The real session manager + default sender factory streaming one captured signal concurrently to
 * an AirPlay 1 and an AirPlay 2 loopback receiver with independent volumes, then tearing down.
 */
class FanOutTest {
    @Test
    fun streamsToAirPlay1AndAirPlay2AtOnce() {
        MockRaopReceiver().start().use { ap1Mock ->
            MockAp2Receiver().start().use { ap2Mock ->
                val ap1 = AirPlayReceiver(
                    id = "den", name = "Den AVR", host = "127.0.0.1", raopPort = ap1Mock.port,
                    raopTxt = mapOf("cn" to "0,1", "et" to "0,4", "am" to "STR-DN1040", "ft" to "0x44F0A00"),
                )
                val ap2 = AirPlayReceiver(
                    id = "kitchen", name = "Kitchen HomePod", host = "127.0.0.1", airplayPort = ap2Mock.port,
                    airplayTxt = mapOf("features" to "0x445F8A00,0x1C340", "flags" to "0x4", "model" to "AudioAccessory5,1"),
                )
                assertEquals(AirPlayProtocol.AIRPLAY_1, ap1.recommendedProtocol)
                assertEquals(AirPlayProtocol.AIRPLAY_2, ap2.recommendedProtocol)

                val manager = AirPlaySessionManager(DefaultSenderFactory(SenderIdentity.random(), InMemoryPairingStore()))
                manager.start()
                manager.connect(ap1, ConnectOptions(volumePercent = 70))
                manager.connect(ap2, ConnectOptions(volumePercent = 30))
                assertTrue(
                    "both speakers streaming: ${manager.statuses.value}",
                    waitUntil(15_000) { manager.statuses.value.values.count { it.state == SessionState.STREAMING } == 2 },
                )
                assertEquals(AirPlayProtocol.AIRPLAY_1, manager.statuses.value["den"]!!.protocol)
                assertEquals(AirPlayProtocol.AIRPLAY_2, manager.statuses.value["kitchen"]!!.protocol)
                assertEquals(2, manager.activeCount)

                val chunks = TestAudio.sineChunks(150)
                chunks.forEach {
                    manager.push(it)
                    Thread.sleep(2)
                }
                val sinkA = ap1Mock.sink!!
                val sinkB = ap2Mock.sink!!
                assertTrue(sinkA.waitForFrames(150))
                assertTrue(sinkB.waitForFrames(150))
                val expected = TestAudio.concat(chunks)
                assertArrayEquals("AirPlay 1 receiver PCM", expected, sinkA.pcmInOrder())
                assertArrayEquals("AirPlay 2 receiver PCM", expected, sinkB.pcmInOrder())
                // One shared timeline: both receivers see identical RTP timestamps for each frame.
                assertEquals(sinkA.frames.values.map { it.timestamp }, sinkB.frames.values.map { it.timestamp })

                // Independent per-speaker volume.
                assertEquals(listOf(-9.0), ap1Mock.volumes)
                assertEquals(listOf(-21.0), ap2Mock.volumes)
                manager.setVolume("den", 100)
                assertTrue(waitUntil { ap1Mock.volumes == listOf(-9.0, 0.0) })
                Thread.sleep(200)
                assertEquals("other speaker untouched", listOf(-21.0), ap2Mock.volumes)

                // Removing one speaker leaves the other streaming.
                manager.disconnect("den")
                assertTrue(waitUntil { ap1Mock.teardown })
                manager.push(TestAudio.sineStereo(352, 150L * 352))
                assertTrue(sinkB.waitForFrames(151))
                assertEquals(1, manager.activeCount)

                manager.stop()
                assertTrue(waitUntil { ap2Mock.teardown })
                assertTrue(sinkA.errors.isEmpty() && sinkB.errors.isEmpty())
                assertTrue("mock errors: ${ap1Mock.errors} ${ap2Mock.errors}", ap1Mock.errors.isEmpty() && ap2Mock.errors.isEmpty())
            }
        }
    }
}
