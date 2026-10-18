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
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadResult {
    data class Success(val stream: InputStream) : DownloadResult()
    data class Failure(
        val message: String,
        val isRateLimit: Boolean = false,
        val statusCode: Int? = null
    ) : DownloadResult()
}

interface OpenCelliDApi {
    suspend fun download(mcc: Int, token: String): DownloadResult
}

@Singleton
open class DefaultOpenCelliDApi @Inject constructor() : OpenCelliDApi {

    override suspend fun download(mcc: Int, token: String): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://opencellid.org/ocid/downloads?token=$token&type=mcc&file=${mcc}.csv.gz")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "EdgeSentinel/2.0")

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            } ?: return@withContext DownloadResult.Failure(
                message = "HTTP $responseCode: No response body",
                isRateLimit = responseCode == 429,
                statusCode = responseCode
            )

            parseResponse(responseCode, stream)
        } catch (e: Exception) {
            DownloadResult.Failure("Network error: ${e.message ?: "unknown"}")
        }
    }

    fun parseResponse(responseCode: Int, stream: InputStream): DownloadResult {
        val buffered = BufferedInputStream(stream, 8192)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        val isGzip = (b1 == 0x1f && b2 == 0x8b)

        if (isGzip) {
            if (responseCode !in 200..299) {
                return DownloadResult.Failure(
                    message = "HTTP $responseCode",
                    isRateLimit = responseCode == 429,
                    statusCode = responseCode
                )
            }
            buffered.reset()
            return DownloadResult.Success(GZIPInputStream(buffered))
        }

        // Not gzip -> read as error body
        buffered.reset()
        val body = try {
            buffered.bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
            ""
        }

        val serverMsg = parseServerErrorMessage(body)
        val isRate = isRateLimit(responseCode, serverMsg, body)
        val displayMsg = formatErrorMessage(serverMsg, isRate)
        return DownloadResult.Failure(
            message = displayMsg,
            isRateLimit = isRate,
            statusCode = responseCode
        )
    }

    companion object {
        fun parseServerErrorMessage(body: String): String {
            val messageRegex = Regex("""\"message\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
            val errorRegex = Regex("""\"error\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
            val descRegex = Regex("""\"description\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
            val match = messageRegex.find(body) ?: errorRegex.find(body) ?: descRegex.find(body)
            if (match != null) {
                return match.groupValues[1]
            }
            val trimmed = body.trim()
            return if (trimmed.isNotEmpty() && trimmed.length < 256) trimmed else "Invalid response from server"
        }

        fun isRateLimit(statusCode: Int?, serverMsg: String, body: String): Boolean {
            if (statusCode == 429) return true
            val combined = "$serverMsg $body".lowercase()
            return combined.contains("rate limit") ||
                    combined.contains("daily limit") ||
                    combined.contains("too many requests") ||
                    combined.contains("quota") ||
                    combined.contains("limit reached") ||
                    combined.contains("limit exceeded")
        }

        fun formatErrorMessage(serverMsg: String, isRateLimit: Boolean): String {
            return if (isRateLimit) {
                val lower = serverMsg.lowercase()
                if (lower.contains("rate limit") || lower.contains("daily limit")) {
                    serverMsg
                } else {
                    "Rate limit reached: $serverMsg"
                }
            } else {
                serverMsg
            }
        }
    }
}
