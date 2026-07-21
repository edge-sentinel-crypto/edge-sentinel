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

data class SecurityAdvice(
    val title: String,
    val description: String,
    val priority: AdvicePriority,
    val actionable: Boolean = true
)

enum class AdvicePriority { CRITICAL, HIGH, MEDIUM, LOW }

data class ChecklistItem(
    val id: String,
    val text: String,
    val category: ChecklistCategory,
    val completed: Boolean = false
)

enum class ChecklistCategory { PRE_DEPARTURE, POST_DEPARTURE }

object TravelAdvisor {

    fun getEntryBriefing(profile: CountryThreatProfile): List<SecurityAdvice> {
        val advice = mutableListOf<SecurityAdvice>()

        if (profile.riskLevel >= 4) {
            advice.add(SecurityAdvice(
                title = "High-Risk Environment",
                description = "You are entering a country with significant surveillance " +
                    "capabilities. Exercise heightened caution with all communications.",
                priority = AdvicePriority.CRITICAL
            ))
        }

        if (profile.primaryThreats.any { it.contains("VPN", ignoreCase = true) }) {
            advice.add(SecurityAdvice(
                title = "VPN May Be Blocked or Monitored",
                description = "This country is known to detect and block VPN connections. " +
                    "Ensure your VPN uses obfuscation protocols. Have backup connection methods.",
                priority = AdvicePriority.HIGH
            ))
        }

        if (profile.primaryThreats.any { it.contains("IMSI", ignoreCase = true) ||
                it.contains("Stingray", ignoreCase = true) }) {
            advice.add(SecurityAdvice(
                title = "IMSI Catcher Risk",
                description = "Fake cell towers may be deployed in this area. Edge Sentinel " +
                    "will monitor for anomalous tower behavior. Pay attention to unexpected " +
                    "network changes.",
                priority = AdvicePriority.HIGH
            ))
        }

        if (profile.primaryThreats.any { it.contains("DNS", ignoreCase = true) }) {
            advice.add(SecurityAdvice(
                title = "DNS Integrity Risk",
                description = "DNS queries may be intercepted or manipulated. Use encrypted " +
                    "DNS (DoH/DoT) where possible.",
                priority = AdvicePriority.HIGH
            ))
        }

        if (profile.primaryThreats.any { it.contains("downgrade", ignoreCase = true) ||
                it.contains("2G", ignoreCase = true) }) {
            advice.add(SecurityAdvice(
                title = "Network Downgrade Risk",
                description = "Forced downgrades from LTE/5G to 2G have been documented. " +
                    "2G traffic is more easily intercepted. If your device supports it, " +
                    "disable 2G in network settings.",
                priority = AdvicePriority.HIGH
            ))
        }

        if (profile.primaryThreats.any { it.contains("spyware", ignoreCase = true) ||
                it.contains("Pegasus", ignoreCase = true) }) {
            advice.add(SecurityAdvice(
                title = "Commercial Spyware Risk",
                description = "This country has access to advanced commercial spyware. Keep " +
                    "your device OS and apps fully updated. Avoid clicking unknown links.",
                priority = AdvicePriority.CRITICAL
            ))
        }

        if (profile.primaryThreats.any { it.contains("shutdown", ignoreCase = true) }) {
            advice.add(SecurityAdvice(
                title = "Internet Shutdown Risk",
                description = "This country has imposed internet shutdowns. Download any " +
                    "essential information before it is needed. Consider offline " +
                    "communication methods.",
                priority = AdvicePriority.MEDIUM
            ))
        }

        if (profile.primaryThreats.any { it.contains("MITM", ignoreCase = true) ||
                it.contains("man-in-the-middle", ignoreCase = true) ||
                it.contains("intercept", ignoreCase = true) }) {
            advice.add(SecurityAdvice(
                title = "Traffic Interception Risk",
                description = "Encrypted traffic may be subject to interception. Verify " +
                    "certificate authenticity for sensitive connections. Use certificate " +
                    "pinning where available.",
                priority = AdvicePriority.HIGH
            ))
        }

        return advice.sortedBy { it.priority.ordinal }
    }

