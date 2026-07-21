/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects captive portals that may inject surveillance scripts or intercept traffic.
 *
 * PRIVACY NOTE: This detector makes HTTP (not HTTPS) requests to well-known
 * captive portal detection endpoints used by Android and other OSes.
 * NO user data or device identifiers are transmitted. The only data sent is
 * a standard HTTP GET request. Response bodies are inspected locally for
 * JavaScript injection patterns and then discarded.
 *
 * Detects:
 * - Standard captive portals (HTTP 204 check fails)
 * - JavaScript injection in portal pages
 * - Redirect-based portals
 */
@Singleton
class CaptivePortalDetector @Inject constructor() {

    companion object {
        /**
         * Well-known captive portal detection URLs.
         * These endpoints return HTTP 204 (No Content) when no portal is present.
         */
        private val PORTAL_CHECK_URLS = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://clients3.google.com/generate_204",
            "http://www.gstatic.com/generate_204"
        )

        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 5000
        private const val EXPECTED_STATUS = 204

        /** Patterns indicating JavaScript injection in captive portal pages. */
        private val JS_INJECTION_PATTERNS = listOf(
            "<script",
            "javascript:",
            "document.cookie",
            "window.location",
            "eval(",
            "XMLHttpRequest",
            "fetch(",
            ".createElement('script')",
            "navigator.userAgent",
            "document.write"
        )
    }

    /**
     * Runs captive portal detection using multiple check URLs.
     */
    suspend fun runCheck(): CaptivePortalResult = withContext(Dispatchers.IO) {
        for (checkUrl in PORTAL_CHECK_URLS) {
            val result = checkUrl(checkUrl)
            if (result.captivePortalDetected) return@withContext result
        }

        // All checks passed — no captive portal
        CaptivePortalResult(
            captivePortalDetected = false
        )
    }

    /**
     * Checks a single captive portal detection URL.
     */
    private fun checkUrl(urlString: String): CaptivePortalResult {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            // Mimic a plain browser user-agent to detect portals that only trigger
            // for real browsers
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            try {
                connection.connect()
                val statusCode = connection.responseCode

                when {
                    statusCode == EXPECTED_STATUS -> {
                        // No captive portal
                        CaptivePortalResult(
                            captivePortalDetected = false,
                            httpStatusCode = statusCode
                        )
                    }
                    statusCode in 300..399 -> {
                        // Redirect — likely a captive portal
                        val redirectUrl = connection.getHeaderField("Location")
                        CaptivePortalResult(
                            captivePortalDetected = true,
                            portalUrl = redirectUrl,
                            httpStatusCode = statusCode
                        )
                    }
                    statusCode == 200 -> {
                        // Got a 200 instead of 204 — captive portal injected content
                        val body = try {
                            connection.inputStream.bufferedReader()
                                .use { it.readText().take(8192) }
                        } catch (_: Exception) { "" }

                        val jsInjection = detectJsInjection(body)

                        CaptivePortalResult(
                            captivePortalDetected = true,
                            portalUrl = urlString,
                            jsInjectionDetected = jsInjection,
                            injectionDetails = if (jsInjection) {
                                "JavaScript detected in captive portal response"
                            } else null,
                            httpStatusCode = statusCode
                        )
                    }
                    else -> {
                        CaptivePortalResult(
                            captivePortalDetected = true,
                            httpStatusCode = statusCode
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // Connection failure may itself indicate a captive portal blocking traffic
            CaptivePortalResult(
                captivePortalDetected = false
            )
        }
    }

    /**
     * Checks response body for JavaScript injection patterns.
     */
    private fun detectJsInjection(body: String): Boolean {
        if (body.isBlank()) return false
        val lowerBody = body.lowercase()
        return JS_INJECTION_PATTERNS.any { pattern ->
            lowerBody.contains(pattern.lowercase())
        }
    }
}
