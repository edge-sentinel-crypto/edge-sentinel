package com.airbridge.airplay.codec

import com.airbridge.airplay.testutil.TestAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cross-checks against independent implementations when they are installed on the build host
 * (skipped otherwise): FFmpeg's ALAC decoder must reproduce our PCM bit-exactly from a CAF file
 * holding AirBridge's ALAC frames, and Python's plistlib must read/write the same bplist00.
 */
class ExternalInteropTest {
    private fun tool(vararg cmd: String): Boolean = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    private fun run(input: ByteArray?, vararg cmd: String): ByteArray {
        val errFile = File.createTempFile("airbridge-tool", ".err")
        try {
            val pb = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.to(errFile))
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val p = pb.start()
            if (input != null) p.outputStream.use { it.write(input) } else p.outputStream.close()
            val out = p.inputStream.readBytes()
            check(p.waitFor(60, TimeUnit.SECONDS)) { "timeout running ${cmd.first()}" }
            check(p.exitValue() == 0) { "${cmd.first()} exited with ${p.exitValue()}: ${errFile.readText().take(500)}" }
            return out
        } finally {
            errFile.delete()
        }
    }

    /** Minimal Core Audio Format writer: desc + kuki (ALAC cookie) + pakt + data. */
    private fun writeCaf(frames: List<ByteArray>, totalFrames: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeBytes("caff"); out.writeShort(1); out.writeShort(0)
        out.writeBytes("desc"); out.writeLong(32)
        out.writeDouble(44_100.0); out.writeBytes("alac"); out.writeInt(1)
        out.writeInt(0); out.writeInt(352); out.writeInt(2); out.writeInt(0)
        val cookie = AlacEncoder.magicCookie()
        out.writeBytes("kuki"); out.writeLong(cookie.size.toLong()); out.write(cookie)
        val table = ByteArrayOutputStream()
        for (f in frames) {
            var v = f.size
            val groups = ArrayList<Int>()
            do { groups.add(v and 0x7F); v = v ushr 7 } while (v > 0)
            for (i in groups.indices.reversed()) table.write(if (i > 0) groups[i] or 0x80 else groups[i])
        }
        out.writeBytes("pakt"); out.writeLong(24L + table.size())
        out.writeLong(frames.size.toLong()); out.writeLong(totalFrames); out.writeInt(0); out.writeInt(0)
        out.write(table.toByteArray())
        val dataSize = frames.sumOf { it.size }
        out.writeBytes("data"); out.writeLong(4L + dataSize); out.writeInt(0)
        frames.forEach { out.write(it) }
        out.flush()
        return bos.toByteArray()
    }

    @Test
    fun ffmpegDecodesAirBridgeAlacBitExact() {
        assumeTrue("ffmpeg not installed", tool("ffmpeg", "-version"))
        val chunks = TestAudio.sineChunks(64) + listOf(TestAudio.edgeCasePcm(352), TestAudio.randomPcm(704, 7))
        val caf = writeCaf(chunks.map { AlacEncoder.encode(it) }, chunks.size * 352L)
        val file = File.createTempFile("airbridge-alac", ".caf")
        try {
            file.writeBytes(caf)
            val raw = run(null, "ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "s16le", "-acodec", "pcm_s16le", "-")
            val expected = TestAudio.concat(chunks)
            assertEquals("decoded length", expected.size * 2, raw.size)
            val decoded = ShortArray(raw.size / 2) { ((raw[it * 2].toInt() and 0xFF) or (raw[it * 2 + 1].toInt() shl 8)).toShort() }
            assertArrayEquals("FFmpeg output differs from the PCM we encoded", expected, decoded)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pythonPlistlibReadsOurBinaryPlist() {
        assumeTrue("python3 not installed", tool("python3", "-c", "import plistlib"))
        val plist = mapOf(
            "streams" to listOf(mapOf("type" to 96L, "ct" to 2L, "shk" to ByteArray(32) { it.toByte() }, "latencyMin" to 11025L)),
            "timingProtocol" to "NTP",
            "isMultiSelectAirPlay" to true,
            "negative" to -7L,
            "big" to 0x1C340445F8A00L,
            "name" to "Küche ♫",
        )
        val script = "import plistlib,sys,json;d=plistlib.loads(sys.stdin.buffer.read());" +
            "s=d['streams'][0];print(json.dumps([d['timingProtocol'],d['isMultiSelectAirPlay'],d['negative'],d['big'],d['name'],s['type'],s['ct'],s['shk'].hex(),s['latencyMin']],ensure_ascii=False))"
        val out = String(run(BinaryPlist.encode(plist), "python3", "-c", script), Charsets.UTF_8).trim()
        assertEquals(
            "[\"NTP\", true, -7, 496155769145856, \"Küche ♫\", 96, 2, \"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\", 11025]",
            out,
        )
    }
}
