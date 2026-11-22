package com.airbridge.airplay.multiroom

import com.airbridge.airplay.airplay.SyncOffset
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.mdns.ReceiverRegistry
import com.airbridge.airplay.session.AirPlaySessionManager
import com.airbridge.airplay.session.ConnectOptions
import com.airbridge.airplay.util.AirLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Which speakers play and how: on/off, volume (per speaker and master), protocol choice, sync
 * offset and saved groups. Pure JVM code (no Android imports), so the loopback tests drive the
 * same object the app uses. While a stream is running, every change is applied to the live
 * sessions at once: speakers that are switched on connect, speakers that are switched off
 * disconnect, and volume and sync-offset changes reach the playing speakers without a reconnect.
 */
class MultiRoomController(
    private val store: SpeakerStore,
    private val registry: ReceiverRegistry,
    private val sessions: AirPlaySessionManager,
    private val newGroupId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Serializes selection changes so a group, All on/off and auto-join never interleave. */
    private val lock = Any()

    private val _selected = MutableStateFlow(store.selectedReceivers())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _volumes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val volumes: StateFlow<Map<String, Int>> = _volumes.asStateFlow()

    private val _preferences = MutableStateFlow<Map<String, ProtocolPreference>>(emptyMap())
    val preferences: StateFlow<Map<String, ProtocolPreference>> = _preferences.asStateFlow()

    private val _syncOffsets = MutableStateFlow<Map<String, Int>>(emptyMap())
    val syncOffsets: StateFlow<Map<String, Int>> = _syncOffsets.asStateFlow()

    private val _groups = MutableStateFlow(SpeakerGroups.normalize(store.speakerGroups()))
    val groups: StateFlow<List<SpeakerGroup>> = _groups.asStateFlow()

    /** Passwords typed this session (kept in memory only, never written to disk). */
    private val sessionPasswords = ConcurrentHashMap<String, String>()

    private var masterDrag: MasterVolumeDrag? = null

    fun volumeOf(id: String): Int = _volumes.value[id] ?: store.volume(id)
    fun preferenceOf(id: String): ProtocolPreference = _preferences.value[id] ?: store.preference(id)
    fun syncOffsetOf(id: String): Int = _syncOffsets.value[id] ?: store.syncOffsetMs(id)

    /** Everything a new connection to [receiver] needs: volume, protocol, password and sync offset. */
    fun optionsFor(receiver: AirPlayReceiver): ConnectOptions = ConnectOptions(
        volumePercent = volumeOf(receiver.id),
        preference = preferenceOf(receiver.id),
        password = sessionPasswords[receiver.id],
        syncOffsetMs = syncOffsetOf(receiver.id),
    )

    /** Ids of the speakers that are discovered or added by hand right now. */
    fun availableIds(): Set<String> = registry.snapshot().mapTo(LinkedHashSet()) { it.id }

    /** Available speakers that are switched on (what the UI shows as enabled). */
    fun enabledReceivers(): List<AirPlayReceiver> {
        val on = _selected.value
        return registry.snapshot().filter { it.id in on }
    }

    // ------------------------------------------------------------------ speakers on and off

    fun setReceiverEnabled(receiver: AirPlayReceiver, enabled: Boolean) = synchronized(lock) {
        _selected.update { if (enabled) it + receiver.id else it - receiver.id }
        store.setSelectedReceivers(_selected.value)
        if (!sessions.isStreaming) return@synchronized
        if (enabled) connect(receiver) else sessions.disconnect(receiver.id)
    }

    /**
     * Replaces the set of switched-on speakers in one step. While streaming, speakers that left the
     * set disconnect and available speakers that joined it connect; speakers in both keep playing
     * untouched (no reconnect).
     */
    fun setSelection(target: Set<String>) = synchronized(lock) {
        val before = _selected.value
        val after = target.toSet()
        if (after == before) return@synchronized
        _selected.value = after
        store.setSelectedReceivers(after)
        if (!sessions.isStreaming) return@synchronized
        for (id in before - after) sessions.disconnect(id)
        val available = registry.snapshot().associateBy { it.id }
        for (id in after - before) available[id]?.let { connect(it) }
    }

    /** True when every available speaker is switched on (the quick toggle then offers "All off"). */
    fun allOn(): Boolean {
        val available = availableIds()
        return available.isNotEmpty() && _selected.value.containsAll(available)
    }

    /** "All on": switches on every discovered and manual speaker. */
    fun turnAllOn() = synchronized(lock) { setSelection(_selected.value + availableIds()) }

    /** "All off": switches off every speaker. */
    fun turnAllOff() = setSelection(emptySet())

    // ------------------------------------------------------------------ groups

    /**
     * Plays exactly the group's speakers that are available now; members that are offline are
     * skipped. Returns false (and changes nothing) when the group is unknown or none of its
     * members is available.
     */
    fun applyGroup(groupId: String): Boolean = synchronized(lock) {
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return@synchronized false
        val members = SpeakerGroups.selectionFor(group, availableIds())
        if (members.isEmpty()) return@synchronized false
        setSelection(members)
        true
    }

    /** Saves the speakers that are switched on now as group [name] (same name: replaces its speakers). */
    fun saveGroup(name: String): GroupChange = synchronized(lock) {
        val members = enabledReceivers().mapTo(LinkedHashSet()) { it.id }
        val change = SpeakerGroups.save(_groups.value, name, members, newGroupId)
        if (change is GroupChange.Saved) storeGroups(change.groups)
        change
    }

    fun renameGroup(groupId: String, name: String): GroupChange = synchronized(lock) {
        val change = SpeakerGroups.rename(_groups.value, groupId, name)
        if (change is GroupChange.Saved) storeGroups(change.groups)
        change
    }

    fun deleteGroup(groupId: String) = synchronized(lock) {
        storeGroups(SpeakerGroups.delete(_groups.value, groupId))
    }

    private fun storeGroups(groups: List<SpeakerGroup>) {
        _groups.value = groups
        store.setSpeakerGroups(groups)
    }

    // ------------------------------------------------------------------ volume

    fun setVolume(id: String, percent: Int) {
        val v = MasterVolumeCalculator.clamp(percent)
        _volumes.update { it + (id to v) }
        store.setVolume(id, v)
        sessions.setVolume(id, v)
    }

    /** Master level for the speakers that are switched on: the loudest one (0 when none is on). */
    fun masterVolume(): Int = MasterVolumeCalculator.masterOf(enabledReceivers().map { volumeOf(it.id) })

    /** Starts a master-slider drag: snapshots the volumes of the speakers that are switched on. */
    fun beginMasterDrag(): MasterVolumeDrag = synchronized(lock) {
        MasterVolumeDrag(enabledReceivers().associate { it.id to volumeOf(it.id) }).also { masterDrag = it }
    }

    /** Moves the master slider: scales every speaker of the drag snapshot and updates live speakers. */
    fun dragMasterTo(master: Int): Map<String, Int> = synchronized(lock) {
        val drag = masterDrag ?: beginMasterDrag()
        drag.volumesFor(master).also { applyVolumes(it) }
    }

    /** Ends the drag and saves the speakers' final volumes. */
    fun endMasterDrag() = synchronized(lock) {
        val drag = masterDrag ?: return@synchronized
        masterDrag = null
        store.setVolumes(drag.snapshot.keys.associateWith { volumeOf(it) })
    }

    /** Applies new volumes; only speakers whose value changed get a live volume command. */
    private fun applyVolumes(target: Map<String, Int>) {
        val changed = target.filter { (id, v) -> volumeOf(id) != v }
        if (changed.isEmpty()) return
        _volumes.update { it + changed }
        changed.forEach { (id, v) -> sessions.setVolume(id, v) }
    }

    // ------------------------------------------------------------------ protocol, sync offset

    fun setPreference(receiver: AirPlayReceiver, pref: ProtocolPreference) {
        _preferences.update { it + (receiver.id to pref) }
        store.setPreference(receiver.id, pref)
        if (sessions.isConnected(receiver.id)) connect(receiver)
    }

    /**
     * Sets one speaker's sync offset (clamped to -250..+250 ms, 5 ms steps; negative = earlier,
     * positive = later), saves it and applies it to the live session without reconnecting.
     * Returns the value actually used.
     */
    fun setSyncOffset(id: String, ms: Int): Int {
        val v = SyncOffset.snap(ms)
        _syncOffsets.update { it + (id to v) }
        store.setSyncOffsetMs(id, v)
        sessions.setSyncOffsetMs(id, v)
        return v
    }

    // ------------------------------------------------------------------ sessions

    fun providePassword(id: String, password: String) {
        sessionPasswords[id] = password
        sessions.providePassword(id, password)
    }

    /** Capture started: connect every switched-on speaker that is available. */
    fun connectSelected() = synchronized(lock) {
        val on = _selected.value
        registry.snapshot().filter { it.id in on }.forEach { connect(it) }
    }

    /** Speakers that are switched on and appear (or re-appear) while streaming join automatically. */
    fun onReceiversChanged(list: List<AirPlayReceiver>) = synchronized(lock) {
        if (!sessions.isStreaming) return@synchronized
        val on = _selected.value
        list.filter { it.id in on && !sessions.isConnected(it.id) }.forEach { connect(it) }
    }

    private fun connect(receiver: AirPlayReceiver) {
        try {
            sessions.connect(receiver, optionsFor(receiver))
        } catch (e: IllegalStateException) {
            AirLog.w(TAG, "connect ignored: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MultiRoom"
    }
}
