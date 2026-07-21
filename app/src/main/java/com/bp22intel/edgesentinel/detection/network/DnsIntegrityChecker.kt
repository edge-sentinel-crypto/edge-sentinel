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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs DNS integrity checks by comparing responses from the device's default resolver
 * against well-known trusted public DNS resolvers.
 *
 * PRIVACY NOTE: This checker makes DNS queries ONLY to well-known public DNS resolvers
 * (1.1.1.1, 8.8.8.8, 9.9.9.9) for integrity validation. NO user data, device identifiers,
 * or telemetry is ever transmitted. The only data sent is standard DNS A-record queries
 * for well-known domain names.
 *
 * Detects:
 * - DNS hijacking: device resolver returns different IPs than trusted resolvers
 * - DNS poisoning for sensitive domains (Signal, ProtonMail, WhatsApp, etc.)
 * - NXDOMAIN hijacking: ISP/state redirecting failed lookups to controlled servers
 */
@Singleton
class DnsIntegrityChecker @Inject constructor() {

    companion object {
        /** Trusted public DNS resolvers used for cross-validation. */
        val TRUSTED_RESOLVERS = listOf(
            "1.1.1.1" to "Cloudflare",
            "8.8.8.8" to "Google",
            "9.9.9.9" to "Quad9"
        )

        /** Sensitive domains commonly targeted for DNS poisoning in censorship environments. */
        val SENSITIVE_DOMAINS = listOf(
            "signal.org",
            "protonmail.com",
            "whatsapp.com",
            "torproject.org",
            "telegram.org",
            "wire.com"
        )

        /** Random nonexistent domain for NXDOMAIN hijack detection. */
        private const val NXDOMAIN_TEST = "es-nx-probe-7f3a2b.invalid"

        private const val DNS_PORT = 53
        private const val DNS_TIMEOUT_MS = 5000
    }

    /**
     * Runs a full DNS integrity check across sensitive domains and NXDOMAIN test.
     */
    suspend fun runFullCheck(): DnsIntegrityResult = withContext(Dispatchers.IO) {
        val domainResults = SENSITIVE_DOMAINS.map { domain ->
            async { checkDomain(domain) }
        }.awaitAll()

        val nxdomainResult = checkNxdomainHijack()

        val hijackedDomains = domainResults.filter { it.hijackDetected }.map { it.domain }

        DnsIntegrityResult(
            domainResults = domainResults + listOfNotNull(nxdomainResult),
            overallClean = hijackedDomains.isEmpty() && !nxdomainResult.nxdomainHijacked,
            hijackedDomains = hijackedDomains,
            nxdomainHijacked = nxdomainResult.nxdomainHijacked
        )
    }

    /**
     * Checks a single domain by comparing device DNS against trusted resolvers.
     */
    suspend fun checkDomain(domain: String): DnsCheckResult = withContext(Dispatchers.IO) {
        val deviceResult = resolveWithDefault(domain)
        val trustedResults = TRUSTED_RESOLVERS.map { (addr, name) ->
            async { resolveWithResolver(domain, addr, name) }
        }.awaitAll()

        val trustedAddresses = trustedResults
            .flatMap { it.resolvedAddresses }
            .toSet()

        // Hijack detected if device returns addresses that NO trusted resolver returned
        val deviceAddresses = deviceResult.resolvedAddresses.toSet()
        val hijackDetected = if (deviceAddresses.isNotEmpty() && trustedAddresses.isNotEmpty()) {
            (deviceAddresses - trustedAddresses).isNotEmpty() &&
                    (deviceAddresses intersect trustedAddresses).isEmpty()
        } else false

        DnsCheckResult(
            domain = domain,
            deviceResult = deviceResult,
            trustedResults = trustedResults,
            hijackDetected = hijackDetected,
            mismatchDetails = if (hijackDetected) {
                "Device resolved to ${deviceAddresses.joinToString()}, " +
                        "trusted resolvers returned ${trustedAddresses.joinToString()}"
            } else null
        )
    }

    /**
     * Checks for NXDOMAIN hijacking by querying a known-nonexistent domain.
     * If any resolver returns an IP address, the ISP/state is redirecting failed lookups.
     */
    private suspend fun checkNxdomainHijack(): DnsCheckResult = withContext(Dispatchers.IO) {
        val deviceResult = resolveWithDefault(NXDOMAIN_TEST)
        val hijacked = deviceResult.resolvedAddresses.isNotEmpty() && deviceResult.error == null

        DnsCheckResult(
            domain = NXDOMAIN_TEST,
            deviceResult = deviceResult,
            trustedResults = emptyList(),
            hijackDetected = false,
            nxdomainHijacked = hijacked,
            mismatchDetails = if (hijacked) {
                "NXDOMAIN query returned ${deviceResult.resolvedAddresses.joinToString()} — " +
                        "ISP/state is redirecting failed DNS lookups"
            } else null
        )
    }

