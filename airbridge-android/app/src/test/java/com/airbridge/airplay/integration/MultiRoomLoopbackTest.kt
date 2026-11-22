package com.airbridge.airplay.integration

import com.airbridge.airplay.airplay.AirPlayVolume
import com.airbridge.airplay.airplay.SenderIdentity
import com.airbridge.airplay.airplay.ap2.InMemoryPairingStore
import com.airbridge.airplay.integration.LoopbackSupport.waitUntil
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ReceiverRegistry
import com.airbridge.airplay.multiroom.GroupChange
import com.airbridge.airplay.multiroom.InMemorySpeakerStore
import com.airbridge.airplay.multiroom.MultiRoomController
import com.airbridge.airplay.multiroom.SpeakerGroup
import com.airbridge.airplay.multiroom.SpeakerGroups
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.DefaultSenderFactory
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.testutil.MockAp2Receiver
import com.airbridge.airplay.testutil.MockRaopReceiver
import com.airbridge.airplay.testutil.TestAudio
import com.airbridge.airplay.util.ByteUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/**
 * The multi-room controller the app uses (selection, All on / All off, groups, master volume,
 * sync offset) driving the real session manager and senders against loopback mock speakers:
 * Kitchen (AirPlay 2), Living Room and Den (AirPlay 1), and Garage (AirPlay 1, offline at first).
 */
class MultiRoomLoopbackTest {
    private class Rig : Closeable {
        val kitchenMock = MockAp2Receiver().start()
        val livingMock = MockRaopReceiver().start()
        val denMock = MockRaopReceiver().start()
        val garageMock = MockRaopReceiver().start()
        val kitchen = AirPlayReceiver(
            id = "kitchen", name = "Kitchen", host = "127.0.0.1", airplayPort = kitchenMock.port,
            airplayTxt = mapOf("features" to "0x445F8A00,0x1C340", "flags" to "0x4", "model" to "AudioAccessory5,1"),
        )
        val living = AirPlayReceiver(id = "living", name = "Living Room", host = "127.0.0.1", raopPort = livingMock.port, raopTxt = mapOf("cn" to "0,1", "et" to "0,4"))
        val den = AirPlayReceiver(id = "den", name = "Den", host = "127.0.0.1", raopPort = denMock.port, raopTxt = mapOf("cn" to "0,1", "et" to "0,4"))
        val garage = AirPlayReceiver(id = "garage", name = "Garage", host = "127.0.0.1", raopPort = garageMock.port, raopTxt = mapOf("cn" to "0,1", "et" to "0,4"))
        val registry = ReceiverRegistry().apply { listOf(kitchen, living, den).forEach { addManual(it) } }
        val store = InMemorySpeakerStore()
        val sessions = AirPlaySessionManager(DefaultSenderFactory(SenderIdentity.random(), InMemoryPairingStore()))
        private var ids = 0
        var controller = MultiRoomController(store, registry, sessions) { "group-${++ids}" }

        fun streamingIds(): Set<String> = sessions.statuses.value.filterValues { it.state == SessionState.STREAMING }.keys

        fun waitStreaming(vararg ids: String) =
            assertTrue("streaming ${ids.toList()}: ${sessions.statuses.value}", waitUntil(15_000) { streamingIds() == ids.toSet() })

        fun push(frames: Int) = TestAudio.sineChunks(frames).forEach { sessions.push(it); Thread.sleep(2) }

        fun setups(mock: MockRaopReceiver) = mock.requests.count { it.startsWith("SETUP ") }

        override fun close() {
            sessions.stop()
            listOf(livingMock, denMock, garageMock).forEach { it.close() }
            kitchenMock.close()
        }
    }

    private fun latencyOf(p: ByteArray): Long = (ByteUtil.getU32BE(p, 16) - ByteUtil.getU32BE(p, 4)) and 0xFFFFFFFFL

