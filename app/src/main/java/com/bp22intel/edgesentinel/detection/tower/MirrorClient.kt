/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.tower

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class MirrorManifest(
    val schema: Int,
    val generated: String,
    val source: String,
    val licenseUrl: String,
    val baseUrl: String,
    val countries: List<CountryManifestEntry>
)

data class CountryManifestEntry(
    val iso2: String,
    val name: String,
    val mccs: List<Int>,
    val file: String,
    val rows: Int,
    val bytes: Long,
    val sha256: String,
    val dataDate: String
)

sealed class MirrorDownloadResult {
    data class Success(val tempFile: File, val entry: CountryManifestEntry) : MirrorDownloadResult()
    data class Failure(val message: String, val isSha256Mismatch: Boolean = false) : MirrorDownloadResult()
}

interface MirrorClient {
    suspend fun getManifest(forceRefresh: Boolean = false): Result<MirrorManifest>
    suspend fun downloadCountry(entry: CountryManifestEntry, cacheDir: File): MirrorDownloadResult
}

@Singleton
open class DefaultMirrorClient @Inject constructor() : MirrorClient {

    companion object {
        const val MANIFEST_URL =
            "https://github.com/edge-sentinel-crypto/edge-sentinel/releases/download/tower-data/manifest.json"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
    }

    private var cachedManifest: MirrorManifest? = null

    override suspend fun getManifest(forceRefresh: Boolean): Result<MirrorManifest> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedManifest != null) {
                return@withContext Result.success(cachedManifest!!)
            }

            try {
                val connection = openConnectionWithRedirects(MANIFEST_URL)
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
                val manifest = ManifestParser.parse(body)
                cachedManifest = manifest
                Result.success(manifest)
            } catch (e: Exception) {
                if (cachedManifest != null) {
                    Result.success(cachedManifest!!)
                } else {
                    Result.failure(e)
                }
            }
        }

    override suspend fun downloadCountry(
        entry: CountryManifestEntry,
        cacheDir: File
    ): MirrorDownloadResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var tempFile: File? = null
        try {
            val manifest = cachedManifest ?: getManifest().getOrNull()
            val baseUrl = manifest?.baseUrl
                ?: "https://github.com/edge-sentinel-crypto/edge-sentinel/releases/download/tower-data/"
            val fileUrl = if (baseUrl.endsWith("/")) baseUrl + entry.file else "$baseUrl/${entry.file}"

            connection = openConnectionWithRedirects(fileUrl)
            tempFile = File.createTempFile("tower_${entry.iso2}_", ".csv.gz", cacheDir)
            val digest = MessageDigest.getInstance("SHA-256")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!computedSha256.equals(entry.sha256, ignoreCase = true)) {
                tempFile.delete()
                return@withContext MirrorDownloadResult.Failure(
                    "SHA-256 mismatch for ${entry.name}: expected ${entry.sha256}, got $computedSha256",
                    isSha256Mismatch = true
                )
            }

            MirrorDownloadResult.Success(tempFile, entry)
        } catch (e: Exception) {
            tempFile?.delete()
            MirrorDownloadResult.Failure("Network error downloading ${entry.name}: ${e.message ?: "unknown"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnectionWithRedirects(urlString: String): HttpURLConnection {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 10) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "EdgeSentinel/2.0")
            conn.instanceFollowRedirects = false

            val status = conn.responseCode
            if (status in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $status redirect without Location header")
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(url, location).toString()
                }
                conn.disconnect()
                redirects++
            } else if (status in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IOException("HTTP $status error from $currentUrl")
            }
        }
        throw IOException("Too many redirects: $redirects")
    }
}

object ManifestParser {
    fun parse(jsonStr: String): MirrorManifest {
        val parser = JsonParser(jsonStr)
        val root = parser.parseObject()
        val schema = (root["schema"] as? Number)?.toInt() ?: 1
        val generated = root["generated"] as? String ?: ""
        val source = root["source"] as? String ?: ""
        val licenseUrl = root["license_url"] as? String ?: ""
        val baseUrl = root["base_url"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val countryList = (root["countries"] as? List<Map<String, Any?>>) ?: emptyList()
        val countries = countryList.map { c ->
            val mccsList = (c["mccs"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            CountryManifestEntry(
                iso2 = c["iso2"] as? String ?: "",
                name = c["name"] as? String ?: "",
                mccs = mccsList,
                file = c["file"] as? String ?: "",
                rows = (c["rows"] as? Number)?.toInt() ?: 0,
                bytes = (c["bytes"] as? Number)?.toLong() ?: 0L,
                sha256 = c["sha256"] as? String ?: "",
                dataDate = c["data_date"] as? String ?: ""
            )
        }

        return MirrorManifest(
            schema = schema,
            generated = generated,
            source = source,
            licenseUrl = licenseUrl,
            baseUrl = baseUrl,
            countries = countries
        )
    }

    private class JsonParser(private val jsonStr: String) {
        private var pos = 0
        private val len = jsonStr.length

        private fun skipWhitespace() {
            while (pos < len && jsonStr[pos].isWhitespace()) pos++
        }

        fun parseString(): String {
            skipWhitespace()
            if (pos >= len || jsonStr[pos] != '"') throw IllegalArgumentException("Expected '\"' at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < len) {
                val c = jsonStr[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\' && pos < len) {
                    val esc = jsonStr[pos++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = jsonStr.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        fun parseNumber(): Number {
            skipWhitespace()
            val start = pos
            if (pos < len && (jsonStr[pos] == '-' || jsonStr[pos] == '+')) pos++
            while (pos < len && (jsonStr[pos].isDigit() || jsonStr[pos] == '.' || jsonStr[pos] == 'e' || jsonStr[pos] == 'E' || jsonStr[pos] == '+' || jsonStr[pos] == '-')) {
                pos++
            }
            val numStr = jsonStr.substring(start, pos)
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                numStr.toDouble()
            } else {
                numStr.toLong()
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= len) return null
            return when (jsonStr[pos]) {
                '"' -> parseString()
                '{' -> parseObject()
                '[' -> parseArray()
                't', 'f' -> {
                    if (jsonStr.startsWith("true", pos)) { pos += 4; true }
                    else if (jsonStr.startsWith("false", pos)) { pos += 5; false }
                    else throw IllegalArgumentException("Unexpected token at $pos")
                }
                'n' -> {
                    if (jsonStr.startsWith("null", pos)) { pos += 4; null }
                    else throw IllegalArgumentException("Unexpected token at $pos")
                }
                else -> parseNumber()
            }
        }

        fun parseArray(): List<Any?> {
            skipWhitespace()
            pos++ // skip '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < len && jsonStr[pos] == ']') {
                pos++
                return list
            }
            while (pos < len) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < len && jsonStr[pos] == ',') {
                    pos++
                } else if (pos < len && jsonStr[pos] == ']') {
                    pos++
                    break
                }
            }
            return list
        }

        fun parseObject(): Map<String, Any?> {
            skipWhitespace()
            pos++ // skip '{'
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (pos < len && jsonStr[pos] == '}') {
                pos++
                return map
            }
            while (pos < len) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos < len && jsonStr[pos] == ':') pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < len && jsonStr[pos] == ',') {
                    pos++
                } else if (pos < len && jsonStr[pos] == '}') {
                    pos++
                    break
                }
            }
            return map
        }
    }
}
