package com.airbridge.airplay.mdns

import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.AudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TXT records of real receivers (Sonos One as published in an owntone issue; HomePod, Apple TV 4K,
 * Sony/Denon-class AVR and AirPort Express lines from owntone's raop.c; Shairport-Sync values from
 * its source) run through parsing, protocol selection, codec/encryption choice and merging.
 */
class DiscoveryFixturesTest {
    private fun raop(instance: String, host: String, vararg txt: String) =
        DiscoveredService(ServiceType.RAOP, instance, host, 7000, TxtRecord.parseStrings(txt.toList()), "test")

    private fun airplay(instance: String, host: String, vararg txt: String) =
        DiscoveredService(ServiceType.AIRPLAY, instance, host, 7000, TxtRecord.parseStrings(txt.toList()), "test")

    private val sonosRaop = raop(
        "7828CAC3238C@Garage", "192.168.1.31",
        "cn=0,1", "da=true", "et=0,4", "ft=0x445F8A00,0x1C340", "fv=p20.46.3-57250", "md=0,1,2", "am=One", "sf=0x4",
        "tp=UDP", "vn=65537", "vs=366.0", "pk=f410d2290e85081e295313aca75693fdca2a6cb3c35b0661b795782e8f3f2358",
    )
    private val sonosAirplay = airplay(
        "Garage", "192.168.1.31",
        "acl=0", "deviceid=78:28:CA:C3:23:8C", "features=0x445F8A00,0x1C340", "flags=0x4", "model=One", "manufacturer=Sonos",
        "srcvers=366.0", "pk=f410d2290e85081e295313aca75693fdca2a6cb3c35b0661b795782e8f3f2358",
    )
    private val homePodRaop = raop(
        "A1B2C3D4E5F6@Kitchen", "192.168.1.40",
        "cn=0,1,2,3", "da=true", "et=0,3,5", "ft=0x4A7FCA00,0x56BD0", "sf=0x404", "md=0,1,2", "am=AudioAccessory1,1",
        "tp=UDP", "vn=65537", "vs=356.19", "ov=11.2.5", "vv=2",
    )
    private val homePodAirplay = airplay(
        "Kitchen", "192.168.1.40",
        "deviceid=A1:B2:C3:D4:E5:F6", "features=0x4A7FCA00,0x56BD0", "flags=0x404", "model=AudioAccessory1,1", "srcvers=356.19",
    )
    private val appleTvRaop = raop(
        "0011223344AA@Living Room", "192.168.1.50",
        "vv=2", "ov=13.3", "vs=415.3", "vn=65537", "tp=UDP", "am=AppleTV6,2", "md=0,1,2", "sf=0x30644",
        "ft=0x4A7FFFF7,0x3C155FDE", "et=0,3,5", "da=true", "cn=0,1,2,3",
    )
    private val airportGen1 = raop(
        "001F5BAABBCC@Den", "192.168.1.60",
        "tp=TCP,UDP", "sm=false", "sv=false", "ek=1", "et=0,1", "cn=0,1", "ch=2", "ss=16", "sr=44100", "pw=false", "vn=3", "txtvers=1",
    )
    private val airportGen2 = raop("001F5BAABBCD@Office", "192.168.1.61", "am=AirPort4,107", "et=0,1", "cn=0,1", "pw=false")
    private val airportGen3 = raop("001F5BAABBCE@Porch", "192.168.1.62", "am=AirPort10,115", "et=0,4", "cn=0,1", "pw=true")
    private val avrRaop = raop(
        "5C5A3C112233@Receiver", "192.168.1.70",
        "fv=s9327.1090.0", "am=STR-DN1040", "vs=141.9", "vn=65537", "tp=UDP", "ss=16", "sr=44100", "sv=false", "pw=false",
        "md=0,2", "ft=0x44F0A00", "et=0,4", "da=true", "cn=0,1", "ch=2", "txtvers=1",
    )
    private val shairportRaop = raop(
        "DCA632112233@Pi Speaker", "192.168.1.80",
        "cn=0,1", "da=true", "et=0,4", "ft=0x401C4A00,0x1C340", "md=0,1,2", "am=ShairportSync", "sf=0x4", "tp=UDP", "vn=65537", "vs=366.0",
    )
    private val shairportAirplay = airplay(
        "Pi Speaker", "192.168.1.80",
        "deviceid=DC:A6:32:11:22:33", "features=0x401C4A00,0x1C340", "flags=0x4", "model=ShairportSync", "srcvers=366.0", "acl=0", "gcgl=0",
    )

    private fun merged(vararg services: DiscoveredService): List<AirPlayReceiver> {
        val r = ReceiverRegistry()
        services.forEach { r.onServiceResolved(it) }
        return r.receivers.value
    }

