/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.travel

data class CountryThreatProfile(
    val countryCode: String,
    val countryName: String,
    val flagEmoji: String,
    val mcc: String,
    val riskLevel: Int, // 1 (low) to 5 (critical)
    val primaryThreats: List<String>,
    val recommendedSettings: RecommendedSettings,
    val advisoryText: String
)

data class RecommendedSettings(
    val detectionSensitivity: String = "HIGH",
    val enableVpnMonitoring: Boolean = true,
    val enableDnsIntegrityCheck: Boolean = true,
    val enableImsiCatcherDetection: Boolean = true,
    val enableNetworkDowngradeAlert: Boolean = true,
    val enableSilentSmsDetection: Boolean = true,
    val scanIntervalMinutes: Int = 5
)

object CountryThreatProfiles {

    private val profiles = listOf(
        CountryThreatProfile(
            countryCode = "CN",
            countryName = "China",
            flagEmoji = "\uD83C\uDDE8\uD83C\uDDF3",
            mcc = "460",
            riskLevel = 5,
            primaryThreats = listOf(
                "Carrier-level traffic intercept",
                "GFW DNS poisoning and manipulation",
                "VPN blocking and detection",
                "Targeted IMSI catcher deployment",
                "Mandatory backdoor access on domestic networks"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableDnsIntegrityCheck = true,
                enableVpnMonitoring = true,
                scanIntervalMinutes = 3
            ),
            advisoryText = "China operates extensive carrier-level surveillance infrastructure. " +
                "DNS queries are subject to manipulation by the Great Firewall. VPN connections " +
                "may be actively detected and disrupted. IMSI catchers are deployed in areas of " +
                "interest. Assume all unencrypted network traffic is monitored."
        ),
        CountryThreatProfile(
            countryCode = "RU",
            countryName = "Russia",
            flagEmoji = "\uD83C\uDDF7\uD83C\uDDFA",
            mcc = "250",
            riskLevel = 5,
            primaryThreats = listOf(
                "IMSI catcher / Stingray deployment",
                "Fake base station operations",
                "Forced network downgrades (4G to 2G)",
                "SORM lawful intercept system",
                "Unusual tower activity near government facilities"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableNetworkDowngradeAlert = true,
                scanIntervalMinutes = 3
            ),
            advisoryText = "Russia has documented use of IMSI catchers and Stingray devices, " +
                "particularly near government buildings and during public events. The SORM system " +
                "provides authorities with direct access to carrier infrastructure. Network " +
                "downgrades to 2G may indicate targeted interception attempts."
        ),
        CountryThreatProfile(
            countryCode = "IR",
            countryName = "Iran",
            flagEmoji = "\uD83C\uDDEE\uD83C\uDDF7",
            mcc = "432",
            riskLevel = 5,
            primaryThreats = listOf(
                "Internet shutdowns during unrest",
                "HTTPS man-in-the-middle interception",
                "Forced 2G downgrade near protest areas",
                "Deep packet inspection",
                "Domestic certificate authority abuse"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableNetworkDowngradeAlert = true,
                enableDnsIntegrityCheck = true,
                scanIntervalMinutes = 3
            ),
            advisoryText = "Iran employs internet shutdowns during periods of civil unrest. " +
                "HTTPS traffic may be subject to man-in-the-middle attacks using domestically " +
                "issued certificates. Forced downgrades to 2G have been documented near " +
                "protest locations, enabling easier interception of communications."
        ),
        CountryThreatProfile(
            countryCode = "AE",
            countryName = "United Arab Emirates",
            flagEmoji = "\uD83C\uDDE6\uD83C\uDDEA",
            mcc = "424",
            riskLevel = 4,
            primaryThreats = listOf(
                "Commercial spyware deployment (Pegasus, Predator)",
                "Carrier-level traffic intercept",
                "IMSI catcher use near VIP events",
                "VoIP blocking and monitoring",
                "Deep packet inspection"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableVpnMonitoring = true,
                scanIntervalMinutes = 5
            ),
            advisoryText = "The UAE has documented deployment of commercial spyware including " +
                "Pegasus. Carrier-level intercept capabilities are in place. IMSI catchers have " +
                "been observed near diplomatic events and VIP gatherings. VoIP services are " +
                "restricted and monitored."
        ),
        CountryThreatProfile(
            countryCode = "SA",
            countryName = "Saudi Arabia",
            flagEmoji = "\uD83C\uDDF8\uD83C\uDDE6",
            mcc = "420",
            riskLevel = 4,
            primaryThreats = listOf(
                "Commercial spyware deployment",
                "Carrier-level surveillance",
                "IMSI catcher operations",
                "SS7 exploitation for location tracking",
                "Social media monitoring"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableSilentSmsDetection = true,
                scanIntervalMinutes = 5
            ),
            advisoryText = "Saudi Arabia has invested in commercial surveillance capabilities " +
                "including spyware and SS7 exploitation tools. Carrier-level monitoring is " +
                "established. Silent SMS tracking and IMSI catcher deployments have been " +
                "documented by security researchers."
        ),
        CountryThreatProfile(
            countryCode = "KP",
            countryName = "North Korea",
            flagEmoji = "\uD83C\uDDF0\uD83C\uDDF5",
            mcc = "467",
            riskLevel = 5,
            primaryThreats = listOf(
                "Total network compromise assumed",
                "All traffic monitored and logged",
                "Foreign device detection and tracking",
                "No secure communication possible on domestic network",
                "Physical device inspection risk"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableNetworkDowngradeAlert = true,
                enableDnsIntegrityCheck = true,
                scanIntervalMinutes = 1
            ),
            advisoryText = "North Korea operates a fully state-controlled telecommunications " +
                "infrastructure. All network traffic should be assumed compromised. Foreign " +
                "mobile devices are tracked and may be subject to physical inspection. No " +
                "communication over domestic networks can be considered private."
        ),
        CountryThreatProfile(
            countryCode = "TR",
            countryName = "Turkey",
            flagEmoji = "\uD83C\uDDF9\uD83C\uDDF7",
            mcc = "286",
            riskLevel = 3,
            primaryThreats = listOf(
                "Man-in-the-middle attacks on encrypted traffic",
                "Cell surveillance near border regions",
                "VPN and Tor blocking",
                "Deep packet inspection",
                "Social media throttling"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableDnsIntegrityCheck = true,
                enableVpnMonitoring = true,
                scanIntervalMinutes = 5
            ),
            advisoryText = "Turkey has documented capabilities for intercepting encrypted " +
                "traffic through man-in-the-middle techniques. Cell surveillance is heightened " +
                "near border regions and during security operations. VPN and Tor access may " +
                "be blocked or throttled. Deep packet inspection is deployed at carrier level."
        ),
        CountryThreatProfile(
            countryCode = "EG",
            countryName = "Egypt",
            flagEmoji = "\uD83C\uDDEA\uD83C\uDDEC",
            mcc = "602",
            riskLevel = 4,
            primaryThreats = listOf(
                "Deep packet inspection (FinFisher/Sandvine)",
                "IMSI catcher deployment",
                "Internet throttling and shutdowns",
                "HTTPS interception",
                "Targeted surveillance of journalists and activists"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableDnsIntegrityCheck = true,
                scanIntervalMinutes = 5
            ),
            advisoryText = "Egypt deploys deep packet inspection technology including " +
                "FinFisher and Sandvine equipment for traffic analysis. IMSI catchers are " +
                "operational in urban areas. Internet shutdowns have occurred during periods " +
                "of political tension. HTTPS traffic may be subject to interception."
        ),
        CountryThreatProfile(
            countryCode = "IL",
            countryName = "Israel",
            flagEmoji = "\uD83C\uDDEE\uD83C\uDDF1",
            mcc = "425",
            riskLevel = 4,
            primaryThreats = listOf(
                "Sophisticated TSCM environment",
                "Origin of Pegasus and other commercial spyware",
                "Advanced SIGINT capabilities",
                "Cell network monitoring in border areas",
                "Stingray deployment capability"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableSilentSmsDetection = true,
                scanIntervalMinutes = 5
            ),
            advisoryText = "Israel has one of the most sophisticated signals intelligence " +
                "environments globally. It is the origin of several commercial spyware " +
                "platforms including Pegasus. Advanced TSCM and cell monitoring capabilities " +
                "are deployed, particularly in border regions and areas of security interest."
        ),
        CountryThreatProfile(
            countryCode = "BY",
            countryName = "Belarus",
            flagEmoji = "\uD83C\uDDE7\uD83C\uDDFE",
            mcc = "257",
            riskLevel = 4,
            primaryThreats = listOf(
                "IMSI catcher / Stingray deployment",
                "Internet shutdowns during protests",
                "Carrier-level surveillance (SORM-derived)",
                "Forced network downgrades",
                "Targeted device tracking"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableNetworkDowngradeAlert = true,
                scanIntervalMinutes = 3
            ),
            advisoryText = "Belarus has documented use of IMSI catchers and Stingray equipment, " +
                "particularly during protests and political events. Full internet shutdowns have " +
                "been imposed during periods of civil unrest. Carrier-level surveillance is " +
                "derived from Russian SORM technology."
        ),
        CountryThreatProfile(
            countryCode = "VE",
            countryName = "Venezuela",
            flagEmoji = "\uD83C\uDDFB\uD83C\uDDEA",
            mcc = "734",
            riskLevel = 3,
            primaryThreats = listOf(
                "Cuban-supplied surveillance technology",
                "Carrier-level intercept (CANTV/Movilnet)",
                "Internet throttling and blocking",
                "Social media monitoring",
                "Targeted device surveillance"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableDnsIntegrityCheck = true,
                enableVpnMonitoring = true,
                scanIntervalMinutes = 5
            ),
            advisoryText = "Venezuela operates surveillance infrastructure supplied by Cuba, " +
                "with carrier-level intercept capabilities through state-owned telecoms " +
                "(CANTV/Movilnet). Internet throttling and selective blocking of services " +
                "have been documented. Social media is actively monitored."
        ),
        CountryThreatProfile(
            countryCode = "MM",
            countryName = "Myanmar",
            flagEmoji = "\uD83C\uDDF2\uD83C\uDDF2",
            mcc = "414",
            riskLevel = 4,
            primaryThreats = listOf(
                "Military-controlled surveillance",
                "IMSI catcher / Stingray deployment",
                "Internet shutdowns",
                "Forced SIM registration and tracking",
                "Network downgrades in conflict areas"
            ),
            recommendedSettings = RecommendedSettings(
                detectionSensitivity = "HIGH",
                enableImsiCatcherDetection = true,
                enableNetworkDowngradeAlert = true,
                scanIntervalMinutes = 3
            ),
            advisoryText = "Myanmar's military government operates cellular surveillance " +
                "infrastructure including IMSI catchers. Internet shutdowns are imposed in " +
                "conflict areas and during military operations. Mandatory SIM registration " +
                "enables location tracking. Network quality may be deliberately degraded " +
                "in areas of military activity."
        )
    )

    private val profilesByCode: Map<String, CountryThreatProfile> =
        profiles.associateBy { it.countryCode }

    private val profilesByMcc: Map<String, CountryThreatProfile> =
        profiles.associateBy { it.mcc }

    fun getProfile(countryCode: String): CountryThreatProfile? =
        profilesByCode[countryCode.uppercase()]

    fun getProfileByMcc(mcc: String): CountryThreatProfile? =
        profilesByMcc[mcc]

    fun mccToCountryCode(mcc: String): String? =
        profilesByMcc[mcc]?.countryCode

    fun getAllProfiles(): List<CountryThreatProfile> = profiles

    fun isHighRisk(countryCode: String): Boolean =
        getProfile(countryCode)?.let { it.riskLevel >= 4 } ?: false

    fun getRiskLevel(countryCode: String): Int =
        getProfile(countryCode)?.riskLevel ?: 0
}
