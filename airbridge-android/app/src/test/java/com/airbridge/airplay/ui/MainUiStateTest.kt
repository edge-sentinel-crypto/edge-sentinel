package com.airbridge.airplay.ui

import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.multiroom.SpeakerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the multi-room bar and the master slider show, derived from the screen state only. */
class MainUiStateTest {
    private fun speaker(id: String, on: Boolean, volume: Int, offset: Int = 0) = ReceiverUi(
        receiver = AirPlayReceiver(id, id.replaceFirstChar { it.uppercase() }, "192.0.2.10", raopPort = 7000),
        enabled = on,
        status = null,
        volume = volume,
        preference = ProtocolPreference.AUTO,
        syncOffsetMs = offset,
    )

    @Test
    fun masterShowsTheLoudestSpeakerThatIsOn() {
        val state = MainUiState(receivers = listOf(speaker("kitchen", true, 65), speaker("living", true, 40), speaker("den", false, 90)))
        assertEquals("the louder speaker that is off is ignored", 65, state.masterVolume)
        assertEquals(2, state.enabledCount)
        // A speaker's own slider changes only the derived master value; nothing is written back.
        val moved = state.copy(receivers = listOf(speaker("kitchen", true, 65), speaker("living", true, 80), speaker("den", false, 90)))
        assertEquals(80, moved.masterVolume)
        assertEquals(65, moved.receivers[0].volume)
        assertEquals(0, MainUiState().masterVolume)
    }

    @Test
    fun quickToggleOffersAllOffOnlyWhenEverySpeakerIsOn() {
        assertFalse(MainUiState().allOn)
        assertFalse(MainUiState(receivers = listOf(speaker("a", true, 50), speaker("b", false, 50))).allOn)
        assertTrue(MainUiState(receivers = listOf(speaker("a", true, 50), speaker("b", true, 50))).allOn)
    }

    @Test
    fun groupChipsShowMatchAndOfflineMembers() {
        val groups = listOf(
            SpeakerGroup("g1", "Downstairs", linkedSetOf("kitchen", "living")),
            SpeakerGroup("g2", "Everywhere", linkedSetOf("kitchen", "living", "den", "garage")),
            SpeakerGroup("g3", "Garage", linkedSetOf("garage")),
        )
        val state = MainUiState(
            receivers = listOf(speaker("kitchen", true, 65), speaker("living", true, 40, offset = 40), speaker("den", false, 30)),
            groups = groups,
        )
        val chips = state.groupChips
        assertEquals(listOf(true, false, false), chips.map { it.active })
        assertEquals(listOf(2, 3, 0), chips.map { it.available })
        assertEquals(listOf(0, 1, 1), chips.map { it.offline })
        assertEquals(40, state.receivers[1].syncOffsetMs)
    }
}