    @Test
    fun parsesRawTxtRdata() {
        val rdata = TxtRecord.buildRdata(listOf("txtvers=1", "Deviceid=AA:BB", "pw", "et=0,4", "empty=", "dup=1", "dup=2"))
        val txt = TxtRecord.parseRdata(rdata)
        assertEquals("1", txt["txtvers"])
        assertEquals("AA:BB", txt["deviceid"])
        assertEquals("", txt["pw"])
        assertEquals("0,4", txt["et"])
        assertEquals("", txt["empty"])
        assertEquals("first occurrence wins", "1", txt["dup"])
        // A truncated record keeps the complete entries.
        assertEquals("1", TxtRecord.parseRdata(rdata.copyOf(12))["txtvers"])
        assertEquals("AA:BB", TxtRecord.fromAttributes(mapOf("DeviceID" to "AA:BB".toByteArray(), "flag" to null))["deviceid"])
    }

    @Test
    fun featureBitmasks() {
        val sonos = AirPlayFeatures.parse("0x445F8A00,0x1C340")
        assertEquals(0x1C340445F8A00L, sonos)
        assertTrue(AirPlayFeatures.has(sonos, AirPlayFeatures.AUDIO))
        assertTrue(AirPlayFeatures.has(sonos, AirPlayFeatures.TRANSIENT_PAIRING))
        assertTrue(AirPlayFeatures.has(sonos, AirPlayFeatures.PTP))
        assertTrue(AirPlayFeatures.isAirPlay2(sonos))
        assertFalse(AirPlayFeatures.isAirPlay2(AirPlayFeatures.parse("0x44F0A00"))) // AirPlay 1 AVR
        assertFalse(AirPlayFeatures.isAirPlay2(AirPlayFeatures.parse("0x5A7FFFF7,0xE"))) // Apple TV 3
        assertTrue(AirPlayFeatures.isAirPlay2(AirPlayFeatures.parse("0x4A7FFFF7,0x3C155FDE"))) // Apple TV 4K
        assertEquals(0L, AirPlayFeatures.parse("garbage"))
        assertEquals(0L, AirPlayFeatures.parse(null))
        assertEquals("0x445F8A00,0x1C340", AirPlayFeatures.format(sonos))
        assertEquals(0x30644L, AirPlayFeatures.parseFlags("0x30644"))
    }

    @Test
    fun sonosMergesIntoOneAirPlay2Receiver() {
        val list = merged(sonosRaop, sonosAirplay)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals("7828CAC3238C", s.id)
        assertEquals("Garage", s.name)
        assertEquals(7000, s.raopPort)
        assertEquals(7000, s.airplayPort)
        assertTrue(s.supportsAirPlay2)
        assertEquals(AirPlayProtocol.AIRPLAY_2, s.recommendedProtocol)
        assertEquals(AudioCodec.ALAC, s.raopCodec)
        assertFalse(s.raopNeedsEncryption)
        assertFalse("auth-setup only after a 403 for non-AirPort units", s.raopWantsAuthSetup)
        assertFalse(s.isLocked)
        assertEquals("Sonos One", s.modelLabel)
        assertEquals(AirPlayProtocol.AIRPLAY_1, s.protocolFor(ProtocolPreference.AIRPLAY_1))
    }

    @Test
    fun homePodIsAirPlay2() {
        val hp = merged(homePodRaop, homePodAirplay).single()
        assertEquals(AirPlayProtocol.AIRPLAY_2, hp.recommendedProtocol)
        assertEquals("HomePod", hp.modelLabel)
        assertEquals(setOf(0, 3, 5), hp.encryptionTypes)
        assertFalse(hp.requiresPin)
    }

    @Test
    fun appleTv4kNeedsOneTimePinPairing() {
        val atv = merged(appleTvRaop).single()
        assertTrue("flag 0x200 = one-time pairing", atv.requiresPin)
        assertTrue(atv.isLocked)
        assertEquals("Apple TV", atv.modelLabel)
        // Only _raop._tcp seen so far: the AirPlay 2 path needs the _airplay._tcp port, so Auto uses AirPlay 1.
        assertEquals(AirPlayProtocol.AIRPLAY_1, atv.recommendedProtocol)
        val both = merged(appleTvRaop, airplay("Living Room", "192.168.1.50", "deviceid=00:11:22:33:44:AA", "features=0x4A7FFFF7,0x3C155FDE", "flags=0x30644", "model=AppleTV6,2"))
        assertEquals(AirPlayProtocol.AIRPLAY_2, both.single().recommendedProtocol)
    }