    fun getOngoingAdvice(
        profile: CountryThreatProfile?,
        detectedAnomalies: List<String> = emptyList()
    ): List<SecurityAdvice> {
        val advice = mutableListOf<SecurityAdvice>()

        for (anomaly in detectedAnomalies) {
            when {
                anomaly.contains("tower_change") -> advice.add(SecurityAdvice(
                    title = "Unusual Tower Activity",
                    description = "Unexpected cell tower changes detected. This may indicate " +
                        "IMSI catcher activity in your area.",
                    priority = AdvicePriority.HIGH
                ))
                anomaly.contains("downgrade") -> advice.add(SecurityAdvice(
                    title = "Network Downgrade Detected",
                    description = "Your connection was downgraded to a less secure network type. " +
                        "Avoid sensitive communications until restored.",
                    priority = AdvicePriority.CRITICAL
                ))
                anomaly.contains("silent_sms") -> advice.add(SecurityAdvice(
                    title = "Silent SMS Detected",
                    description = "A silent SMS was received. This technique is used for device " +
                        "location tracking. Consider changing your location.",
                    priority = AdvicePriority.HIGH
                ))
            }
        }

        return advice.sortedBy { it.priority.ordinal }
    }

    fun getPreDepartureChecklist(): List<ChecklistItem> = listOf(
        ChecklistItem(
            id = "backup_data",
            text = "Back up device data to a secure location",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "enable_vpn",
            text = "Install and configure a trusted VPN",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "update_os",
            text = "Update device OS and all apps to latest versions",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "review_permissions",
            text = "Review and restrict app permissions",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "review_accounts",
            text = "Review connected accounts and active sessions",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "enable_2fa",
            text = "Enable 2FA on all critical accounts",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "disable_2g",
            text = "Disable 2G connectivity if device supports it",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "disable_bluetooth",
            text = "Disable Bluetooth and NFC when not in use",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "encrypted_dns",
            text = "Configure encrypted DNS (DoH/DoT)",
            category = ChecklistCategory.PRE_DEPARTURE
        ),
        ChecklistItem(
            id = "edge_sentinel_config",
            text = "Configure Edge Sentinel monitoring for destination",
            category = ChecklistCategory.PRE_DEPARTURE
        )
    )

    fun getPostDepartureChecklist(): List<ChecklistItem> = listOf(
        ChecklistItem(
            id = "change_passwords",
            text = "Change passwords for accounts used during travel",
            category = ChecklistCategory.POST_DEPARTURE
        ),
        ChecklistItem(
            id = "review_sessions",
            text = "Review and revoke any active sessions created during travel",
            category = ChecklistCategory.POST_DEPARTURE
        ),
        ChecklistItem(
            id = "review_alerts",
            text = "Review Edge Sentinel alerts from the trip",
            category = ChecklistCategory.POST_DEPARTURE
        ),
        ChecklistItem(
            id = "export_data",
            text = "Export travel security data for analysis",
            category = ChecklistCategory.POST_DEPARTURE
        ),
        ChecklistItem(
            id = "wipe_travel_data",
            text = "Wipe travel data from Edge Sentinel",
            category = ChecklistCategory.POST_DEPARTURE
        ),
        ChecklistItem(
            id = "scan_device",
            text = "Run a full device security scan",
            category = ChecklistCategory.POST_DEPARTURE
        ),
        ChecklistItem(
            id = "check_installed_apps",
            text = "Check for unfamiliar apps installed during travel",
            category = ChecklistCategory.POST_DEPARTURE
        ),
        ChecklistItem(
            id = "review_certificates",
            text = "Check for unauthorized certificates added to device",
            category = ChecklistCategory.POST_DEPARTURE
        )
    )
}
