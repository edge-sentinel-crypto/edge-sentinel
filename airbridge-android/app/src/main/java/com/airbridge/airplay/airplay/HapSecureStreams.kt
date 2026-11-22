package com.airbridge.airplay.airplay

import com.airbridge.airplay.crypto.HapCrypto
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * HAP encrypted framing used on the AirPlay 2 control connection (and event channel) after
 * pairing: each frame is a 2-byte little-endian plaintext length (max 1024), the ChaCha20-Poly1305
 * ciphertext, and a 16-byte tag. The length bytes are the AAD; the nonce is a 64-bit little-endian
 * frame counter (starting at 0, never reused) behind four zero bytes. Each direction has its own
 * key and counter.
 */
class HapSecureOutputStream(private val out: OutputStream, private val key: ByteArray) : OutputStream() {
    private val pending = java.io.ByteArrayOutputStream()
    private var counter = 0L

    val framesWritten: Long get() = counter

    override fun write(b: Int) {
        pending.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        pending.write(b, off, len)
    }

    @Synchronized
    override fun flush() {
        val data = pending.toByteArray()
        pending.reset()
        var pos = 0
        while (pos < data.size) {
            val len = minOf(MAX_FRAME, data.size - pos)
            val aad = byteArrayOf(len.toByte(), (len ushr 8).toByte())
            val sealed = HapCrypto.seal(key, HapCrypto.counterNonce(counter++), data, pos, len, aad)
            out.write(aad)
            out.write(sealed)
            pos += len
        }
        out.flush()
    }

    override fun close() {
        flush()
        out.close()
    }

    companion object {
        const val MAX_FRAME = 1024
    }
}

class HapSecureInputStream(private val input: InputStream, private val key: ByteArray) : InputStream() {
    private var buffer = ByteArray(0)
    private var pos = 0
    private var counter = 0L

    private fun fill(): Boolean {
        val header = ByteArray(2)
        if (!readFully(header, allowEof = true)) return false
        val len = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
        if (len > HapSecureOutputStream.MAX_FRAME) throw IOException("encrypted frame too large: $len")
        val sealed = ByteArray(len + HapCrypto.TAG_LENGTH)
        readFully(sealed, allowEof = false)
        buffer = HapCrypto.open(key, HapCrypto.counterNonce(counter++), sealed, header)
        pos = 0
        return true
    }

    private fun readFully(dst: ByteArray, allowEof: Boolean): Boolean {
        var read = 0
        while (read < dst.size) {
            val n = input.read(dst, read, dst.size - read)
            if (n < 0) {
                if (allowEof && read == 0) return false
                throw EOFException("connection closed inside an encrypted frame")
            }
            read += n
        }
        return true
    }

    override fun read(): Int {
        while (pos >= buffer.size) if (!fill()) return -1
        return buffer[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= buffer.size) if (!fill()) return -1
        val n = minOf(len, buffer.size - pos)
        System.arraycopy(buffer, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = buffer.size - pos

    override fun close() = input.close()
}