    @Test
    fun allOnAndAllOffConnectAndDisconnectLiveSpeakers() {
        Rig().use { r ->
            val c = r.controller
            r.sessions.start()
            c.connectSelected()
            assertFalse(c.allOn())

            c.turnAllOn()
            assertTrue(c.allOn())
            assertEquals(setOf("kitchen", "living", "den"), c.selected.value)
            assertEquals("selection persisted", setOf("kitchen", "living", "den"), r.store.selectedReceivers())
            r.waitStreaming("kitchen", "living", "den")
            r.push(30)
            assertTrue(r.kitchenMock.sink!!.waitForFrames(30))
            assertTrue(r.livingMock.sink!!.waitForFrames(30))
            assertTrue(r.denMock.sink!!.waitForFrames(30))

            c.turnAllOff()
            assertEquals(emptySet<String>(), c.selected.value)
            assertEquals(emptySet<String>(), r.store.selectedReceivers())
            assertTrue("every speaker sent TEARDOWN", waitUntil { r.kitchenMock.teardown && r.livingMock.teardown && r.denMock.teardown })
            assertTrue(waitUntil { r.sessions.statuses.value.isEmpty() })
            assertEquals(0, r.sessions.activeCount)
            assertTrue("stream itself keeps running", r.sessions.isStreaming)

            c.turnAllOn()
            r.waitStreaming("kitchen", "living", "den")
            assertEquals("reconnected", 2, r.setups(r.livingMock))
            assertEquals(2, r.setups(r.denMock))
            assertEquals(2, r.kitchenMock.transientSetups)
            assertTrue(r.kitchenMock.errors.isEmpty() && r.livingMock.errors.isEmpty() && r.denMock.errors.isEmpty())
        }
    }

    @Test
    fun applyingAGroupMidStreamSkipsOfflineMembersAndLeavesSharedSpeakersPlaying() {
        Rig().use { r ->
            // Saved on an earlier day, when the garage speaker was on the network.
            r.store.setSpeakerGroups(
                listOf(
                    SpeakerGroup("up", "Kitchen + Living + Garage", linkedSetOf("kitchen", "living", "garage")),
                    SpeakerGroup("away", "Garage only", linkedSetOf("garage")),
                ),
            )
            r.store.setSelectedReceivers(setOf("kitchen", "den"))
            r.controller = MultiRoomController(r.store, r.registry, r.sessions)
            val c = r.controller
            assertEquals(2, c.groups.value.size)
            r.sessions.start()
            c.connectSelected()
            r.waitStreaming("kitchen", "den")
            val kitchenSink = r.kitchenMock.sink!!

            assertTrue(c.applyGroup("up"))
            assertEquals("garage is offline, so it is skipped", setOf("kitchen", "living"), c.selected.value)
            assertTrue(waitUntil { r.denMock.teardown })
            r.waitStreaming("kitchen", "living")
            assertSame("kitchen kept its session (no reconnect)", kitchenSink, r.kitchenMock.sink)
            assertEquals(1, r.kitchenMock.transientSetups)
            val group = c.groups.value.first { it.id == "up" }
            assertTrue(SpeakerGroups.isActive(group, c.selected.value, c.availableIds()))

            r.push(25)
            assertTrue(r.livingMock.sink!!.waitForFrames(25))
            assertTrue(kitchenSink.waitForFrames(25))

            // No member available: nothing changes and nothing crashes.
            assertFalse(c.applyGroup("away"))
            assertFalse(c.applyGroup("no-such-group"))
            assertEquals(setOf("kitchen", "living"), c.selected.value)

            // The garage speaker comes back: it is not switched on, so it does not join by itself...
            r.registry.addManual(r.garage)
            c.onReceiversChanged(r.registry.snapshot())
            Thread.sleep(300)
            assertEquals(0, r.setups(r.garageMock))
            assertFalse("group no longer matches: garage is available but off", SpeakerGroups.isActive(group, c.selected.value, c.availableIds()))
            // ...and tapping the group again brings it in.
            assertTrue(c.applyGroup("up"))
            r.waitStreaming("kitchen", "living", "garage")
            assertTrue(SpeakerGroups.isActive(group, c.selected.value, c.availableIds()))
            assertSame(kitchenSink, r.kitchenMock.sink)

            // A switched-on speaker that is offline does not connect; when it appears while
            // streaming, it joins automatically.
            r.registry.removeManual("den")
            c.setSelection(c.selected.value + "den")
            assertTrue("den is selected", "den" in c.selected.value)
            Thread.sleep(300)
            assertEquals(setOf("kitchen", "living", "garage"), r.streamingIds())
            r.registry.addManual(r.den)
            c.onReceiversChanged(r.registry.snapshot())
            r.waitStreaming("kitchen", "living", "garage", "den")
        }
    }

