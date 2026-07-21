/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.diag

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses Qualcomm DIAG messages from raw /dev/diag output.
 *
 * The DIAG protocol uses HDLC-like framing:
 *   - Frame delimiter: 0x7E (CONTROL_CHAR)
 *   - Escape character: 0x7D (ESC_CHAR), next byte XORed with 0x20
 *   - CRC16 appended as 2-byte little-endian before the delimiter
 *
 * Device-level framing (from kernel driver):
 *   [type: uint32] [nelem: uint32] [len: uint32] [data] ...
 *   where type == USER_SPACE_LOG_TYPE (32)
 *
 * Qualcomm DIAG protocol parser for baseband message extraction.
 */
@Singleton
class DiagMessageParser @Inject constructor() {

    companion object {
        private const val TAG = "DiagMsgParser"

        /** HDLC escape XOR mask */
        const val ESC_MASK: Byte = 0x20

        /** HDLC escape character — next byte is XORed with ESC_MASK */
        const val ESC_CHAR: Byte = 0x7D

        /** HDLC frame delimiter */
        const val CONTROL_CHAR: Byte = 0x7E

        /** Qualcomm userspace log type prefix */
        const val USER_SPACE_LOG_TYPE: Int = 32

        /* --- Well-known DIAG log codes for cellular security analysis --- */

        /** GSM RR Signaling Message (contains Cipher Mode Command) */
        const val LOG_GSM_RR_SIGNALING: Int = 0x512F

        /** WCDMA RRC Signaling (Security Mode Command) */
        const val LOG_WCDMA_RRC_SIGNALING: Int = 0x412F

        /** LTE RRC OTA (SecurityModeCommand) */
        const val LOG_LTE_RRC_OTA: Int = 0xB0C0

        /** LTE NAS EMM OTA (contains security context) */
        const val LOG_LTE_NAS_EMM_OTA: Int = 0xB0EC

        /* --- GSM Cipher Algorithm identifiers (from Cipher Mode Command) --- */

        /** A5/0 — No encryption (major red flag) */
        const val CIPHER_A5_0: Int = 0

        /** A5/1 — Weak stream cipher, broken since 2009 */
        const val CIPHER_A5_1: Int = 1

        /** A5/2 — Export cipher, trivially broken */
        const val CIPHER_A5_2: Int = 2

        /** A5/3 — KASUMI-based, considered secure for GSM */
        const val CIPHER_A5_3: Int = 3

        /** A5/4 — Same as A5/3 with 128-bit key */
        const val CIPHER_A5_4: Int = 4
    }

