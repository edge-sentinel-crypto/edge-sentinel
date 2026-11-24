package com.airbridge.airplay.mdns

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Merges DNS-SD results into one entry per physical receiver. Most receivers advertise both
 * `_raop._tcp` ("AABBCCDDEEFF@Kitchen") and `_airplay._tcp` ("Kitchen", TXT deviceid=AA:BB:..);
 * they are joined by device id, falling back to the IP address when no id is present.
 *
 * Re-announcements update the existing entry in place: a receiver that comes back with a new IP
 * address or port (a new DHCP lease, a restart) is matched by its device id, or, when either side
 * has no id, by the same service instance name (instance names are unique per service type on a
 * network). The entry keeps its key, which is the speaker id that saved volumes, sync offsets,
 * protocol choices, group membership and AirPlay 2 pairings are stored under, so a move neither
 * adds a second speaker card nor loses those settings.
 */
class ReceiverRegistry(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(
        var key: String,
        var deviceId: String?,
        var host: String,
        var raop: DiscoveredService? = null,
        var airplay: DiscoveredService? = null,
        var lastSeen: Long = 0,
    )

    /** Same service type and instance name (DNS names compare without regard to case). */
    private fun Entry.advertises(service: DiscoveredService): Boolean {
        val known = when (service.type) {
            ServiceType.RAOP -> raop
            ServiceType.AIRPLAY -> airplay
        }
        return known != null && known.instanceName.equals(service.instanceName, ignoreCase = true)
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val manual = LinkedHashMap<String, AirPlayReceiver>()
    private val _receivers = MutableStateFlow<List<AirPlayReceiver>>(emptyList())
    val receivers: StateFlow<List<AirPlayReceiver>> = _receivers.asStateFlow()

    @Synchronized
    fun onServiceResolved(service: DiscoveredService) {
        val id = service.deviceId
        val existing = entries.values.firstOrNull { id != null && it.deviceId == id }
            ?: entries.values.firstOrNull { (it.deviceId == null || id == null) && it.advertises(service) }
            ?: entries.values.firstOrNull { it.host == service.host && (it.deviceId == null || id == null) }
        val entry = existing ?: Entry(id ?: "host:${service.host}", id, service.host).also { entries[it.key] = it }
        if (entry.deviceId == null && id != null) {
            // Upgrade a host-keyed entry once a device id is known.
            entries.remove(entry.key)
            entry.deviceId = id
            entry.key = id
            entries[id] = entry
        }
        entry.host = service.host
        // Some NsdManager builds drop TXT records on a re-resolve: keep what the same
        // advertisement already told us instead of forgetting the speaker's features.
        val kept = when (service.type) {
            ServiceType.RAOP -> entry.raop
            ServiceType.AIRPLAY -> entry.airplay
        }
        val update = if (service.txt.isEmpty() && kept != null && kept.txt.isNotEmpty() &&
            kept.instanceName.equals(service.instanceName, ignoreCase = true)
        ) {
            service.copy(txt = kept.txt)
        } else {
            service
        }
        when (service.type) {
            ServiceType.RAOP -> entry.raop = update
            ServiceType.AIRPLAY -> entry.airplay = update
        }
        entry.lastSeen = now()
        publish()
    }

    /** NSD "service lost" callback: forget that advertisement (the other one may remain). */
    @Synchronized
    fun onServiceLost(type: ServiceType, instanceName: String) {
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (type == ServiceType.RAOP && e.raop?.instanceName == instanceName) e.raop = null
            if (type == ServiceType.AIRPLAY && e.airplay?.instanceName == instanceName) e.airplay = null
            if (e.raop == null && e.airplay == null) it.remove()
        }
        publish()
    }

    /** Drops entries not refreshed within [maxAgeMs] (raw mDNS results have no "lost" event). */
    @Synchronized
    fun expire(maxAgeMs: Long, keep: Set<String> = emptySet()) {
        val cutoff = now() - maxAgeMs
        val removed = entries.values.removeAll { it.lastSeen < cutoff && it.key !in keep }
        if (removed) publish()
    }

    @Synchronized
    fun addManual(receiver: AirPlayReceiver) {
        manual[receiver.id] = receiver
        publish()
    }

    @Synchronized
    fun removeManual(id: String) {
        manual.remove(id)
        publish()
    }

    @Synchronized
    fun clearDiscovered() {
        entries.clear()
        publish()
    }

    @Synchronized
    fun snapshot(): List<AirPlayReceiver> = _receivers.value

    private fun publish() {
        val list = ArrayList<AirPlayReceiver>()
        for (e in entries.values) list.add(toReceiver(e))
        list.addAll(manual.values)
        list.sortWith(compareBy({ it.manual }, { it.name.lowercase() }))
        _receivers.value = list
    }

    private fun toReceiver(e: Entry): AirPlayReceiver {
        val name = e.airplay?.displayName ?: e.raop?.displayName ?: e.host
        return AirPlayReceiver(
            id = e.key,
            name = name,
            host = e.host,
            raopPort = e.raop?.port,
            airplayPort = e.airplay?.port,
            raopTxt = e.raop?.txt ?: emptyMap(),
            airplayTxt = e.airplay?.txt ?: emptyMap(),
            manual = false,
            lastSeenMs = e.lastSeen,
        )
    }
}
