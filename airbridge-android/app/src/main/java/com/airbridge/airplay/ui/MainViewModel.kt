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
) {
    /** Protocol in use when connected, otherwise what Auto/override would use. */
    val protocol: AirPlayProtocol get() = status?.protocol ?: receiver.protocolFor(preference)
    val state: SessionState? get() = status?.state
}

data class MainUiState(
    val stream: StreamUiState = StreamUiState(),
    val level: LevelMeter.Level = LevelMeter.Level(LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB),
    val health: CaptureHealthMonitor.Status = CaptureHealthMonitor.Status.STARTING,
    val receivers: List<ReceiverUi> = emptyList(),
) {
    val activeCount: Int get() = receivers.count { it.state == SessionState.STREAMING }
    val enabledCount: Int get() = receivers.count { it.enabled }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val controller = (app as AirBridgeApp).controller

    private val receiverRows = combine(
        controller.registry.receivers,
        controller.sessions.statuses,
        controller.selected,
        controller.volumes,
        controller.preferences,
    ) { receivers, statuses, selected, _, _ ->
        receivers.map { r ->
            ReceiverUi(
                receiver = r,
                enabled = r.id in selected,
                status = statuses[r.id],
                volume = controller.volumeOf(r.id),
                preference = controller.preferenceOf(r.id),
            )
        }
    }

    val state: StateFlow<MainUiState> = combine(
        controller.stream,
        controller.level,
        controller.health,
        receiverRows,
    ) { stream, level, health, rows -> MainUiState(stream, level, health, rows) }
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
}