    /**
     * A parsed DIAG message with its log code and payload.
     */
    data class DiagMessage(
        /** DIAG log code (e.g., 0x512F for GSM RR) */
        val logCode: Int,
        /** Raw message payload (after HDLC de-framing and CRC validation) */
        val payload: ByteArray,
        /** Timestamp from the DIAG header, if present */
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DiagMessage) return false
            return logCode == other.logCode && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * logCode + payload.contentHashCode()
    }

    /**
     * Result of cipher mode analysis from DIAG messages.
     */
    data class CipherModeInfo(
        /** The cipher algorithm in use (A5/0 through A5/4) */
        val algorithm: Int,
        /** Human-readable name (e.g., "A5/1") */
        val algorithmName: String,
        /** Whether this cipher is considered weak/dangerous */
        val isWeak: Boolean,
    )

    /**
     * De-frames raw /dev/diag output into individual DIAG messages.
     *
     * The device-level framing wraps each batch:
     *   [type: uint32] [nelem: uint32] [len: uint32] [data] ...
     *
     * Each [data] section contains HDLC-framed DIAG messages which are
     * de-escaped and CRC-validated.
     *
     * @param rawData raw bytes from DiagBridge.read()
     * @return list of validated, de-framed message payloads
     */
    fun parseRawDeviceData(rawData: ByteArray): List<ByteArray> {
        if (rawData.size < 12) return emptyList()

        val buf = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.int
        if (type != USER_SPACE_LOG_TYPE) return emptyList()

        val results = mutableListOf<ByteArray>()
        val nelem = buf.int

        for (i in 0 until nelem) {
            if (buf.remaining() < 4) break
            val len = buf.int
            if (len <= 0 || len > 1_000_000 || len > buf.remaining()) {
                Log.w(TAG, "parseRawDeviceData: invalid element length $len")
                break
            }
            val element = ByteArray(len)
            buf.get(element)
            results.add(element)
        }

        return results
    }

    /**
     * De-frames HDLC-encoded DIAG messages from a raw byte stream.
     *
     * Qualcomm DIAG message framing protocol:
     *   1. Scan for CONTROL_CHAR (0x7E) delimiters
     *   2. De-escape: ESC_CHAR (0x7D) followed by byte XOR ESC_MASK (0x20)
     *   3. Validate CRC16 (last 2 bytes before delimiter)
     *   4. Return de-escaped payload (without CRC)
     *
     * @param data raw HDLC-framed bytes (may contain multiple messages)
     * @return list of de-framed, CRC-validated message payloads
     */
    fun deframeHdlc(data: ByteArray): List<ByteArray> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val results = mutableListOf<ByteArray>()
        val deEscaped = ByteBuffer.allocate(data.size).order(ByteOrder.LITTLE_ENDIAN)

        while (buf.hasRemaining()) {
            deEscaped.clear()
            val frameStart = deEscaped.position()
            var foundDelimiter = false

            // Scan until CONTROL_CHAR, de-escaping as we go
            while (buf.hasRemaining()) {
                val b = buf.get()
                if (b == CONTROL_CHAR) {
                    foundDelimiter = true
                    break
                }
                if (b == ESC_CHAR) {
                    if (!buf.hasRemaining()) break
                    val unescaped = (buf.get().toInt() xor ESC_MASK.toInt()).toByte()
                    deEscaped.put(unescaped)
                } else {
                    deEscaped.put(b)
                }
            }

            if (!foundDelimiter) break

            val frameEnd = deEscaped.position()
            val frameLen = frameEnd - frameStart

            // Need at least 3 bytes: 1 byte payload + 2 bytes CRC
            if (frameLen < 3) continue

            // Extract payload (everything except last 2 CRC bytes)
            val payloadLen = frameLen - 2
            val payload = ByteArray(payloadLen)
            deEscaped.position(frameStart)
            deEscaped.get(payload)

            // Extract CRC (last 2 bytes, little-endian)
            val receivedCrc = deEscaped.short.toInt() and 0xFFFF

            // Validate CRC
            val expectedCrc = crc16(payload)
            if (receivedCrc != expectedCrc) {
                Log.w(TAG, "deframeHdlc: CRC mismatch: got %04x, expected %04x".format(
                    receivedCrc, expectedCrc))
                continue
            }

            results.add(payload)
        }

        return results
    }

    /**
     * Frames data for sending to /dev/diag with HDLC encoding.
     *
     * Adds CRC16, escapes special bytes, appends CONTROL_CHAR delimiter.
     *
     * @param data raw message bytes to frame
     * @return HDLC-framed byte array ready to write to the device
     */
    fun frameHdlc(data: ByteArray): ByteArray {
        val crc = crc16(data)

        // Build unescaped frame: data + CRC16 LE
        val unescaped = ByteBuffer.allocate(data.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        unescaped.put(data)
        unescaped.putShort(crc.toShort())
        unescaped.flip()

        // Count bytes needing escaping for buffer sizing
        val tempBuf = unescaped.duplicate()
        var extraBytes = 0
        while (tempBuf.hasRemaining()) {
            val b = tempBuf.get()
            if (b == ESC_CHAR || b == CONTROL_CHAR) extraBytes++
        }

        // Build escaped frame
        val escaped = ByteArray(unescaped.remaining() + extraBytes + 1)
        var i = 0
        while (unescaped.hasRemaining()) {
            val b = unescaped.get()
            if (b == ESC_CHAR || b == CONTROL_CHAR) {
                escaped[i++] = ESC_CHAR
                escaped[i++] = (b.toInt() xor ESC_MASK.toInt()).toByte()
            } else {
                escaped[i++] = b
            }
        }
        escaped[i] = CONTROL_CHAR

        return escaped
    }

    /**
     * Parses a DIAG log message to extract its log code and payload.
     *
     * DIAG log message structure (after HDLC de-framing):
     *   cmd_code (uint8), more (uint8), len (uint16 LE), log_code (uint16 LE), timestamp (8 bytes), payload...
     *
     * Log messages have cmd_code == 0x10.
     *
     * @param data de-framed DIAG message
     * @return parsed DiagMessage, or null if not a log message
     */
    fun parseLogMessage(data: ByteArray): DiagMessage? {
        if (data.size < 12) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val cmdCode = buf.get().toInt() and 0xFF

        // 0x10 = log message
        if (cmdCode != 0x10) return null

        buf.get() // skip 'more' byte
        buf.short // skip length field
        val logCode = buf.short.toInt() and 0xFFFF

        // Skip 8-byte timestamp
        buf.position(buf.position() + 8)

        if (!buf.hasRemaining()) return null

        val payload = ByteArray(buf.remaining())
        buf.get(payload)

        return DiagMessage(logCode = logCode, payload = payload)
    }

    /**
     * Extracts cipher mode information from a GSM RR Cipher Mode Command.
     *
     * The Cipher Mode Command (message type 0x35) contains the cipher
     * algorithm in bits 1-3 of the first octet of the Cipher Mode Setting IE.
     *
     * @param gsmRrPayload payload from a LOG_GSM_RR_SIGNALING (0x512F) message
     * @return cipher mode info, or null if this isn't a Cipher Mode Command
     */
    fun extractCipherMode(gsmRrPayload: ByteArray): CipherModeInfo? {
        if (gsmRrPayload.size < 2) return null

        // GSM RR messages have a message type byte; Cipher Mode Command = 0x35
        val messageType = gsmRrPayload[0].toInt() and 0xFF
        if (messageType != 0x35) return null

        // Cipher Mode Setting IE: bits 1-3 contain the algorithm
        val cipherModeSetting = gsmRrPayload[1].toInt() and 0xFF
        val algorithm = (cipherModeSetting shr 1) and 0x07

        val name = when (algorithm) {
            CIPHER_A5_0 -> "A5/0"
            CIPHER_A5_1 -> "A5/1"
            CIPHER_A5_2 -> "A5/2"
            CIPHER_A5_3 -> "A5/3"
            CIPHER_A5_4 -> "A5/4"
            else -> "A5/$algorithm"
        }

        val isWeak = algorithm <= CIPHER_A5_2

        return CipherModeInfo(
            algorithm = algorithm,
            algorithmName = name,
            isWeak = isWeak,
        )
    }

    /**
     * Checks if a DIAG message indicates a protocol anomaly.
     *
     * Looks for patterns that IMSI catchers commonly produce:
     *   - Identity Request (type 0x18) — requesting IMSI on a new connection
     *   - Location Update Reject with unusual cause codes
     *   - Authentication Reject (type 0x11)
     *
     * @param data de-framed DIAG message payload
     * @return description of the anomaly, or null if normal
     */
    fun detectProtocolAnomaly(data: ByteArray): String? {
        if (data.isEmpty()) return null

        val messageType = data[0].toInt() and 0xFF

        return when (messageType) {
            0x18 -> "Identity Request (possible IMSI harvesting)"
            0x11 -> "Authentication Reject (possible rogue network)"
            0x04 -> {
                // Location Update Reject — check cause code
                if (data.size >= 2) {
                    val cause = data[1].toInt() and 0xFF
                    when (cause) {
                        0x06 -> "LU Reject: Illegal ME (cause 6)"
                        0x0B -> "LU Reject: PLMN not allowed (cause 11)"
                        else -> null
                    }
                } else null
            }
            else -> null
        }
    }

    /* ---- CRC16 implementation for DIAG protocol framing ---- */

    /**
     * Calculates the Qualcomm DIAG CRC16 for the given data.
     *
     * This is a non-standard CRC16 used by the DIAG protocol:
     *   - Initial value: 0x84CF
     *   - Polynomial: 0x1021
     *   - LSB-first bit processing
     *   - Finalized with 2 zero-byte padding and bit reversal
     */
    fun crc16(data: ByteArray): Int {
        var crc = CRC16_INITIAL

        for (b in data) {
            crc = addByteToCrc(crc, b)
        }

        return finalizeCrc(crc)
    }

    private fun addByteToCrc(crc: Int, b: Byte): Int {
        var c = crc
        var bit = 1
        while (bit < 0x100) {
            c = c shl 1
            if (b.toInt() and bit != 0) c = c or 1
            if (c > 0xFFFF) {
                c = c and 0xFFFF
                c = c xor CRC16_POLY
            }
            bit = bit shl 1
        }
        return c
    }

    private fun finalizeCrc(crc: Int): Int {
        // Pad with two zero bytes
        var padded = addByteToCrc(crc, 0)
        padded = addByteToCrc(padded, 0)

        // Reverse bits
        var finalCrc = 0
        var i = 1
        while (i < 0x10000) {
            finalCrc = finalCrc shl 1
            if (padded and i != 0) finalCrc = finalCrc or 1
            i = i shl 1
        }

        return finalCrc xor 0xFFFF
    }
}

/** CRC16 initial value used by Qualcomm DIAG protocol */
private const val CRC16_INITIAL = 0x84CF

/** CRC16 polynomial used by Qualcomm DIAG protocol */
private const val CRC16_POLY = 0x1021