    /**
     * Resolves a domain using the device's default DNS resolver (system InetAddress).
     */
    private fun resolveWithDefault(domain: String): DnsResolverResult {
        val start = System.currentTimeMillis()
        return try {
            val addresses = InetAddress.getAllByName(domain)
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
            DnsResolverResult(
                resolverAddress = "device_default",
                resolverName = "Device Default",
                resolvedAddresses = addresses,
                queryTimeMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            DnsResolverResult(
                resolverAddress = "device_default",
                resolverName = "Device Default",
                resolvedAddresses = emptyList(),
                queryTimeMs = System.currentTimeMillis() - start,
                error = e.message
            )
        }
    }

    /**
     * Resolves a domain by sending a raw DNS query to a specific resolver.
     * Constructs a minimal DNS A-record query packet and parses the response.
     */
    private fun resolveWithResolver(
        domain: String,
        resolverAddress: String,
        resolverName: String
    ): DnsResolverResult {
        val start = System.currentTimeMillis()
        return try {
            val query = buildDnsQuery(domain)
            val socket = DatagramSocket().apply {
                soTimeout = DNS_TIMEOUT_MS
            }
            val serverAddr = InetAddress.getByName(resolverAddress)

            socket.use { s ->
                val sendPacket = DatagramPacket(query, query.size, serverAddr, DNS_PORT)
                s.send(sendPacket)

                val responseBuffer = ByteArray(512)
                val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                s.receive(receivePacket)

                val addresses = parseDnsResponse(responseBuffer, receivePacket.length)
                DnsResolverResult(
                    resolverAddress = resolverAddress,
                    resolverName = resolverName,
                    resolvedAddresses = addresses,
                    queryTimeMs = System.currentTimeMillis() - start
                )
            }
        } catch (e: Exception) {
            DnsResolverResult(
                resolverAddress = resolverAddress,
                resolverName = resolverName,
                resolvedAddresses = emptyList(),
                queryTimeMs = System.currentTimeMillis() - start,
                error = e.message
            )
        }
    }

    /**
     * Builds a minimal DNS A-record query packet for the given domain.
     */
    private fun buildDnsQuery(domain: String): ByteArray {
        val parts = domain.split(".")
        // Header: 12 bytes
        val header = byteArrayOf(
            0x00, 0x01, // Transaction ID
            0x01, 0x00, // Flags: standard query, recursion desired
            0x00, 0x01, // Questions: 1
            0x00, 0x00, // Answers: 0
            0x00, 0x00, // Authority: 0
            0x00, 0x00  // Additional: 0
        )
        val question = buildList<Byte> {
            for (part in parts) {
                add(part.length.toByte())
                addAll(part.toByteArray().toList())
            }
            add(0x00) // null terminator
            add(0x00); add(0x01) // Type A
            add(0x00); add(0x01) // Class IN
        }
        return header + question.toByteArray()
    }

    /**
     * Parses A-record answers from a DNS response packet.
     */
    private fun parseDnsResponse(data: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()

        val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (answerCount == 0) return emptyList()

        // Skip header (12 bytes) and question section
        var offset = 12
        // Skip question name
        while (offset < length && data[offset].toInt() != 0) {
            val labelLen = data[offset].toInt() and 0xFF
            if (labelLen >= 0xC0) { // pointer
                offset += 2
                break
            }
            offset += labelLen + 1
        }
        if (offset < length && data[offset].toInt() == 0) offset++ // null terminator
        offset += 4 // skip QTYPE and QCLASS

        val addresses = mutableListOf<String>()
        for (i in 0 until answerCount) {
            if (offset >= length) break

            // Skip name (may be a pointer)
            if ((data[offset].toInt() and 0xC0) == 0xC0) {
                offset += 2
            } else {
                while (offset < length && data[offset].toInt() != 0) {
                    offset += (data[offset].toInt() and 0xFF) + 1
                }
                offset++ // null terminator
            }

            if (offset + 10 > length) break

            val rType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10

            if (rType == 1 && rdLength == 4 && offset + 4 <= length) {
                val ip = "${data[offset].toInt() and 0xFF}.${data[offset + 1].toInt() and 0xFF}." +
                        "${data[offset + 2].toInt() and 0xFF}.${data[offset + 3].toInt() and 0xFF}"
                addresses.add(ip)
            }
            offset += rdLength
        }
        return addresses
    }
}
