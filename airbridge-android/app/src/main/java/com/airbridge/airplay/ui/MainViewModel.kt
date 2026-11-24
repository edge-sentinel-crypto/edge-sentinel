package com.airbridge.airplay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airbridge.airplay.AirBridgeApp
import com.airbridge.airplay.StreamUiState
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.audio.CaptureHealthMonitor
import com.airbridge.airplay.audio.LevelMeter
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.multiroom.GroupChange
import com.airbridge.airplay.multiroom.MasterVolumeCalculator
import com.airbridge.airplay.multiroom.SpeakerGroup
import com.airbridge.airplay.multiroom.SpeakerGroups
import com.airbridge.airplay.session.ReceiverStatus
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.util.AirLog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row of the speaker list. */
data class ReceiverUi(
    val receiver: AirPlayReceiver,
    val enabled: Boolean,
    val status: ReceiverStatus?,
    val volume: Int,
    val preference: ProtocolPreference,
    /** Sync offset in ms: negative = this speaker plays earlier, positive = later. 0 = none. */
    val syncOffsetMs: Int = 0,
) {
    /** Protocol in use when connected, otherwise what Auto/override would use. */
    val protocol: AirPlayProtocol get() = status?.protocol ?: receiver.protocolFor(preference)
    val state: SessionState? get() = status?.state

    /** True when Auto mode could not use AirPlay 2 for this speaker and it plays over AirPlay 1. */
    val isFallback: Boolean get() = status?.isFallback == true

    /** Badge text: "AirPlay 2", "AirPlay 1", or "AirPlay 1 (fallback)" after an automatic fallback. */
    val protocolLabel: String get() = status?.protocolLabel ?: protocol.label
}

/** One saved group as shown in the multi-room bar. */
data class GroupChipUi(
    val group: SpeakerGroup,
    /** The speakers switched on are exactly this group's available members. */
    val active: Boolean,
    /** Members discovered or added by hand right now. */
    val available: Int,
) {
    val total: Int get() = group.receiverIds.size
    val offline: Int get() = total - available
}

data class MainUiState(
    val stream: StreamUiState = StreamUiState(),
    val level: LevelMeter.Level = LevelMeter.Level(LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB),
    val health: CaptureHealthMonitor.Status = CaptureHealthMonitor.Status.STARTING,
    val receivers: List<ReceiverUi> = emptyList(),
    val groups: List<SpeakerGroup> = emptyList(),
    /** Capture sample rate while streaming: 44,100, or 48,000 (converted to 44,100); 0 when idle. */
    val captureRateHz: Int = 0,
) {
    val activeCount: Int get() = receivers.count { it.state == SessionState.STREAMING }
    val enabledCount: Int get() = receivers.count { it.enabled }

    /** Master level: the loudest switched-on speaker. Derived only, so it never writes back. */
    val masterVolume: Int get() = MasterVolumeCalculator.masterOf(receivers.filter { it.enabled }.map { it.volume })

    /** Every listed speaker is on: the quick toggle offers "All off" (otherwise "All on"). */
    val allOn: Boolean get() = receivers.isNotEmpty() && receivers.all { it.enabled }

    val groupChips: List<GroupChipUi> get() {
        val available = receivers.mapTo(HashSet()) { it.receiver.id }
        val selected = receivers.filter { it.enabled }.mapTo(HashSet()) { it.receiver.id }
        return groups.map { g ->
            GroupChipUi(g, SpeakerGroups.isActive(g, selected, available), SpeakerGroups.selectionFor(g, available).size)
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val controller = (app as AirBridgeApp).controller

    private data class SpeakerSettings(
        val volumes: Map<String, Int>,
        val preferences: Map<String, ProtocolPreference>,
        val offsets: Map<String, Int>,
    )

    private val speakerSettings = combine(controller.volumes, controller.preferences, controller.syncOffsets) { v, p, o ->
        SpeakerSettings(v, p, o)
    }

    private val receiverRows = combine(
        controller.registry.receivers,
        controller.sessions.statuses,
        controller.selected,
        speakerSettings,
    ) { receivers, statuses, selected, _ ->
        receivers.map { r ->
            ReceiverUi(
                receiver = r,
                enabled = r.id in selected,
                status = statuses[r.id],
                volume = controller.volumeOf(r.id),
                preference = controller.preferenceOf(r.id),
                syncOffsetMs = controller.syncOffsetOf(r.id),
            )
        }
    }

    val state: StateFlow<MainUiState> = combine(
        controller.stream,
        controller.level,
        controller.health,
        receiverRows,
        controller.groups,
    ) { stream, level, health, rows, groups -> MainUiState(stream, level, health, rows, groups) }
        .combine(controller.captureRate) { state, rate -> state.copy(captureRateHz = rate) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun setEnabled(r: AirPlayReceiver, on: Boolean) = controller.setReceiverEnabled(r, on)
    fun setVolume(id: String, v: Int) = controller.setVolume(id, v)
    fun setPreference(r: AirPlayReceiver, p: ProtocolPreference) = controller.setPreference(r, p)
    fun providePin(id: String, pin: String) = controller.providePin(id, pin)
    fun providePassword(id: String, pw: String) = controller.providePassword(id, pw)
    fun retry(id: String) = controller.retry(id)
    fun refresh() = controller.refreshDiscovery()
    fun addManual(host: String, port: Int, protocol: AirPlayProtocol?, name: String) =
        controller.addManualReceiver(host, port, protocol, name)
    fun removeManual(r: AirPlayReceiver) = controller.removeManualReceiver(r)
    fun recentLog(): List<String> = AirLog.recentLines()

    // v0.2 multi-room
    fun dragMasterVolume(percent: Int) {
        controller.dragMasterVolume(percent)
    }
    fun endMasterVolumeDrag() = controller.endMasterVolumeDrag()
    fun setAllSpeakers(on: Boolean) = if (on) controller.turnAllOn() else controller.turnAllOff()
    /** False when none of the group's speakers is available right now (nothing changes then). */
    fun applyGroup(id: String): Boolean = controller.applyGroup(id)
    fun saveGroup(name: String): GroupChange = controller.saveGroup(name)
    fun renameGroup(id: String, name: String): GroupChange = controller.renameGroup(id, name)
    fun deleteGroup(id: String) = controller.deleteGroup(id)
    fun setSyncOffset(id: String, ms: Int) {
        controller.setSyncOffset(id, ms)
    }
}
