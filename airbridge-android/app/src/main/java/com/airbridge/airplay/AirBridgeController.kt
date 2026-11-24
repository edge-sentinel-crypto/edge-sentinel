package com.airbridge.airplay

import android.app.Application
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.audio.CaptureHealthMonitor
import com.airbridge.airplay.audio.LevelMeter
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.DiscoveryManager
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.mdns.ReceiverRegistry
import com.airbridge.airplay.multiroom.GroupChange
import com.airbridge.airplay.multiroom.MultiRoomController
import com.airbridge.airplay.multiroom.SpeakerGroup
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.DefaultSenderFactory
import com.airbridge.airplay.util.AirLog
import com.airbridge.airplay.util.NtpClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Streaming lifecycle shown in the hero card. */
enum class StreamPhase { IDLE, STARTING, STREAMING, STOPPING }

data class StreamUiState(val phase: StreamPhase = StreamPhase.IDLE, val message: String? = null)

/**
 * Process-wide state holder shared by the activity (UI) and the capture service. Keeps the
 * discovered receivers and the streaming state, and drives the [AirPlaySessionManager] when
 * capture starts and stops. Which speakers play, their volumes (per speaker and master), protocol
 * choices, sync offsets and saved groups live in [multiRoom].
 */
class AirBridgeController(app: Application) {
    val settings = AppSettings(app)
    val clock = NtpClock()
    val registry = ReceiverRegistry()
    val sessions = AirPlaySessionManager(DefaultSenderFactory(settings.senderIdentity(), settings.pairingStore()), clock)
    private val discovery = DiscoveryManager(app, registry)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _stream = MutableStateFlow(StreamUiState())
    val stream: StateFlow<StreamUiState> = _stream.asStateFlow()

    private val _level = MutableStateFlow(LevelMeter.Level(LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB))
    val level: StateFlow<LevelMeter.Level> = _level.asStateFlow()

    private val _health = MutableStateFlow(CaptureHealthMonitor.Status.STARTING)
    val health: StateFlow<CaptureHealthMonitor.Status> = _health.asStateFlow()

    /** Capture sample rate while streaming: 44,100, or 48,000 converted to 44,100; 0 when idle. */
    private val _captureRate = MutableStateFlow(0)
    val captureRate: StateFlow<Int> = _captureRate.asStateFlow()

    /** Speaker selection, volumes, master volume, sync offsets and groups (pure, unit-tested). */
    val multiRoom = MultiRoomController(settings, registry, sessions)

    val selected: StateFlow<Set<String>> get() = multiRoom.selected
    val volumes: StateFlow<Map<String, Int>> get() = multiRoom.volumes
    val preferences: StateFlow<Map<String, ProtocolPreference>> get() = multiRoom.preferences
    val syncOffsets: StateFlow<Map<String, Int>> get() = multiRoom.syncOffsets
    val groups: StateFlow<List<SpeakerGroup>> get() = multiRoom.groups

    init {
        settings.manualReceivers().forEach { registry.addManual(it) }
        // Speakers that are switched on but appear (or re-appear) while streaming join automatically.
        scope.launch {
            registry.receivers.collect { list -> multiRoom.onReceiversChanged(list) }
        }
    }

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()
    fun refreshDiscovery() = discovery.restart()

    fun volumeOf(id: String): Int = multiRoom.volumeOf(id)
    fun preferenceOf(id: String): ProtocolPreference = multiRoom.preferenceOf(id)
    fun syncOffsetOf(id: String): Int = multiRoom.syncOffsetOf(id)

    fun setReceiverEnabled(receiver: AirPlayReceiver, enabled: Boolean) = multiRoom.setReceiverEnabled(receiver, enabled)

    fun setVolume(id: String, percent: Int) = multiRoom.setVolume(id, percent)

    fun setPreference(receiver: AirPlayReceiver, pref: ProtocolPreference) = multiRoom.setPreference(receiver, pref)

    /** Live per-speaker sync offset (ms; negative = earlier, positive = later); no reconnect. */
    fun setSyncOffset(id: String, ms: Int): Int = multiRoom.setSyncOffset(id, ms)

    fun dragMasterVolume(percent: Int) = multiRoom.dragMasterTo(percent)
    fun endMasterVolumeDrag() = multiRoom.endMasterDrag()

    fun turnAllOn() = multiRoom.turnAllOn()
    fun turnAllOff() = multiRoom.turnAllOff()
    fun applyGroup(groupId: String): Boolean = multiRoom.applyGroup(groupId)
    fun saveGroup(name: String): GroupChange = multiRoom.saveGroup(name)
    fun renameGroup(groupId: String, name: String): GroupChange = multiRoom.renameGroup(groupId, name)
    fun deleteGroup(groupId: String) = multiRoom.deleteGroup(groupId)

    fun providePin(id: String, pin: String) = sessions.providePin(id, pin)

    fun providePassword(id: String, password: String) = multiRoom.providePassword(id, password)

    fun retry(id: String) = sessions.retry(id)

    fun addManualReceiver(host: String, port: Int, protocol: AirPlayProtocol?, name: String): AirPlayReceiver {
        val receiver = AirPlayReceiver.manual(host, port, protocol, name)
        settings.addManualReceiver(host, port, protocol, receiver.name)
        registry.addManual(receiver)
        return receiver
    }

    fun removeManualReceiver(receiver: AirPlayReceiver) {
        setReceiverEnabled(receiver, false)
        settings.removeManualReceiver(receiver.host, receiver.raopPort ?: receiver.airplayPort ?: 0)
        registry.removeManual(receiver.id)
    }

    // ------------------------------------------------------------ capture service callbacks

    fun onStartRequested() {
        _stream.value = StreamUiState(StreamPhase.STARTING)
    }

    fun onStartCancelled(message: String?) {
        _stream.value = StreamUiState(StreamPhase.IDLE, message)
    }

    fun onCaptureStarting() {
        _health.value = CaptureHealthMonitor.Status.STARTING
        sessions.start()
    }

    fun onCaptureStarted() {
        _stream.value = StreamUiState(StreamPhase.STREAMING)
        multiRoom.connectSelected()
    }

    fun onCaptureFailed(message: String) {
        AirLog.e(TAG, message)
        sessions.stop()
        _captureRate.value = 0
        _stream.value = StreamUiState(StreamPhase.IDLE, message)
    }

    /** Called off the main thread by the service; performs TEARDOWN on every speaker. */
    fun onCaptureStopped(reason: String?) {
        _stream.value = StreamUiState(StreamPhase.STOPPING)
        sessions.stop()
        _level.value = LevelMeter.Level(LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB)
        _captureRate.value = 0
        _stream.value = StreamUiState(StreamPhase.IDLE, reason)
    }

    fun onCaptureFormat(sampleRate: Int) {
        _captureRate.value = sampleRate
    }

    fun onLevel(level: LevelMeter.Level) {
        _level.value = level
    }

    fun onCaptureHealth(status: CaptureHealthMonitor.Status) {
        _health.value = status
        if (status == CaptureHealthMonitor.Status.BLOCKED) {
            AirLog.w(TAG, "music is playing but captured audio is silent: the playing app (or this version of it) seems to opt out of playback capture")
        }
    }

    companion object {
        private const val TAG = "Controller"
    }
}
