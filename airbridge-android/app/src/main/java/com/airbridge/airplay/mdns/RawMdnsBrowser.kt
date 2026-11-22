package com.airbridge.airplay.mdns

import com.airbridge.airplay.util.AirLog
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback mDNS browser that does not depend on Android's NsdManager. It sends "legacy unicast"
 * PTR queries (RFC 6762 section 6.7: queries from a port other than 5353 are answered by unicast
 * to that port), so it never competes with the system responder for port 5353. Responses are
 * assembled from PTR -> SRV/TXT -> A records and reported as [DiscoveredService]s.
 * The caller holds a Wi-Fi MulticastLock while this runs.
 */
class RawMdnsBrowser(
    private val onService: (DiscoveredService) -> Unit,
    private val target: InetSocketAddress = InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353),
    private val queryIntervalMs: Long = 4_000,
) : Closeable {
    private val socket = DatagramSocket(0)
    @Volatile private var running = false
    private var thread: Thread? = null

    private val ptr = ConcurrentHashMap<String, ServiceType>() // instance FQDN -> type
    private val srv = ConcurrentHashMap<String, Pair<String, Int>>() // instance -> (host, port)
    private val txt = ConcurrentHashMap<String, Map<String, String>>()
    private val addresses = ConcurrentHashMap<String, String>() // host name -> IPv4
    private val reported = ConcurrentHashMap<String, String>() // instance -> signature

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "airbridge-raw-mdns").apply { isDaemon = true; start() }
    }

    private fun loop() {
        socket.soTimeout = 250
        var nextQuery = 0L
        var id = 1
        val buf = ByteArray(9000)
        while (running) {
            val now = System.currentTimeMillis()
            if (now >= nextQuery) {
                sendQuery(id++ and 0xFFFF, ServiceType.values().map { MdnsMessage.Question("${it.dnsType}.local", MdnsMessage.TYPE_PTR) })
                followUp(id++ and 0xFFFF)
                nextQuery = now + queryIntervalMs
            }
            try {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                handle(buf, p.length)
            } catch (_: SocketTimeoutException) {
            } catch (e: SocketException) {
                if (running) AirLog.w(TAG, "mDNS socket error", e)
                break
            } catch (e: Exception) {
                AirLog.d(TAG, "ignoring malformed mDNS packet: ${e.message}")
            }
        }
    }

    private fun sendQuery(id: Int, questions: List<MdnsMessage.Question>) {
        if (questions.isEmpty()) return
        try {
            val q = MdnsMessage.buildQuery(id, questions)
            socket.send(DatagramPacket(q, q.size, target))
        } catch (e: Exception) {
            if (running) AirLog.d(TAG, "mDNS query failed: ${e.message}")
        }
    }

    /** Asks for SRV/TXT/A records that the PTR answers did not include. */
    private fun followUp(id: Int) {
        val qs = ArrayList<MdnsMessage.Question>()
        for (instance in ptr.keys) {
            if (!srv.containsKey(instance)) qs.add(MdnsMessage.Question(instance, MdnsMessage.TYPE_SRV))
            if (!txt.containsKey(instance)) qs.add(MdnsMessage.Question(instance, MdnsMessage.TYPE_TXT))
            val host = srv[instance]?.first
            if (host != null && !addresses.containsKey(host)) qs.add(MdnsMessage.Question(host, MdnsMessage.TYPE_A))
        }
        qs.chunked(8).forEach { sendQuery(id, it) }
    }

    /** Feeds one received datagram (public for unit tests). */
    fun handle(data: ByteArray, length: Int) {
        val msg = MdnsMessage.parse(data, length)
        if (!msg.isResponse) return
        for (r in msg.records) {
            when (r.type) {
                MdnsMessage.TYPE_PTR -> {
                    val type = ServiceType.fromDnsType(r.name) ?: continue
                    r.target?.let { ptr[it] = type }
                }
                MdnsMessage.TYPE_SRV -> r.target?.let { srv[r.name] = it to r.port }
                MdnsMessage.TYPE_TXT -> txt[r.name] = r.txt
                MdnsMessage.TYPE_A -> r.address?.hostAddress?.let { addresses[r.name] = it }
            }
        }
        for ((instance, type) in ptr) {
            val (host, port) = srv[instance] ?: continue
            val ip = addresses[host] ?: continue
            val record = txt[instance] ?: continue
            val service = DiscoveredService(type, instanceLabel(instance, type), ip, port, record, "raw-mdns")
            val signature = "$ip:$port:${record.hashCode()}"
            if (reported[instance] != signature) {
                reported[instance] = signature
                onService(service)
            } else {
                onService(service) // refresh last-seen time
            }
        }
    }

    override fun close() {
        running = false
        socket.close()
    }

    companion object {
        private const val TAG = "RawMdns"

        /** "Kitchen._airplay._tcp.local" -> "Kitchen". */
        fun instanceLabel(fqdn: String, type: ServiceType): String {
            val suffix = ".${type.dnsType}.local"
            return if (fqdn.endsWith(suffix, ignoreCase = true)) fqdn.substring(0, fqdn.length - suffix.length) else fqdn
        }
    }
}
