package com.airbridge.airplay.testutil

import com.airbridge.airplay.util.ByteUtil

/**
 * How a strict AirPlay 2 receiver reads one RTP sync packet against the latency window
 * (`latencyMin`..`latencyMax`) that the sender declared in its stream SETUP.
 *
 * [announced] = rtpNow - rtpLessLatency (bytes 16..19 minus bytes 4..7). [total] adds the fixed
 * 11,025 frames that receivers add when bytes 2..3 carry the 0x0007 flag. A receiver may compare
 * either value with the window, so [inside] requires both to be in it.
 */
data class SyncLatency(val first: Boolean, val announced: Long, val total: Long, val min: Long, val max: Long) {
    val inside: Boolean get() = announced in min..max && total in min..max

    override fun toString(): String =
        "sync packet (${if (first) "first" else "mid-stream"}) announced $announced, total $total, SETUP window $min..$max"

    companion object {
        const val RECEIVER_ADDED_FRAMES = 11_025L

        fun of(packet: ByteArray, min: Long, max: Long): SyncLatency {
            require(packet.size >= 20) { "sync packet is ${packet.size} bytes" }
            val first = (packet[0].toInt() and 0xFF) == 0x90
            val announced = (ByteUtil.getU32BE(packet, 16) - ByteUtil.getU32BE(packet, 4)) and 0xFFFFFFFFL
            val flagged = ByteUtil.getU16BE(packet, 2) == 0x0007
            val total = announced + if (flagged) RECEIVER_ADDED_FRAMES else 0L
            return SyncLatency(first, announced, total, min, max)
        }
    }
}
