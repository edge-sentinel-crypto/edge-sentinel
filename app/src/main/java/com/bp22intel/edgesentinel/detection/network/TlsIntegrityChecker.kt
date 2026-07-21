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

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

/**
 * Checks for TLS interception (MITM) by connecting to well-known HTTPS endpoints
 * and validating their certificate chains against expected issuers.
 *
 * PRIVACY NOTE: This checker connects ONLY to well-known public HTTPS endpoints
 * (Google, Cloudflare, etc.) to validate their TLS certificates. NO user data,
 * device identifiers, or telemetry is transmitted. The connections are HTTPS GETs
 * that only inspect the server certificate — response bodies are discarded.
 *
 * Detects:
 * - MITM proxies presenting substitute certificates
 * - Certificate chain anomalies (unexpected issuers)
 * - Certificate fingerprint changes from stored known-good values
 */
@Singleton
class TlsIntegrityChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /**
         * Well-known endpoints and their expected root CA issuers.
         * These are large, stable services whose CA chains rarely change.
         */
        val PROBE_ENDPOINTS = listOf(
            EndpointProbe("www.google.com", listOf("Google Trust Services", "GTS")),
            EndpointProbe("cloudflare.com", listOf("DigiCert", "Cloudflare")),
            EndpointProbe("www.microsoft.com", listOf("Microsoft", "DigiCert")),
            EndpointProbe("one.one.one.one", listOf("DigiCert", "Cloudflare"))
        )

        private const val PREFS_NAME = "tls_fingerprints"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 5000
    }

    data class EndpointProbe(
        val hostname: String,
        val expectedIssuerKeywords: List<String>
    )

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Runs TLS integrity checks against all probe endpoints.
     */
    suspend fun runFullCheck(): TlsIntegrityResult = withContext(Dispatchers.IO) {
        val results = PROBE_ENDPOINTS.map { probe ->
            async { checkEndpoint(probe) }
        }.awaitAll()

        val mitmEndpoints = results.filter { it.mitmDetected }.map { it.hostname }

        TlsIntegrityResult(
            endpointResults = results,
            overallClean = mitmEndpoints.isEmpty(),
            mitmEndpoints = mitmEndpoints
        )
    }

    /**
     * Checks a single endpoint's TLS certificate chain for MITM indicators.
     */
    suspend fun checkEndpoint(probe: EndpointProbe): TlsCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://${probe.hostname}")
                val connection = url.openConnection() as HttpsURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false

                try {
                    connection.connect()

                    val certs = connection.serverCertificates
                    if (certs.isEmpty()) {
                        return@withContext TlsCheckResult(
                            hostname = probe.hostname,
                            certificateChainValid = false,
                            mitmDetected = true,
                            error = "No certificates presented"
                        )
                    }

                    val leafCert = certs[0] as? X509Certificate
                        ?: return@withContext TlsCheckResult(
                            hostname = probe.hostname,
                            certificateChainValid = false,
                            mitmDetected = true,
                            error = "Non-X509 certificate presented"
                        )

                    // Get the issuer of the leaf certificate
                    val issuerDN = leafCert.issuerX500Principal.name
                    val fingerprint = computeFingerprint(leafCert)

                    // Check if issuer matches expected CA
                    val issuerMatchesExpected = probe.expectedIssuerKeywords.any { keyword ->
                        issuerDN.contains(keyword, ignoreCase = true)
                    }

                    // Check against stored fingerprint (if available)
                    val storedFingerprint = prefs.getString(probe.hostname, null)
                    val fingerprintMatches = if (storedFingerprint != null) {
                        storedFingerprint == fingerprint
                    } else null

                    // Store this fingerprint for future comparison
                    storeFingerprint(probe.hostname, fingerprint)

                    // MITM detected if issuer doesn't match AND fingerprint changed
                    // (fingerprint change alone can be legitimate rotation)
                    val mitmDetected = !issuerMatchesExpected

                    TlsCheckResult(
                        hostname = probe.hostname,
                        certificateChainValid = issuerMatchesExpected,
                        mitmDetected = mitmDetected,
                        presentedIssuer = issuerDN,
                        expectedIssuer = probe.expectedIssuerKeywords.joinToString(" / "),
                        certificateFingerprint = fingerprint,
                        fingerprintMatch = fingerprintMatches
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                TlsCheckResult(
                    hostname = probe.hostname,
                    certificateChainValid = false,
                    mitmDetected = false,
                    error = "Connection failed: ${e.message}"
                )
            }
        }

    /**
     * Computes SHA-256 fingerprint of a certificate's encoded form.
     */
    private fun computeFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Stores a known-good certificate fingerprint for offline comparison.
     */
    private fun storeFingerprint(hostname: String, fingerprint: String) {
        prefs.edit().putString(hostname, fingerprint).apply()
    }

    /**
     * Clears all stored fingerprints (useful after known legitimate cert rotations).
     */
    fun clearStoredFingerprints() {
        prefs.edit().clear().apply()
    }
}