    @Test
    fun groupsAreSavedRenamedDeletedAndPersisted() {
        Rig().use { r ->
            val c = r.controller
            assertTrue("nothing on yet", c.saveGroup("Empty") is GroupChange.Rejected)
            c.setReceiverEnabled(r.kitchen, true)
            c.setReceiverEnabled(r.living, true)
            val saved = c.saveGroup("  Downstairs \"main\" | ☕ ") as GroupChange.Saved
            assertEquals("Downstairs \"main\" | ☕", saved.group.name)
            assertEquals(setOf("kitchen", "living"), saved.group.receiverIds)
            assertTrue(c.saveGroup("   ") is GroupChange.Rejected)

            c.setReceiverEnabled(r.living, false)
            c.setReceiverEnabled(r.den, true)
            val second = c.saveGroup("Kitchen & Den") as GroupChange.Saved
            assertEquals(listOf("group-1", "group-2"), c.groups.value.map { it.id })
            assertTrue(c.renameGroup(second.group.id, "downstairs \"MAIN\" | ☕") is GroupChange.Rejected)
            assertTrue(c.renameGroup(second.group.id, "Evening") is GroupChange.Saved)

            // A fresh controller (app restart) reads the same groups back from storage.
            val reloaded = MultiRoomController(r.store, r.registry, r.sessions)
            assertEquals(c.groups.value, reloaded.groups.value)
            assertEquals(listOf("Downstairs \"main\" | ☕", "Evening"), reloaded.groups.value.map { it.name })

            c.deleteGroup("group-1")
            assertEquals(listOf("Evening"), r.store.speakerGroups().map { it.name })
        }
    }

    @Test
    fun masterVolumeScalesLiveSpeakersKeepsTheBalanceAndSavesOnRelease() {
        Rig().use { r ->
            val c = r.controller
            c.setVolume("kitchen", 60)
            c.setVolume("living", 30)
            c.setVolume("den", 10)
            c.setReceiverEnabled(r.kitchen, true)
            c.setReceiverEnabled(r.living, true)
            r.sessions.start()
            c.connectSelected()
            r.waitStreaming("kitchen", "living")
            assertEquals(listOf(-12.0), r.kitchenMock.volumes)
            assertEquals(listOf(-21.0), r.livingMock.volumes)
            assertEquals(60, c.masterVolume())

            val drag = c.beginMasterDrag()
            assertEquals(mapOf("kitchen" to 60, "living" to 30), drag.snapshot)
            assertEquals(mapOf("kitchen" to 30, "living" to 15), c.dragMasterTo(30))
            assertTrue(waitUntil { r.kitchenMock.volumes.last() == -21.0 && r.livingMock.volumes.last() == AirPlayVolume.percentToDb(15) })
            c.dragMasterTo(0)
            assertTrue("0 % mutes", waitUntil { r.kitchenMock.volumes.last() == -144.0 && r.livingMock.volumes.last() == -144.0 })
            for (m in 1..100) c.dragMasterTo(m) // a fast drag: commands are coalesced, the last value wins
            assertTrue(waitUntil { r.kitchenMock.volumes.last() == 0.0 && r.livingMock.volumes.last() == AirPlayVolume.percentToDb(50) })
            assertEquals(mapOf("kitchen" to 60, "living" to 30), c.dragMasterTo(60))
            assertTrue("back to the exact start", waitUntil { r.kitchenMock.volumes.last() == -12.0 && r.livingMock.volumes.last() == -21.0 })
            assertEquals("not saved before release", 10, r.store.volume("den"))
            c.endMasterDrag()
            assertEquals(60, r.store.volume("kitchen"))
            assertEquals(30, r.store.volume("living"))
            assertEquals("a speaker that is off is not touched", 10, c.volumeOf("den"))

            // One speaker's own slider: only that speaker changes, the master display follows.
            val kitchenCommands = r.kitchenMock.volumes.size
            c.setVolume("living", 90)
            assertTrue(waitUntil { r.livingMock.volumes.last() == -3.0 })
            Thread.sleep(300)
            assertEquals("kitchen untouched", kitchenCommands, r.kitchenMock.volumes.size)
            assertEquals(90, c.masterVolume())
            assertEquals(60, c.volumeOf("kitchen"))

            // All speakers at 0: the master raises them evenly.
            c.setVolume("kitchen", 0)
            c.setVolume("living", 0)
            assertEquals(0, c.masterVolume())
            c.beginMasterDrag()
            assertEquals(mapOf("kitchen" to 40, "living" to 40), c.dragMasterTo(40))
            c.endMasterDrag()
            assertTrue(waitUntil { r.kitchenMock.volumes.last() == -18.0 && r.livingMock.volumes.last() == -18.0 })
            assertEquals(40, r.store.volume("living"))
        }
    }