    @Test
    fun airportExpressEncryptionRules() {
        val gen1 = merged(airportGen1).single()
        assertTrue("gen 1 (no am, ek=1) needs RSA/AES", gen1.raopNeedsEncryption)
        assertEquals(AirPlayProtocol.AIRPLAY_1, gen1.recommendedProtocol)
        val gen2 = merged(airportGen2).single()
        assertTrue(gen2.raopNeedsEncryption)
        assertEquals("AirPort Express", gen2.modelLabel)
        val gen3 = merged(airportGen3).single()
        assertFalse(gen3.raopNeedsEncryption)
        assertTrue("MFi AirPort gets auth-setup", gen3.raopWantsAuthSetup)
        assertTrue(gen3.requiresPassword)
    }

    @Test
    fun airPlay1OnlyAvr() {
        val avr = merged(avrRaop).single()
        assertFalse(avr.supportsAirPlay2)
        assertEquals(AirPlayProtocol.AIRPLAY_1, avr.recommendedProtocol)
        assertEquals(AudioCodec.ALAC, avr.raopCodec)
        assertEquals(setOf(0, 2), avr.metadataTypes)
        assertEquals("STR-DN1040", avr.modelLabel)
        val pcmOnly = merged(raop("AABBCCDDEEFF@Old", "10.0.0.9", "cn=0", "et=0")).single()
        assertEquals(AudioCodec.L16, pcmOnly.raopCodec)
    }

    @Test
    fun shairportSyncUsesAirPlay1BecauseItsAirPlay2NeedsPtp() {
        val pi = merged(shairportAirplay, shairportRaop).single()
        assertTrue(pi.isShairportSync)
        assertTrue(AirPlayFeatures.isAirPlay2(pi.features))
        assertEquals(AirPlayProtocol.AIRPLAY_1, pi.recommendedProtocol)
        assertEquals(AirPlayProtocol.AIRPLAY_2, pi.protocolFor(ProtocolPreference.AIRPLAY_2))
        assertEquals("Shairport-Sync", pi.modelLabel)
    }

    @Test
    fun registryDedupesByDeviceIdThenIpAndHandlesLoss() {
        val reg = ReceiverRegistry()
        // AirPlay record without deviceid first: keyed by host until the RAOP record supplies the id.
        reg.onServiceResolved(airplay("Garage", "192.168.1.31", "features=0x445F8A00,0x1C340", "model=One"))
        reg.onServiceResolved(sonosRaop)
        reg.onServiceResolved(homePodAirplay)
        reg.onServiceResolved(homePodRaop)
        assertEquals(2, reg.receivers.value.size)
        val sonos = reg.receivers.value.first { it.name == "Garage" }
        assertEquals("7828CAC3238C", sonos.id)
        assertEquals(7000, sonos.airplayPort)
        reg.onServiceLost(ServiceType.AIRPLAY, "Garage")
        val after = reg.receivers.value.first { it.name == "Garage" }
        assertNull(after.airplayPort)
        assertEquals(AirPlayProtocol.AIRPLAY_1, after.recommendedProtocol)
        reg.onServiceLost(ServiceType.RAOP, "7828CAC3238C@Garage")
        assertEquals(1, reg.receivers.value.size)
    }

    @Test
    fun manualReceiversAndExpiry() {
        var now = 1_000L
        val reg = ReceiverRegistry { now }
        reg.onServiceResolved(avrRaop)
        reg.addManual(AirPlayReceiver.manual("10.0.0.5", 7000, AirPlayProtocol.AIRPLAY_2, "Garage HomePod"))
        reg.addManual(AirPlayReceiver.manual("10.0.0.6", 5000, AirPlayProtocol.AIRPLAY_1, ""))
        assertEquals(3, reg.receivers.value.size)
        val ap2 = reg.receivers.value.first { it.host == "10.0.0.5" }
        assertTrue(ap2.manual)
        assertEquals(AirPlayProtocol.AIRPLAY_2, ap2.recommendedProtocol)
        val ap1 = reg.receivers.value.first { it.host == "10.0.0.6" }
        assertEquals("10.0.0.6:5000", ap1.name)
        assertEquals(AirPlayProtocol.AIRPLAY_1, ap1.recommendedProtocol)
        now += 10 * 60_000
        reg.expire(5 * 60_000)
        assertEquals("discovered entry expired, manual ones kept", 2, reg.receivers.value.size)
    }

    @Test
    fun deviceIdNormalization() {
        assertEquals("AABBCCDDEEFF", DiscoveredService.normalizeDeviceId("aa:bb:cc:dd:ee:ff"))
        assertNull(DiscoveredService.normalizeDeviceId("not-an-id"))
        assertEquals("7828CAC3238C", sonosRaop.raopDeviceId)
        assertEquals("Garage", sonosRaop.displayName)
        assertEquals(ServiceType.AIRPLAY, ServiceType.fromDnsType("_airplay._tcp.local."))
        assertEquals(ServiceType.RAOP, ServiceType.fromDnsType("_raop._tcp."))
    }
}
