/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.fusion

import com.bp22intel.edgesentinel.domain.model.FusedThreatLevel
import com.bp22intel.edgesentinel.domain.model.SensorCategory

object FusionRuleSet {

    private val defaultRules = listOf(
        // CRITICAL: Coordinated attack — cell downgrade + WiFi evil twin + VPN drop
        FusionRule(
            id = "coordinated_attack",
            name = "Coordinated Interception Attack",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.CELLULAR, "network_downgrade"),
                TriggerCondition(SensorCategory.WIFI, "evil_twin"),
                TriggerCondition(SensorCategory.NETWORK, "vpn_drop")
            ),
            resultingThreatLevel = FusedThreatLevel.CRITICAL,
            confidenceBoost = 0.4,
            narrativeTemplate = "Coordinated attack detected: your cellular connection was downgraded while a rogue WiFi access point appeared and your VPN was disrupted. This is a multi-vector interception attempt. {signals}"
        ),

        // CRITICAL: Targeted IMSI catcher — silent SMS + new tower + signal spike
        FusionRule(
            id = "imsi_catcher_targeted",
            name = "Targeted IMSI Catcher Attack",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.CELLULAR, "silent_sms"),
                TriggerCondition(SensorCategory.CELLULAR, "new_tower"),
                TriggerCondition(SensorCategory.CELLULAR, "signal_spike")
            ),
            resultingThreatLevel = FusedThreatLevel.CRITICAL,
            confidenceBoost = 0.5,
            narrativeTemplate = "Targeted IMSI catcher detected: a silent SMS was received, a new cell tower appeared, and signal strength spiked abnormally. You are likely being specifically targeted for interception. {signals}"
        ),

        // HIGH: New cell tower + new rogue AP at same time
        FusionRule(
            id = "dual_rogue_infrastructure",
            name = "Dual Rogue Infrastructure",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.CELLULAR, "new_tower"),
                TriggerCondition(SensorCategory.WIFI, "rogue_ap")
            ),
            requireSameLocation = true,
            resultingThreatLevel = FusedThreatLevel.DANGEROUS,
            confidenceBoost = 0.3,
            narrativeTemplate = "A new cell tower and a rogue WiFi access point appeared simultaneously in your area. This suggests deployed interception infrastructure. {signals}"
        ),

        // HIGH: DNS hijack + TLS MITM — active network interception
        FusionRule(
            id = "active_network_interception",
            name = "Active Network Interception",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.NETWORK, "dns_hijack"),
                TriggerCondition(SensorCategory.NETWORK, "tls_mitm")
            ),
            resultingThreatLevel = FusedThreatLevel.DANGEROUS,
            confidenceBoost = 0.4,
            narrativeTemplate = "Your DNS queries are being hijacked and TLS connections are being intercepted. Your network traffic is actively being monitored. Switch to cellular data and a trusted VPN immediately. {signals}"
        ),

        // MEDIUM: BLE tracker + cell tower change — physical + electronic surveillance
        FusionRule(
            id = "combined_surveillance",
            name = "Combined Physical and Electronic Surveillance",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.BLUETOOTH, "tracker_detected"),
                TriggerCondition(SensorCategory.CELLULAR, "tower_change")
            ),
            resultingThreatLevel = FusedThreatLevel.ELEVATED,
            confidenceBoost = 0.2,
            narrativeTemplate = "A Bluetooth tracker is following you while your cellular environment shows anomalies. You may be under combined physical and electronic surveillance. {signals}"
        ),

        // LOW standalone rules (single-sensor, context-dependent)
        FusionRule(
            id = "cell_downgrade_solo",
            name = "Cellular Network Downgrade",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.CELLULAR, "network_downgrade")
            ),
            resultingThreatLevel = FusedThreatLevel.ELEVATED,
            confidenceBoost = 0.0,
            narrativeTemplate = "Your phone was forced to a lower-generation network (e.g. 2G). This could indicate a downgrade attack or normal roaming. Monitor for additional indicators. {signals}"
        ),

        // WiFi evil twin alone
        FusionRule(
            id = "evil_twin_solo",
            name = "WiFi Evil Twin Detected",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.WIFI, "evil_twin")
            ),
            resultingThreatLevel = FusedThreatLevel.ELEVATED,
            confidenceBoost = 0.1,
            narrativeTemplate = "A WiFi access point mimicking a known network was detected nearby. Avoid connecting to untrusted WiFi networks. {signals}"
        ),

        // VPN drop alone
        FusionRule(
            id = "vpn_drop_solo",
            name = "VPN Connection Dropped",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.NETWORK, "vpn_drop")
            ),
            resultingThreatLevel = FusedThreatLevel.ELEVATED,
            confidenceBoost = 0.0,
            narrativeTemplate = "Your VPN connection dropped unexpectedly. This may expose your traffic to interception. Re-establish your VPN connection. {signals}"
        ),

        // Fake BTS standalone
        FusionRule(
            id = "fake_bts_solo",
            name = "Fake Base Station Detected",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.CELLULAR, "fake_bts")
            ),
            resultingThreatLevel = FusedThreatLevel.DANGEROUS,
            confidenceBoost = 0.2,
            narrativeTemplate = "A suspected fake base station (IMSI catcher) was detected. Your cellular communications may be intercepted. {signals}"
        ),

        // BLE tracker alone
        FusionRule(
            id = "ble_tracker_solo",
            name = "Bluetooth Tracker Detected",
            triggerConditions = listOf(
                TriggerCondition(SensorCategory.BLUETOOTH, "tracker_detected")
            ),
            resultingThreatLevel = FusedThreatLevel.ELEVATED,
            confidenceBoost = 0.1,
            narrativeTemplate = "An unknown Bluetooth tracker has been detected following your location. Check your belongings for unwanted tracking devices. {signals}"
        )
    )

    private val customRules = mutableListOf<FusionRule>()

    fun getAllRules(): List<FusionRule> = defaultRules + customRules

    fun addCustomRule(rule: FusionRule) {
        customRules.add(rule)
    }

    fun removeCustomRule(ruleId: String) {
        customRules.removeAll { it.id == ruleId }
    }

    fun clearCustomRules() {
        customRules.clear()
    }
}