    @Test
    fun syncOffsetIsAppliedLiveSavedAndUsedAgainOnReconnect() {
        Rig().use { r ->
            val c = r.controller
            c.setReceiverEnabled(r.kitchen, true)
            c.setReceiverEnabled(r.living, true)
            r.sessions.start()
            c.connectSelected()
            r.waitStreaming("kitchen", "living")
            val kitchenSink = r.kitchenMock.sink!!
            val livingSink = r.livingMock.sink!!
            assertTrue(waitUntil(3_000) { kitchenSink.syncPackets.isNotEmpty() && livingSink.syncPackets.isNotEmpty() })
            assertEquals(77_175L, latencyOf(kitchenSink.syncPackets.first()))

            assertEquals("snapped to 5 ms", 40, c.setSyncOffset("kitchen", 42))
            assertEquals(40, r.store.syncOffsetMs("kitchen"))
            assertEquals(40, c.syncOffsetOf("kitchen"))
            assertTrue(waitUntil(2_000) { kitchenSink.syncPackets.any { latencyOf(it) == 77_175L + 1_764 } })
            assertSame("no reconnect", kitchenSink, r.kitchenMock.sink)
            assertEquals(1, r.kitchenMock.transientSetups)
            assertTrue("other speaker untouched", livingSink.syncPackets.all { latencyOf(it) == 77_175L })
            assertEquals(-250, c.setSyncOffset("living", -300))
            assertTrue(waitUntil(2_000) { livingSink.syncPackets.any { latencyOf(it) == 66_150L } })

            // Off and on again: the new connection starts with the saved offset.
            c.setReceiverEnabled(r.kitchen, false)
            assertTrue(waitUntil { r.kitchenMock.teardown })
            c.setReceiverEnabled(r.kitchen, true)
            r.waitStreaming("kitchen", "living")
            val newSink = r.kitchenMock.sink!!
            assertTrue(newSink !== kitchenSink)
            assertTrue(waitUntil(3_000) { newSink.syncPackets.isNotEmpty() })
            assertEquals(77_175L + 1_764, latencyOf(newSink.syncPackets.first()))
            assertEquals(0x90, newSink.syncPackets.first()[0].toInt() and 0xFF)

            // Reset to 0 removes the stored value.
            assertEquals(0, c.setSyncOffset("kitchen", 0))
            assertEquals(0, r.store.syncOffsetMs("kitchen"))
            assertTrue(waitUntil(2_000) { newSink.syncPackets.last().let { latencyOf(it) == 77_175L } })
            val chunks = TestAudio.sineChunks(20)
            chunks.forEach { r.sessions.push(it); Thread.sleep(2) }
            assertTrue(newSink.waitForFrames(20))
            assertArrayEquals(TestAudio.concat(chunks), newSink.pcmInOrder())
        }
    }
}
