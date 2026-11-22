package com.airbridge.airplay.airplay

import com.airbridge.airplay.util.ByteUtil

/**
 * Wire formats of the AirPlay UDP channels (shared by AirPlay 1 and AirPlay 2 "realtime"):
 *
 *  audio      : RTP v2, PT 96 (0x60, marker 0xE0 on the first packet), seq, timestamp, SSRC
 *  timing     : receiver -> sender request PT 82 (0xD2), sender reply PT 83 (0xD3), NTP stamps
 *  sync       : sender -> receiver PT 84 (0xD4, first one 0x90 0xD4), maps RTP time to NTP time
 *  resend     : receiver -> sender PT 85 (0xD5) "please resend seq N..N+count-1"
 *  retransmit : sender -> receiver PT 86 (0xD6) = 4-byte header + the original audio packet
 */
object RtpPackets {
    const val AUDIO_HEADER_SIZE = 12
    const val SYNC_PACKET_SIZE = 20
    const val TIMING_PACKET_SIZE = 32

    const val BYTE1_AUDIO = 0x60
    const val BYTE1_AUDIO_MARKER = 0xE0
    const val BYTE1_TIMING_REQUEST = 0xD2
    const val BYTE1_TIMING_REPLY = 0xD3
    const val BYTE1_SYNC = 0xD4
    const val BYTE1_RESEND_REQUEST = 0xD5
    const val BYTE1_RETRANSMIT = 0xD6

    fun audioHeader(marker: Boolean, seq: Int, timestamp: Long, ssrc: Long): ByteArray {
        val h = ByteArray(AUDIO_HEADER_SIZE)
        h[0] = 0x80.toByte()
        h[1] = (if (marker) BYTE1_AUDIO_MARKER else BYTE1_AUDIO).toByte()
        ByteUtil.putU16BE(h, 2, seq and 0xFFFF)
        ByteUtil.putU32BE(h, 4, timestamp and 0xFFFFFFFFL)
        ByteUtil.putU32BE(h, 8, ssrc and 0xFFFFFFFFL)
        return h
    }

    /**
     * Sync packet. [rtpNow] is the RTP time being captured at [ntpNow]; [rtpLessLatency] =
     * rtpNow - latency is the frame that should be audible at [ntpNow]. Byte 3 = 0x07 is the
     * iTunes convention (receivers add their fixed 11025-frame offset).
     */
    fun sync(first: Boolean, rtpLessLatency: Long, ntpNow: Long, rtpNow: Long): ByteArray {
        val p = ByteArray(SYNC_PACKET_SIZE)
        p[0] = (if (first) 0x90 else 0x80).toByte()
        p[1] = BYTE1_SYNC.toByte()
        p[2] = 0x00
        p[3] = 0x07
        ByteUtil.putU32BE(p, 4, rtpLessLatency and 0xFFFFFFFFL)
        ByteUtil.putU64BE(p, 8, ntpNow)
        ByteUtil.putU32BE(p, 16, rtpNow and 0xFFFFFFFFL)
        return p
    }

    fun isTimingRequest(p: ByteArray, len: Int) = len >= TIMING_PACKET_SIZE && (p[1].toInt() and 0xFF) == BYTE1_TIMING_REQUEST

    /** Builds the reply to a timing request: origin = request's transmit time. */
    fun timingReply(request: ByteArray, receivedNtp: Long, sendNtp: Long): ByteArray {
        val r = ByteArray(TIMING_PACKET_SIZE)
        r[0] = 0x80.toByte()
        r[1] = BYTE1_TIMING_REPLY.toByte()
        r[2] = 0x00
        r[3] = 0x07
        System.arraycopy(request, 24, r, 8, 8)
        ByteUtil.putU64BE(r, 16, receivedNtp)
        ByteUtil.putU64BE(r, 24, sendNtp)
        return r
    }

    /** Timing request as sent by a receiver (used by the loopback mocks). */
    fun timingRequest(sendNtp: Long): ByteArray {
        val r = ByteArray(TIMING_PACKET_SIZE)
        r[0] = 0x80.toByte()
        r[1] = BYTE1_TIMING_REQUEST.toByte()
        r[2] = 0x00
        r[3] = 0x07
        ByteUtil.putU64BE(r, 24, sendNtp)
        return r
    }

    data class ResendRequest(val firstSeq: Int, val count: Int)

    fun parseResendRequest(p: ByteArray, len: Int): ResendRequest? {
        if (len < 8 || (p[1].toInt() and 0xFF) != BYTE1_RESEND_REQUEST) return null
        val first = ByteUtil.getU16BE(p, 4)
        val count = ByteUtil.getU16BE(p, 6)
        if (count <= 0 || count > 512) return null
        return ResendRequest(first, count)
    }

    fun resendRequest(requestSeq: Int, firstSeq: Int, count: Int): ByteArray {
        val p = ByteArray(8)
        p[0] = 0x80.toByte()
        p[1] = BYTE1_RESEND_REQUEST.toByte()
        ByteUtil.putU16BE(p, 2, requestSeq and 0xFFFF)
        ByteUtil.putU16BE(p, 4, firstSeq and 0xFFFF)
        ByteUtil.putU16BE(p, 6, count and 0xFFFF)
        return p
    }

    fun retransmit(original: ByteArray, seq: Int): ByteArray {
        val p = ByteArray(4 + original.size)
        p[0] = 0x80.toByte()
        p[1] = BYTE1_RETRANSMIT.toByte()
        ByteUtil.putU16BE(p, 2, seq and 0xFFFF)
        System.arraycopy(original, 0, p, 4, original.size)
        return p
    }
}

/** Keeps the most recent audio packets so receivers can ask for lost ones. */
class RetransmitBuffer(capacity: Int = 1024) {
    private val packets = arrayOfNulls<ByteArray>(capacity)
    private val seqs = IntArray(capacity) { -1 }

    @Synchronized
    fun put(seq: Int, packet: ByteArray) {
        val i = (seq and 0xFFFF) % packets.size
        packets[i] = packet
        seqs[i] = seq and 0xFFFF
    }

    @Synchronized
    fun get(seq: Int): ByteArray? {
        val i = (seq and 0xFFFF) % packets.size
        return if (seqs[i] == (seq and 0xFFFF)) packets[i] else null
    }

    @Synchronized
    fun clear() {
        packets.fill(null)
        seqs.fill(-1)
    }
}
