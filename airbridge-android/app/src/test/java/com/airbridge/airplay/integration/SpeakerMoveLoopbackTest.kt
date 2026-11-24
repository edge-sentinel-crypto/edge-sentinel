package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AirPlayVolume
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.mdns.DiscoveredService
import com.airbridge.airplay.mdns.ReceiverRegistry
import com.airbridge.airplay.mdns.ServiceType
import com.airbridge.airplay.multiroom.InMemorySpeakerStore
import com.airbridge.airplay.multiroom.MultiRoomController
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.DefaultSenderFactory
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.testutil.MockAp2Receiver
import com.airbridge.airplay.testutil.MockRaopReceiver
import com.airbridge.airplay.testutil.TestAudio
import com.airbridge.airplay.util.ByteUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A speaker that comes back at a new address (port numbers stand in for IP addresses on
 * 127.0.0.1) while AirBridge streams: the app's real registry, multi-room controller and session
 * manager keep one speaker with the same id, the saved volume and sync offset, and the AirPlay 2
 * pairing; a session that lost its connection reconnects at the new address at once, and a session
 * that is playing keeps its connection.
 */
class SpeakerMoveLoopbackTest {
    private class App {
        val store = InMemorySpeakerStore()
        val registry = ReceiverRegistry()
        val pairings = InMemoryPairingStore()
        val sessions = AirPlaySessionManager(DefaultSenderFactory(SenderIdentity.random(), pairings))
        val multiRoom = MultiRoomController(store, registry, sessions)

        /** An mDNS (re-)announcement, delivered the way AirBridgeController's collector delivers it. */
        fun announce(service: DiscoveredService) {
            registry.onServiceResolved(service)
            multiRoom.onReceiversChanged(registry.receivers.value)
        }

        fun state(id: String) = sessions.statuses.value[id]?.state
    }

    private fun latencyOf(p: ByteArray): Long = (ByteUtil.getU32BE(p, 16) - ByteUtil.getU32BE(p, 4)) and 0xFFFFFFFFL

    @Test
    fun pairedAirPlay2SpeakerReconnectsAtItsNewAddressWithItsSettingsAndPairing() {
        MockAp2Receiver(statusFlags = MockAp2Receiver.FLAG_PIN, screenPin = "2468").start().use { tv ->
            val app = App()
            val id = "0011223344AA"
            fun tvAt(port: Int) = DiscoveredService(
                ServiceType.AIRPLAY, "Family Room TV", "127.0.0.1", port,
                mapOf("deviceid" to "00:11:22:33:44:AA", "features" to "0x4A7FFFF7,0x3C155FDE", "flags" to "0x8", "model" to "AppleTV6,2"),
                "test",
            )
            app.announce(tvAt(tv.port))
            val receiver = app.registry.receivers.value.single()
            assertEquals(id, receiver.id)
            app.multiRoom.setVolume(id, 70)
            app.multiRoom.setSyncOffset(id, 30)
            app.multiRoom.setReceiverEnabled(receiver, true)
            app.sessions.start()
            app.multiRoom.connectSelected()
            assertTrue(waitUntil { app.state(id) == SessionState.NEEDS_PIN })
            app.sessions.providePin(id, "2468")
            assertTrue(waitUntil(15_000) { app.state(id) == SessionState.STREAMING })
            assertEquals(1, tv.fullSetups)
            assertNotNull("pairing saved under the speaker id", app.pairings.load(id))
            val firstSink = tv.sink!!

            // The TV gets a new address; its connection to the old one is gone. (A retry stands in for
            // the lost connection, so the test does not wait for three failed keep-alives.)
            val newPort = tv.moveToNewPort()
            app.sessions.retry(id)
            assertTrue(
                "failing at the old address",
                waitUntil(10_000) { app.sessions.statuses.value[id]?.message?.contains("retrying in 2s") == true },
            )
            val t0 = System.nanoTime()
            app.announce(tvAt(newPort)) // the TV's mDNS announcement from its new address
            assertTrue(waitUntil(10_000) { app.state(id) == SessionState.STREAMING })
            val reconnectMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("reconnected at once, not after the 2 s backoff ($reconnectMs ms)", reconnectMs < 1_500)

            // One card, same id, new port; the saved settings and the pairing were used.
            val moved = app.registry.receivers.value.single()
            assertEquals(id, moved.id)
            assertEquals(newPort, moved.airplayPort)
            val status = app.sessions.statuses.value.getValue(id)
            assertEquals(AirPlayProtocol.AIRPLAY_2, status.protocol)
            assertEquals(70, status.volumePercent)
            assertEquals(30, status.syncOffsetMs)
            assertEquals("no new PIN pairing", 1, tv.fullSetups)
            assertEquals("no new code on the TV", 1, tv.pinStarts)
            assertEquals("pair-verify with the saved keys after the move", 2, tv.verifies)
            assertEquals(70, app.store.volume(id))
            assertEquals(30, app.store.syncOffsetMs(id))
            val sink = tv.sink!!
            assertNotSame("new stream at the new address", firstSink, sink)
            assertTrue(waitUntil(3_000) { sink.syncPackets.isNotEmpty() })
            assertEquals("sync offset +30 ms applied", 77_175L + 1_323, latencyOf(sink.syncPackets.first()))
            assertTrue(waitUntil { tv.volumes.lastOrNull() == AirPlayVolume.percentToDb(70) })
            val chunks = TestAudio.sineChunks(20)
            chunks.forEach { app.sessions.push(it); Thread.sleep(2) }
            assertTrue(sink.waitForFrames(20))
            app.sessions.stop()
            assertTrue(waitUntil { tv.teardown })
            assertTrue("mock errors: ${tv.errors}", tv.errors.isEmpty())
        }
    }

