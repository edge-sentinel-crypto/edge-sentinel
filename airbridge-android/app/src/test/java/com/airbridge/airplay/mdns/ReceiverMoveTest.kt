package com.airbridge.airplay.mdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A speaker that is announced again, from a new IP address or port, stays one speaker card with
 * the same id (the key of its saved volume, sync offset, protocol choice, groups and pairing).
 * Addresses are from the 192.0.2.0/24 documentation range.
 */
class ReceiverMoveTest {
    private fun raop(instance: String, host: String, port: Int = 7000, vararg txt: String) =
        DiscoveredService(ServiceType.RAOP, instance, host, port, TxtRecord.parseStrings(txt.toList()), "test")

    private fun airplay(instance: String, host: String, port: Int = 7000, vararg txt: String) =
        DiscoveredService(ServiceType.AIRPLAY, instance, host, port, TxtRecord.parseStrings(txt.toList()), "test")

    private val features = arrayOf("features=0x445F8A00,0x1C340", "model=One", "manufacturer=Sonos")

    @Test
    fun speakerWithDeviceIdKeepsItsCardWhenItsAddressChanges() {
        val reg = ReceiverRegistry()
        reg.onServiceResolved(raop("7828CAC3238C@Kitchen", "192.0.2.31", 7000, "et=0,4", "cn=0,1"))
        reg.onServiceResolved(airplay("Kitchen", "192.0.2.31", 7000, "deviceid=78:28:CA:C3:23:8C", *features))
        val before = reg.receivers.value.single()
        assertEquals("7828CAC3238C", before.id)

        // New DHCP lease: both advertisements come back from 192.0.2.77, the AirPlay port changed too.
        reg.onServiceResolved(airplay("Kitchen", "192.0.2.77", 7001, "deviceid=78:28:CA:C3:23:8C", *features))
        reg.onServiceResolved(raop("7828CAC3238C@Kitchen", "192.0.2.77", 7000, "et=0,4", "cn=0,1"))
        val after = reg.receivers.value.single()
        assertEquals("same id, so the same saved settings and pairing", before.id, after.id)
        assertEquals("192.0.2.77", after.host)
        assertEquals(7001, after.airplayPort)
        assertEquals(7000, after.raopPort)
        assertTrue(after.supportsAirPlay2)
    }

    @Test
    fun speakerWithoutDeviceIdIsMatchedByItsInstanceName() {
        val reg = ReceiverRegistry()
        reg.onServiceResolved(airplay("Patio", "192.0.2.40", 7000, *features))
        val before = reg.receivers.value.single()
        assertEquals("host:192.0.2.40", before.id)
        reg.onServiceResolved(airplay("Patio", "192.0.2.41", 7000, *features))
        reg.onServiceResolved(airplay("PATIO", "192.0.2.42", 7000, *features)) // DNS names ignore case
        val after = reg.receivers.value.single()
        assertEquals("the card keeps its key", "host:192.0.2.40", after.id)
        assertEquals("192.0.2.42", after.host)
    }

    @Test
    fun hostKeyedEntryUpgradesWhenTheIdArrivesFromTheNewAddress() {
        val reg = ReceiverRegistry()
        reg.onServiceResolved(airplay("Den", "192.0.2.50", 7000, *features)) // TXT without deviceid
        reg.onServiceResolved(airplay("Den", "192.0.2.51", 7000, "deviceid=5C:5A:3C:11:22:33", *features))
        val r = reg.receivers.value.single()
        assertEquals("5C5A3C112233", r.id)
        assertEquals("192.0.2.51", r.host)
    }

    @Test
    fun reResolveWithoutTxtKeepsTheKnownFeatures() {
        val reg = ReceiverRegistry()
        reg.onServiceResolved(airplay("Office", "192.0.2.60", 7000, "deviceid=AA:BB:CC:DD:EE:01", *features))
        assertTrue(reg.receivers.value.single().supportsAirPlay2)
        // An OEM NsdManager re-resolve after an address change returns no TXT record at all.
        reg.onServiceResolved(airplay("Office", "192.0.2.61", 7000))
        val r = reg.receivers.value.single()
        assertEquals("AABBCCDDEE01", r.id)
        assertEquals("192.0.2.61", r.host)
        assertTrue("features kept", r.supportsAirPlay2)
        assertEquals("Sonos", r.manufacturer)
    }

    @Test
    fun twoSpeakersThatSwapAddressesStayApart() {
        val reg = ReceiverRegistry()
        reg.onServiceResolved(airplay("Left", "192.0.2.10", 7000, "deviceid=AA:BB:CC:DD:EE:0A", *features))
        reg.onServiceResolved(airplay("Right", "192.0.2.11", 7000, "deviceid=AA:BB:CC:DD:EE:0B", *features))
        reg.onServiceResolved(airplay("Left", "192.0.2.11", 7000, "deviceid=AA:BB:CC:DD:EE:0A", *features))
        reg.onServiceResolved(airplay("Right", "192.0.2.10", 7000, "deviceid=AA:BB:CC:DD:EE:0B", *features))
        val byId = reg.receivers.value.associateBy { it.id }
        assertEquals(2, byId.size)
        assertEquals("192.0.2.11", byId.getValue("AABBCCDDEE0A").host)
        assertEquals("192.0.2.10", byId.getValue("AABBCCDDEE0B").host)
    }

    @Test
    fun repeatedAnnouncementsNeverAddCards() {
        val reg = ReceiverRegistry()
        repeat(50) { i ->
            val host = "192.0.2.${100 + i % 5}"
            reg.onServiceResolved(raop("7828CAC3238C@Kitchen", host, 7000, "et=0,4", "cn=0,1"))
            reg.onServiceResolved(airplay("Kitchen", host, 7000, "deviceid=78:28:CA:C3:23:8C", *features))
        }
        assertEquals(1, reg.receivers.value.size)
        assertEquals("192.0.2.104", reg.receivers.value.single().host)
    }
}
