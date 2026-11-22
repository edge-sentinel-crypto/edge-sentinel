package com.airbridge.airplay.multiroom

import com.airbridge.airplay.airplay.SyncOffset
import com.airbridge.airplay.mdns.ProtocolPreference

/**
 * Per-speaker choices that survive an app restart. On the phone this is [com.airbridge.airplay.AppSettings]
 * (private SharedPreferences); tests use [InMemorySpeakerStore].
 */
interface SpeakerStore {
    fun selectedReceivers(): Set<String>
    fun setSelectedReceivers(ids: Set<String>)

    fun volume(id: String): Int
    fun setVolume(id: String, percent: Int)

    /** Writes several volumes at once (one master-volume change). */
    fun setVolumes(volumes: Map<String, Int>) = volumes.forEach { (id, v) -> setVolume(id, v) }

    fun preference(id: String): ProtocolPreference
    fun setPreference(id: String, pref: ProtocolPreference)

    /** Sync offset in ms, clamped to -250..+250 and snapped to 5 ms steps; 0 when never set. */
    fun syncOffsetMs(id: String): Int
    fun setSyncOffsetMs(id: String, ms: Int)

    fun speakerGroups(): List<SpeakerGroup>
    fun setSpeakerGroups(groups: List<SpeakerGroup>)

    companion object {
        const val DEFAULT_VOLUME = 50
    }
}

/** Memory-only [SpeakerStore] (unit tests, previews). Groups go through the real storage codec. */
class InMemorySpeakerStore : SpeakerStore {
    private var selected: Set<String> = emptySet()
    private val volumes = HashMap<String, Int>()
    private val preferences = HashMap<String, ProtocolPreference>()
    private val offsets = HashMap<String, Int>()
    private var groupsEncoded: String? = null

    /** Number of writes, so tests can check what was persisted. */
    @Volatile var writes = 0
        private set

    @Synchronized override fun selectedReceivers() = selected

    @Synchronized override fun setSelectedReceivers(ids: Set<String>) {
        selected = ids.toSet()
        writes++
    }

    @Synchronized override fun volume(id: String) = volumes[id] ?: SpeakerStore.DEFAULT_VOLUME

    @Synchronized override fun setVolume(id: String, percent: Int) {
        volumes[id] = percent.coerceIn(0, 100)
        writes++
    }

    @Synchronized override fun preference(id: String) = preferences[id] ?: ProtocolPreference.AUTO

    @Synchronized override fun setPreference(id: String, pref: ProtocolPreference) {
        preferences[id] = pref
        writes++
    }

    @Synchronized override fun syncOffsetMs(id: String) = SyncOffset.snap(offsets[id] ?: SyncOffset.DEFAULT_MS)

    @Synchronized override fun setSyncOffsetMs(id: String, ms: Int) {
        offsets[id] = SyncOffset.snap(ms)
        writes++
    }

    @Synchronized override fun speakerGroups() = SpeakerGroupCodec.decode(groupsEncoded)

    @Synchronized override fun setSpeakerGroups(groups: List<SpeakerGroup>) {
        groupsEncoded = SpeakerGroupCodec.encode(groups)
        writes++
    }

    /** The stored text of the groups, as the phone would keep it. */
    @Synchronized fun rawGroups(): String? = groupsEncoded
}