    @Test
    fun playingSpeakerKeepsItsConnectionAndUsesTheNewAddressNextTime() {
        MockRaopReceiver().start().use { oldAddress ->
            MockRaopReceiver().start().use { newAddress ->
                val app = App()
                val id = "5C5A3C112233"
                fun denAt(port: Int) = DiscoveredService(
                    ServiceType.RAOP, "5C5A3C112233@Den", "127.0.0.1", port,
                    mapOf("am" to "AVR-X3700H", "cn" to "0,1", "et" to "0,4", "ft" to "0x44F0A00"),
                    "test",
                )
                app.announce(denAt(oldAddress.port))
                val receiver = app.registry.receivers.value.single()
                app.multiRoom.setVolume(id, 40)
                app.multiRoom.setSyncOffset(id, -20)
                app.multiRoom.setReceiverEnabled(receiver, true)
                app.sessions.start()
                app.multiRoom.connectSelected()
                assertTrue(waitUntil(15_000) { app.state(id) == SessionState.STREAMING })
                TestAudio.sineChunks(20).forEach { app.sessions.push(it); Thread.sleep(2) }
                assertTrue(oldAddress.sink!!.waitForFrames(20))

                // Re-announced at a new address while playing: one card, and no reconnect.
                app.announce(denAt(newAddress.port))
                Thread.sleep(500)
                assertEquals(1, app.registry.receivers.value.size)
                assertEquals(newAddress.port, app.registry.receivers.value.single().raopPort)
                assertEquals(SessionState.STREAMING, app.state(id))
                assertEquals("still one session at the old address", 1, oldAddress.requests.count { it.startsWith("SETUP ") })
                assertTrue("nothing sent to the new address yet", newAddress.requests.isEmpty())

                // The next connection (here a forced reconnect) goes to the new address with the same settings.
                app.sessions.retry(id)
                assertTrue(waitUntil(15_000) { newAddress.sink != null && app.state(id) == SessionState.STREAMING })
                val sink = newAddress.sink!!
                assertTrue(waitUntil(3_000) { sink.syncPackets.isNotEmpty() })
                assertEquals("sync offset -20 ms applied", 77_175L - 882, latencyOf(sink.syncPackets.first()))
                assertTrue(waitUntil { newAddress.volumes.lastOrNull() == AirPlayVolume.percentToDb(40) })
                TestAudio.sineChunks(20).forEach { app.sessions.push(it); Thread.sleep(2) }
                assertTrue(sink.waitForFrames(20))
                assertEquals(40, app.sessions.statuses.value.getValue(id).volumePercent)
                app.sessions.stop()
                assertTrue(waitUntil { newAddress.teardown })
                assertTrue(oldAddress.errors.isEmpty() && newAddress.errors.isEmpty())
            }
        }
    }
}
