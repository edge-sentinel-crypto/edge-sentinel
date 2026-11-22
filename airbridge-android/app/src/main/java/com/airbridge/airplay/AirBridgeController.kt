package com.airbridge.airplay

import android.app.Application
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.audio.CaptureHealthMonitor
import com.airbridge.airplay.audio.LevelMeter
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.DiscoveryManager
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.mdns.ReceiverRegistry
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.ConnectOptions
import com.airbridge.airplay.session.DefaultSenderFactory
import com.airbridge.airplay.util.AirLog
import com.airbridge.airplay.util.NtpClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Streaming lifecycle shown in the hero card. */
enum class StreamPhase { IDLE, STARTING, STREAMING, STOPPING }

data class StreamUiState(val phase: StreamPhase = StreamPhase.IDLE, val message: String? = null)

/**
 * Process-wide state holder shared by the activity (UI) and the capture service. Keeps the
 * discovered receivers, which speakers are switched on, their volumes and protocol choices, and
 * drives the [AirPlaySessionManager] when capture starts and stops.
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

    private val _selected = MutableStateFlow(settings.selectedReceivers())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _volumes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val volumes: StateFlow<Map<String, Int>> = _volumes.asStateFlow()

    private val _preferences = MutableStateFlow<Map<String, ProtocolPreference>>(emptyMap())
    val preferences: StateFlow<Map<String, ProtocolPreference>> = _preferences.asStateFlow()

    /** Passwords typed this session (kept in memory only, never written to disk). */
    private val sessionPasswords = HashMap<String, String>()

    init {
        settings.manualReceivers().forEach { registry.addManual(it) }
        // Speakers that are switched on but appear (or re-appear) while streaming join automatically.
        scope.launch {
            registry.receivers.collect { list ->
                if (!sessions.isStreaming) return@collect
                val on = _selected.value
                list.filter { it.id in on && !sessions.isConnected(it.id) }.forEach { connect(it) }
            }
        }
    }

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()
    fun refreshDiscovery() = discovery.restart()

    fun volumeOf(id: String): Int = _volumes.value[id] ?: settings.volume(id)
    fun preferenceOf(id: String): ProtocolPreference = _preferences.value[id] ?: settings.preference(id)

    fun setReceiverEnabled(receiver: AirPlayReceiver, enabled: Boolean) {
        _selected.update { if (enabled) it + receiver.id else it - receiver.id }
        settings.setSelectedReceivers(_selected.value)
        if (!sessions.isStreaming) return
        if (enabled) connect(receiver) else sessions.disconnect(receiver.id)
    }

    fun setVolume(id: String, percent: Int) {
        _volumes.update { it + (id to percent) }
        settings.setVolume(id, percent)
        sessions.setVolume(id, percent)
    }

    fun setPreference(receiver: AirPlayReceiver, pref: ProtocolPreference) {
        _preferences.update { it + (receiver.id to pref) }
        settings.setPreference(receiver.id, pref)
        if (sessions.isConnected(receiver.id)) connect(receiver)
    }

    fun providePin(id: String, pin: String) = sessions.providePin(id, pin)

    fun providePassword(id: String, password: String) {
        sessionPasswords[id] = password
        sessions.providePassword(id, password)
    }

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

    private fun connect(receiver: AirPlayReceiver) {
        try {
            sessions.connect(
                receiver,
                ConnectOptions(
                    volumePercent = volumeOf(receiver.id),
                    preference = preferenceOf(receiver.id),
                    password = sessionPasswords[receiver.id],
                ),
            )
        } catch (e: IllegalStateException) {
            AirLog.w(TAG, "connect ignored: ${e.message}")
        }
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
        val on = _selected.value
        registry.snapshot().filter { it.id in on }.forEach { connect(it) }
    }

    fun onCaptureFailed(message: String) {
        AirLog.e(TAG, message)
        sessions.stop()
        _stream.value = StreamUiState(StreamPhase.IDLE, message)
    }

    /** Called off the main thread by the service; performs TEARDOWN on every speaker. */
    fun onCaptureStopped(reason: String?) {
        _stream.value = StreamUiState(StreamPhase.STOPPING)
        sessions.stop()
        _level.value = LevelMeter.Level(LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB)
        _stream.value = StreamUiState(StreamPhase.IDLE, reason)
    }

    fun onLevel(level: LevelMeter.Level) {
        _level.value = level
    }

    fun onCaptureHealth(status: CaptureHealthMonitor.Status) {
        _health.value = status
        if (status == CaptureHealthMonitor.Status.BLOCKED) {
            AirLog.w(TAG, "music is playing but captured audio is silent: the playing app blocks capture")
        }
    }

    companion object {
        private const val TAG = "Controller"
    }
}
