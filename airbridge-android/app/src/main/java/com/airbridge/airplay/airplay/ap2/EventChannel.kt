package com.airbridge.airplay.airplay.ap2

import com.airbridge.airplay.airplay.HapSecureInputStream
import com.airbridge.airplay.airplay.HapSecureOutputStream
import com.airbridge.airplay.airplay.RtspCodec
import com.airbridge.airplay.airplay.RtspHeaders
import com.airbridge.airplay.airplay.RtspResponse
import com.airbridge.airplay.util.AirLog
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AirPlay 2 event channel: after the session SETUP the receiver offers an `eventPort`; the sender
 * connects and the receiver pushes encrypted RTSP-style requests (e.g. remote-control commands from
 * a HomePod's touch surface). AirBridge acknowledges each with 200 OK. Best effort: a failure here
 * never stops audio.
 */
class EventChannel(
    private val host: InetAddress,
    private val port: Int,
    private val writeKey: ByteArray,
    private val readKey: ByteArray,
    private val label: String,
) : Closeable {
    private val socket = Socket()
    @Volatile private var closed = false

    @Volatile var eventsReceived = 0
        private set

    fun start() {
        socket.connect(InetSocketAddress(host, port), 3_000)
        socket.tcpNoDelay = true
        Thread({ loop() }, "airplay-events-$label").apply { isDaemon = true; start() }
    }

    private fun loop() {
        try {
            val input = BufferedInputStream(HapSecureInputStream(socket.getInputStream(), readKey))
            val output = HapSecureOutputStream(socket.getOutputStream(), writeKey)
            while (!closed) {
                val req = RtspCodec.readRequest(input) ?: break
                eventsReceived++
                AirLog.i(TAG, "$label: event ${req.method} ${req.uri}")
                val headers = RtspHeaders()
                req.headers["CSeq"]?.let { headers["CSeq"] = it }
                headers["Server"] = "AirBridge/1.0"
                output.write(RtspResponse(req.protocol, 200, "OK", headers).serialize())
                output.flush()
            }
        } catch (e: Exception) {
            if (!closed) AirLog.d(TAG, "$label: event channel ended: ${e.message}")
        }
    }

    override fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AP2-Events"
    }
}
