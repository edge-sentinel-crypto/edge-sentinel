package com.airbridge.airplay.mdns

import com.airbridge.airplay.util.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class MdnsMessageTest {
    private fun responseFor(instance: String, type: ServiceType, host: String, ip: String, port: Int, txt: List<String>): ByteArray {
        val fqdn = "$instance.${type.dnsType}.local"
        return MdnsMessage.buildResponse(
            0,
            listOf(
                MdnsMessage.Record("${type.dnsType}.local", MdnsMessage.TYPE_PTR, 10, ByteArray(0), target = fqdn),
                MdnsMessage.Record(fqdn, MdnsMessage.TYPE_SRV, 10, ByteArray(0), target = "$host.local", port = port),
                MdnsMessage.Record(fqdn, MdnsMessage.TYPE_TXT, 10, TxtRecord.buildRdata(txt)),
                MdnsMessage.Record("$host.local", MdnsMessage.TYPE_A, 10, ByteArray(0), address = InetAddress.getByName(ip)),
            ),
        )
    }

    @Test
    fun queryEncodingIsStandardDns() {
        val q = MdnsMessage.buildQuery(0x1234, listOf(MdnsMessage.Question("_raop._tcp.local", MdnsMessage.TYPE_PTR)))
        // id, flags, qd=1, an=ns=ar=0, then 5"_raop" 4"_tcp" 5"local" 0, type 12, class IN with QU bit.
        assertEquals("123400000001000000000000" + "055f72616f70" + "045f746370" + "056c6f63616c" + "00" + "000c" + "8001", Hex.encode(q))
        val parsed = MdnsMessage.parse(q)
        assertEquals("_raop._tcp.local", parsed.questions.single().name)
        assertTrue(parsed.questions.single().unicastResponse)
    }

    @Test
    fun parsesCompressedNames() {
        // Hand-built response: PTR whose target uses a compression pointer to the question name.
        val hex = "0000" + "8400" + "0000" + "0001" + "0000" + "0000" +
            // answer name: _airplay._tcp.local (at offset 12)
            "085f616972706c6179045f746370056c6f63616c00" +
            "000c" + "0001" + "00000078" + "000a" +
            // rdata: "Bedroom" + pointer to offset 12
            "07426564726f6f6d" + "c00c"
        val msg = MdnsMessage.parse(Hex.decode(hex))
        val ptr = msg.records.single()
        assertEquals("_airplay._tcp.local", ptr.name)
        assertEquals("Bedroom._airplay._tcp.local", ptr.target)
        assertEquals(120L, ptr.ttl)
    }

    @Test
    fun rejectsPointerLoops() {
        val hex = "000084000000000100000000" + "c00c" + "000c" + "0001" + "00000078" + "0000"
        try {
            MdnsMessage.parse(Hex.decode(hex))
            fail("loop accepted")
        } catch (e: MdnsMessage.DnsFormatException) {
        }
    }

    @Test
    fun browserAssemblesServiceFromResponse() {
        val found = CopyOnWriteArrayList<DiscoveredService>()
        val browser = RawMdnsBrowser({ found += it })
        try {
            val resp = responseFor("Kitchen", ServiceType.AIRPLAY, "HomePod", "192.168.1.40", 7000, listOf("deviceid=A1:B2:C3:D4:E5:F6", "features=0x4A7FCA00,0x56BD0", "model=AudioAccessory1,1"))
            browser.handle(resp, resp.size)
        } finally {
            browser.close()
        }
        val s = found.first()
        assertEquals(ServiceType.AIRPLAY, s.type)
        assertEquals("Kitchen", s.instanceName)
        assertEquals("192.168.1.40", s.host)
        assertEquals(7000, s.port)
        assertEquals("A1B2C3D4E5F6", s.deviceId)
        assertEquals("AudioAccessory1,1", s.txt["model"])
    }

    /** End-to-end over real UDP: the browser's legacy-unicast query is answered by a fake responder. */
    @Test
    fun browserDiscoversOverLoopbackUdp() {
        val loopback = InetAddress.getLoopbackAddress()
        val responder = DatagramSocket(0, loopback)
        val registry = ReceiverRegistry()
        val browser = RawMdnsBrowser({ registry.onServiceResolved(it) }, InetSocketAddress(loopback, responder.localPort), queryIntervalMs = 200)
        val t = Thread {
            val buf = ByteArray(2048)
            responder.soTimeout = 5000
            try {
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    responder.receive(p)
                    val q = MdnsMessage.parse(buf, p.length)
                    if (q.questions.any { it.name == "_raop._tcp.local" }) {
                        val r = responseFor("7828CAC3238C@Garage", ServiceType.RAOP, "Sonos-Garage", "192.168.1.31", 7000, listOf("am=One", "et=0,4", "cn=0,1", "ft=0x445F8A00,0x1C340"))
                        responder.send(DatagramPacket(r, r.size, p.address, p.port))
                        val r2 = responseFor("Garage", ServiceType.AIRPLAY, "Sonos-Garage", "192.168.1.31", 7000, listOf("deviceid=78:28:CA:C3:23:8C", "model=One", "features=0x445F8A00,0x1C340"))
                        responder.send(DatagramPacket(r2, r2.size, p.address, p.port))
                        return@Thread
                    }
                }
            } catch (_: Exception) {
            }
        }
        t.start()
        browser.start()
        try {
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && registry.receivers.value.firstOrNull()?.airplayPort == null) Thread.sleep(20)
            val r = registry.receivers.value.single()
            assertEquals("Garage", r.name)
            assertEquals("192.168.1.31", r.host)
            assertNotNull(r.raopPort)
            assertNotNull(r.airplayPort)
        } finally {
            browser.close()
            responder.close()
            t.join(2000)
        }
    }
}
