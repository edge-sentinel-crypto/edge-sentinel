package com.airbridge.airplay.airplay

import com.airbridge.airplay.util.AirLog
import com.airbridge.airplay.util.NtpClock
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * The three UDP channels of one AirPlay stream:
 *  - data    : audio RTP packets to the receiver's data/server port
 *  - control : our sync packets out, the receiver's resend requests in (answered from a
 *              retransmit ring), retransmissions out
 *  - timing  : the receiver's NTP-style timing requests in, our replies out
 */
class RtpStreamer(
    private val remote: InetAddress,
    private val clock: NtpClock,
    private val label: String,
    private val stats: SenderStats,
) : Closeable {
    private val dataSocket = DatagramSocket()
    private val controlSocket = DatagramSocket(0)
    private val timingSocket = DatagramSocket(0)
    val retransmitBuffer = RetransmitBuffer()

    val localControlPort: Int get() = controlSocket.localPort
    val localTimingPort: Int get() = timingSocket.localPort

    @Volatile var remoteDataPort = 0
    @Volatile var remoteControlPort = 0

    @Volatile private var closed = false
    private var timingThread: Thread? = null
    private var controlThread: Thread? = null
    private var lastSyncNanos = Long.MIN_VALUE
    private var firstSync = true

    /** Starts the timing responder and the control (resend) listener threads. */
    fun startResponders() {
        timingThread = Thread({ timingLoop() }, "airplay-timing-$label").apply { isDaemon = true; start() }
        controlThread = Thread({ controlLoop() }, "airplay-control-$label").apply { isDaemon = true; start() }
    }

    private fun timingLoop() {
        val buf = ByteArray(128)
        timingSocket.soTimeout = 500
        while (!closed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                timingSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                break
            }
            val received = clock.now()
            if (RtpPackets.isTimingRequest(buf, p.length)) {
                val reply = RtpPackets.timingReply(buf, received, clock.now())
                try {
                    timingSocket.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    stats.timingRepliesSent++
                } catch (e: Exception) {
                    if (!closed) AirLog.w(TAG, "$label: timing reply failed", e)
                }
            }
        }
    }

    private fun controlLoop() {
        val buf = ByteArray(2048)
        controlSocket.soTimeout = 500
        while (!closed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                controlSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                break
            }
            val req = RtpPackets.parseResendRequest(buf, p.length) ?: continue
            val port = if (remoteControlPort != 0) remoteControlPort else p.port
            for (i in 0 until req.count) {
                val seq = (req.firstSeq + i) and 0xFFFF
                val original = retransmitBuffer.get(seq) ?: continue
                val pkt = RtpPackets.retransmit(original, seq)
                try {
                    controlSocket.send(DatagramPacket(pkt, pkt.size, remote, port))
                    stats.retransmitted++
                } catch (e: Exception) {
                    if (!closed) AirLog.w(TAG, "$label: retransmit failed", e)
                }
            }
        }
    }

    fun sendAudio(seq: Int, packet: ByteArray) {
        retransmitBuffer.put(seq, packet)
        dataSocket.send(DatagramPacket(packet, packet.size, remote, remoteDataPort))
        stats.packetsSent++
        stats.bytesSent += packet.size
    }

    /** Sends a sync packet once per second (immediately when [force] or after a flush). */
    fun maybeSendSync(timeline: StreamTimeline, latencyFrames: Int, force: Boolean = false) {
        if (remoteControlPort == 0) return
        val nowNanos = clock.nowNanos()
        if (!force && !firstSync && nowNanos - lastSyncNanos < 1_000_000_000L) return
        val rtpNow = timeline.rtpAt(nowNanos)
        val pkt = RtpPackets.sync(firstSync, rtpNow - latencyFrames, NtpClock.toNtp64(nowNanos), rtpNow)
        controlSocket.send(DatagramPacket(pkt, pkt.size, remote, remoteControlPort))
        stats.syncPacketsSent++
        firstSync = false
        lastSyncNanos = nowNanos
    }

    /** After FLUSH the next sync packet is flagged as the first of a new run. */
    fun resetSync() {
        firstSync = true
    }

    override fun close() {
        closed = true
        dataSocket.close()
        controlSocket.close()
        timingSocket.close()
    }

    companion object {
        private const val TAG = "RTP"
    }
}
