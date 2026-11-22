package com.airbridge.airplay.codec

import com.airbridge.airplay.util.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BinaryPlistAndTlvTest {
    /** Produced by Python's plistlib.dumps(..., fmt=FMT_BINARY, sort_keys=True): an independent encoder. */
    private val pythonPlist = Hex.decode(
        "62706c6973743030dd0102030405060708090a0b0c0d0e0f101112131415161718191a5362696758646576696365494458666561747572657354666c61675769734d656469615a6c6174656e63794d696e5a6c6f6e67537472696e67546e616d65586e6567617469766555726174696f5373686b5b737461747573466c6167735773747265616d7313000000123456789a5f101141413a42423a43433a44443a45453a4646130001c340445f8a000809112b115f1028787878787878787878787878787878787878787878787878787878787878787878787878787878786e004c006900760069006e006700200052006f006f006d002000e9260313ffffffffffffffd6233fe00000000000004f1020000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f1004a11bd31c1d1e1f20215b636f6e74726f6c506f72745864617461506f72745474797065111772111771106000080023002700300039003e00460051005c0061006a0070007400800088009100a500ae00af00b000b300de00fb0104010d013001320134013b0147015001550158015b000000000000020100000000000000220000000000000000000000000000015d",
    )

    @Test
    fun decodesPlistWrittenByPythonPlistlib() {
        val d = BinaryPlist.decodeDict(pythonPlist)
        assertEquals("AA:BB:CC:DD:EE:FF", d["deviceID"])
        assertEquals(0x1C340445F8A00L, d["features"])
        assertEquals(4L, d["statusFlags"])
        assertEquals("Living Room é☃", d["name"])
        assertEquals(11025L, d["latencyMin"])
        assertEquals(-42L, d["negative"])
        assertEquals(0x123456789AL, d["big"])
        assertEquals(0.5, d["ratio"])
        assertEquals(true, d["isMedia"])
        assertEquals(false, d["flag"])
        assertArrayEquals(ByteArray(32) { it.toByte() }, d["shk"] as ByteArray)
        assertEquals("x".repeat(40), d["longString"])
        val stream = (d["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(96L, stream["type"])
        assertEquals(6001L, stream["dataPort"])
        assertEquals(6002L, stream["controlPort"])
    }

    @Test
    fun roundTripsEveryType() {
        val value = linkedMapOf<String, Any?>(
            "s" to "plain",
            "u" to "Ünïcødé ✓",
            "long" to "y".repeat(300),
            "i8" to 200L, "i16" to 60_000L, "i32" to 4_000_000_000L, "i64" to Long.MAX_VALUE, "neg" to Long.MIN_VALUE,
            "d" to 3.25, "t" to true, "f" to false, "n" to null,
            "data" to ByteArray(1000) { (it * 7).toByte() },
            "date" to PlistDate(123.5),
            "uid" to PlistUid(7),
            "list" to listOf(1L, "two", listOf(3L), mapOf("four" to 4L)),
            "set" to setOf("a"),
        )
        @Suppress("UNCHECKED_CAST")
        val back = BinaryPlist.decode(BinaryPlist.encode(value)) as Map<String, Any?>
        assertEquals(value.keys.toList(), back.keys.toList())
        for ((k, v) in value) {
            when (v) {
                is ByteArray -> assertArrayEquals(k, v, back[k] as ByteArray)
                else -> assertEquals(k, v, back[k])
            }
        }
    }

    @Test
    fun usesTwoByteReferencesForLargePlists() {
        val big = (0 until 400).map { "item$it" }
        val encoded = BinaryPlist.encode(mapOf("items" to big))
        assertEquals("object ref size in trailer", 2, encoded[encoded.size - 32 + 7].toInt())
        assertEquals(big, (BinaryPlist.decodeDict(encoded)["items"]))
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf(ByteArray(10), "bplist00".toByteArray() + ByteArray(40) { 0x7F }, pythonPlist.copyOf(pythonPlist.size - 5))) {
            try {
                BinaryPlist.decode(bad)
                fail("accepted malformed plist")
            } catch (e: BinaryPlist.PlistFormatException) {
                // expected
            }
        }
    }

    @Test
    fun tlvSimpleItemsRoundTrip() {
        val encoded = Tlv8.encode(Tlv8.Type.STATE to Tlv8.byte(1), Tlv8.Type.METHOD to Tlv8.byte(0), Tlv8.Type.FLAGS to Tlv8.byte(0x10))
        assertArrayEquals(Hex.decode("060101 000100 130110"), encoded)
        val decoded = Tlv8.decode(encoded)
        assertEquals(0x10, decoded[Tlv8.Type.FLAGS]!![0].toInt())
    }

    @Test
    fun tlvFragmentsValuesOver255Bytes() {
        val key384 = ByteArray(384) { (it * 3).toByte() }
        val encoded = Tlv8.encode(Tlv8.Type.STATE to Tlv8.byte(3), Tlv8.Type.PUBLIC_KEY to key384, Tlv8.Type.PROOF to ByteArray(64) { 9 })
        // state(3) + [type,255,...255 bytes] + [type,129,...] + proof(66)
        assertEquals(3 + 257 + 131 + 66, encoded.size)
        assertEquals(255, encoded[4].toInt() and 0xFF)
        assertEquals(Tlv8.Type.PUBLIC_KEY, encoded[3 + 257].toInt())
        assertEquals(129, encoded[3 + 258].toInt() and 0xFF)
        val decoded = Tlv8.decode(encoded)
        assertArrayEquals(key384, decoded[Tlv8.Type.PUBLIC_KEY])
        assertEquals(64, decoded[Tlv8.Type.PROOF]!!.size)
    }

    @Test
    fun tlvExactMultiplesAndEmptyValues() {
        for (len in listOf(0, 1, 254, 255, 256, 510, 511, 765, 1000)) {
            val v = ByteArray(len) { (it xor len).toByte() }
            val back = Tlv8.decode(Tlv8.encode(Tlv8.Type.ENCRYPTED_DATA to v, Tlv8.Type.STATE to Tlv8.byte(5)))
            assertArrayEquals("len $len", v, back[Tlv8.Type.ENCRYPTED_DATA])
            assertEquals(5, back[Tlv8.Type.STATE]!![0].toInt())
        }
    }

    @Test
    fun tlvSeparatorKeepsSameTypeItemsApart() {
        val encoded = Tlv8.encode(Tlv8.Type.IDENTIFIER to "a".toByteArray(), Tlv8.Type.IDENTIFIER to "b".toByteArray())
        assertArrayEquals(Hex.decode("010161 ff00 010162"), encoded)
        val list = Tlv8.decodeList(encoded)
        assertEquals(listOf("a", "b"), list.map { String(it.second) })
    }

    @Test
    fun tlvRejectsTruncation() {
        try {
            Tlv8.decode(Hex.decode("0603 0102"))
            fail("truncated TLV accepted")
        } catch (e: Tlv8.TlvFormatException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }
}
