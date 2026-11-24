package com.airbridge.airplay.testutil

import com.airbridge.airplay.airplay.RtpPackets
import com.airbridge.airplay.util.ByteUtil
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP half of a loopback mock AirPlay receiver: data, control and timing sockets on 127.0.0.1.
 * Decodes every audio packet (through [payloadOf], which undoes the protocol's encryption) back
 * to PCM, deliberately "loses" one packet to exercise the resend/retransmit path, sends timing
 * requests to the sender and records sync packets.
 */
class MockAudioSink(
    private val label: String,
    /** Extracts the (decrypted) codec payload from a full RTP audio packet. */
    private val payloadOf: (packet: ByteArray) -> ByteArray,
    /** Decodes a codec payload to interleaved PCM. */
    @Volatile var decoder: (ByteArray) -> ShortArray,
    /** Packet ordinal (0-based) to drop on first arrival and request again; -1 disables. */
    private val dropOrdinal: Int = 5,
) : Closeable {
    /** Called for every sync packet as it arrives (a strict receiver checks it here). */
    @Volatile var onSyncPacket: ((ByteArray) -> Unit)? = null

    data class Received(val seq: Int, val timestamp: Long, val marker: Boolean, val pcm: ShortArray, val retransmitted: Boolean)

    private val loopback = InetAddress.getLoopbackAddress()
    val dataSocket = DatagramSocket(0, loopback)
    val controlSocket = DatagramSocket(0, loopback)
    val timingSocket = DatagramSocket(0, loopback)

    @Volatile var clientControlPort = 0
    @Volatile var clientTimingPort = 0
    @Volatile private var running = true

    val frames = ConcurrentSkipListMap<Int, Received>()
    val syncPackets: MutableList<ByteArray> = CopyOnWriteArrayList()
    val timingReplies: MutableList<ByteArray> = CopyOnWriteArrayList()
    val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
    val packetsSeen = AtomicInteger()
    @Volatile var droppedSeq = -1
    @Volatile var retransmitsReceived = 0
    @Volatile var firstSeq = -1

    private val threads = ArrayList<Thread>()

    fun start() {
        threads += thread("data") { dataLoop() }
        threads += thread("control") { controlLoop() }
        threads += thread("timing") { timingLoop() }
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, "mock-$label-$name").apply { isDaemon = true; start() }

    private fun dataLoop() {
        val buf = ByteArray(4096)
        dataSocket.soTimeout = 200
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                dataSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            }
            val packet = buf.copyOf(p.length)
            val ordinal = packetsSeen.getAndIncrement()
            if (ordinal == dropOrdinal) {
                // Simulate Wi-Fi loss: ignore this packet and ask for it again.
                droppedSeq = ByteUtil.getU16BE(packet, 2)
                val req = RtpPackets.resendRequest(1, droppedSeq, 1)
                if (clientControlPort != 0) controlSocket.send(DatagramPacket(req, req.size, loopback, clientControlPort))
                continue
            }
            accept(packet, retransmitted = false)
        }
    }

    private fun accept(packet: ByteArray, retransmitted: Boolean) {
        try {
            if (packet.size < 12 || (packet[0].toInt() and 0xC0) != 0x80) {
                errors += "bad RTP header"
                return
            }
            val b1 = packet[1].toInt() and 0xFF
            val marker = b1 and 0x80 != 0
            if (b1 and 0x7F != 0x60) {
                errors += "unexpected payload type ${b1 and 0x7F}"
                return
            }
            val seq = ByteUtil.getU16BE(packet, 2)
            val ts = ByteUtil.getU32BE(packet, 4)
            if (firstSeq < 0) firstSeq = seq
            val pcm = decoder(payloadOf(packet))
            // Key by distance from the first sequence number so 16-bit wrap-around keeps order.
            frames[(seq - firstSeq) and 0xFFFF] = Received(seq, ts, marker, pcm, retransmitted)
        } catch (e: Exception) {
            errors += "packet decode failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun controlLoop() {
        val buf = ByteArray(4096)
        controlSocket.soTimeout = 200
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                controlSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            }
            val b1 = buf[1].toInt() and 0xFF
            when (b1) {
                RtpPackets.BYTE1_SYNC -> {
                    val sync = buf.copyOf(p.length)
                    onSyncPacket?.invoke(sync)
                    syncPackets += sync
                }
                RtpPackets.BYTE1_RETRANSMIT -> {
                    retransmitsReceived++
                    accept(buf.copyOfRange(4, p.length), retransmitted = true)
                }
                else -> errors += "unexpected control packet 0x%02x".format(b1)
            }
        }
    }

    private fun timingLoop() {
        val buf = ByteArray(128)
        timingSocket.soTimeout = 200
        var nextRequest = 0L
        while (running) {
            val now = System.currentTimeMillis()
            if (clientTimingPort != 0 && now >= nextRequest) {
                val req = RtpPackets.timingRequest(0x1122334455667788L + now)
                timingSocket.send(DatagramPacket(req, req.size, loopback, clientTimingPort))
                nextRequest = now + 300
            }
            val p = DatagramPacket(buf, buf.size)
            try {
                timingSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            }
            timingReplies += buf.copyOf(p.length)
        }
    }

    /** PCM of all received frames in sequence order. */
    fun pcmInOrder(): ShortArray = TestAudio.concat(frames.values.map { it.pcm })

    fun waitForFrames(count: Int, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (frames.size >= count) return true
            Thread.sleep(10)
        }
        return frames.size >= count
    }

    override fun close() {
        running = false
        dataSocket.close()
        controlSocket.close()
        timingSocket.close()
        threads.forEach { it.join(1_000) }
    }
}
