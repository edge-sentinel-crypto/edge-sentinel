/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.components

/**
 * Plain-English glossary for technical terms shown in the app.
 * Each entry maps a technical term to a simple explanation anyone can understand.
 */
object ThreatGlossary {

    data class GlossaryEntry(
        val term: String,
        val simple: String,
        val detail: String
    )

    private val entries = listOf(
        // Cellular terms
        GlossaryEntry(
            "LAC",
            "Location Area Code — a zone ID your carrier assigns to groups of cell towers",
            "Your phone uses the LAC to know which area it's in. If the LAC keeps switching back and forth rapidly (oscillation), it could mean someone is forcing your phone to re-register with a fake tower to track your location."
        ),
        GlossaryEntry(
            "LAC Oscillation",
            "Your phone is rapidly jumping between different tower zones",
            "Normally your phone stays in one zone unless you're moving. Rapid back-and-forth switching can mean an attacker is forcing your phone to re-register, which reveals your location. Think of it like someone repeatedly ringing your doorbell to confirm you're home."
        ),
        GlossaryEntry(
            "CID",
            "Cell ID — the unique identifier for a specific cell tower",
            "Every cell tower has a unique number. Edge Sentinel tracks these to spot when a new, unknown tower appears — which could be a fake tower (stingray) trying to intercept your calls."
        ),
        GlossaryEntry(
            "TAC",
            "Tracking Area Code — similar to LAC but for 4G/5G networks",
            "Modern networks use TAC instead of LAC, but the concept is the same — it identifies which geographic zone your phone is registered to."
        ),
        GlossaryEntry(
            "MCC",
            "Mobile Country Code — identifies which country a network belongs to",
            "For example, 310 = United States, 460 = China. If your phone connects to a tower with the wrong country code while you're in the US, that's suspicious."
        ),
        GlossaryEntry(
            "MNC",
            "Mobile Network Code — identifies your specific carrier",
            "Combined with MCC, this tells you exactly which carrier operates a tower. A tower claiming to be AT&T but with the wrong MNC could be fake."
        ),
        GlossaryEntry(
            "IMSI",
            "International Mobile Subscriber Identity — your SIM card's unique ID",
            "This is the number that identifies YOU on the cellular network. An 'IMSI catcher' (stingray) is a device that tricks your phone into revealing this ID, letting someone track you."
        ),
        GlossaryEntry(
            "IMSI Catcher",
            "A fake cell tower that tricks phones into connecting to it",
            "Also called a 'stingray.' It pretends to be a real tower so your phone connects to it, allowing the operator to track your location, intercept calls, or read texts. Used by law enforcement and criminals alike."
        ),
        GlossaryEntry(
            "Fake BTS",
            "A fake cell tower (Base Transceiver Station) pretending to be real",
            "Same concept as an IMSI catcher. It broadcasts signals that look like a legitimate tower, tricking nearby phones into connecting. Once connected, the attacker can monitor your communications."
        ),
        GlossaryEntry(
            "Network Downgrade",
            "Your phone was forced from 4G/5G down to an older, less secure network",
            "Newer networks (4G, 5G) have stronger encryption. Attackers can force your phone to use 2G/3G, which has weaker or no encryption, making it easier to eavesdrop on your calls and texts."
        ),
        GlossaryEntry(
            "Silent SMS",
            "An invisible text message used to ping your phone's location",
            "A Type-0 SMS that your phone receives and acknowledges but never shows you. It's used to confirm your phone is on and determine which cell tower you're connected to — essentially a silent location ping."
        ),
        GlossaryEntry(
            "Cipher Anomaly",
            "The encryption on your connection changed unexpectedly",
            "Your phone and the tower agree on how to encrypt your calls. If the encryption suddenly weakens or disappears, someone may be tampering with your connection to listen in."
        ),
        GlossaryEntry(
            "Null Cipher",
            "Your phone connection has NO encryption at all",
            "This means your calls and texts are being sent in plain text that anyone with the right equipment can read. This should almost never happen on modern networks."
        ),
        GlossaryEntry(
            "ARFCN",
            "Absolute Radio Frequency Channel Number — the specific radio channel a tower uses",
            "Each tower broadcasts on specific frequencies. Unexpected channel changes can indicate your phone is being redirected to a fake tower."
        ),
        GlossaryEntry(
            "PCI",
            "Physical Cell Identity — a tower's local identifier in 4G/5G",
            "Each tower in an area has a unique PCI. Edge Sentinel uses this to track towers and detect when new, potentially fake ones appear."
        ),
        GlossaryEntry(
            "Handover",
            "Your phone switching from one cell tower to another",
            "This happens naturally as you move. But unusual handover patterns — like being forced to a distant tower when a closer one is available — can indicate an attack."
        ),
        GlossaryEntry(
            "TMSI",
            "Temporary Mobile Subscriber Identity — a short-term ID assigned by the network",
            "Instead of broadcasting your permanent IMSI, the network gives you a temporary ID for privacy. If your TMSI never changes, the network (or an attacker) can track you more easily."
        ),

        // WiFi terms
        GlossaryEntry(
            "Evil Twin",
            "A fake WiFi network that copies a real one's name to trick you into connecting",
            "An attacker creates a hotspot with the same name as a trusted network (like your office WiFi). When you connect, they can see all your internet traffic."
        ),
        GlossaryEntry(
            "SSID",
            "The name of a WiFi network (what you see in your WiFi list)",
            "Service Set Identifier — the human-readable name like 'HomeWiFi' or 'Starbucks'. Multiple access points can share the same SSID, which is how evil twin attacks work."
        ),
        GlossaryEntry(
            "BSSID",
            "The unique hardware address of a specific WiFi access point",
            "Unlike the SSID (name), the BSSID is unique to each physical router/access point. Edge Sentinel uses this to tell apart real and fake networks even when they share the same name."
        ),
        GlossaryEntry(
            "Deauth Attack",
            "Someone is forcibly disconnecting you from WiFi",
            "An attacker sends special packets that kick you off your WiFi network. Often used alongside an evil twin — they disconnect you from the real network so you connect to their fake one."
        ),
        GlossaryEntry(
            "Probe Request",
            "Your phone calling out for WiFi networks it remembers",
            "When WiFi is on, your phone broadcasts the names of networks it's connected to before. This leaks information about places you've been — your home, office, hotels, etc."
        ),
        GlossaryEntry(
            "Karma Attack",
            "A fake access point that pretends to be every network your phone asks for",
            "Your phone probes for 'HomeWiFi' and the attacker's device says 'Yes, I'm HomeWiFi!' — for every single network name. Your phone connects thinking it found a trusted network."
        ),
        GlossaryEntry(
            "Rogue AP",
            "An unauthorized access point that appeared in your area",
            "A new, unknown WiFi access point broadcasting at unusually high power. Could be someone setting up a fake hotspot to intercept traffic from nearby devices."
        ),

        // Bluetooth terms
        GlossaryEntry(
            "BLE Tracking",
            "Someone may be using a Bluetooth tracker to follow your location",
            "Devices like AirTags, SmartTags, or Tile trackers use Bluetooth Low Energy (BLE) to report their location. If one is following you without your knowledge, it could be planted on you or your belongings."
        ),

        // Network terms
        GlossaryEntry(
            "DNS Integrity",
            "Whether your internet address lookups are being tampered with",
            "DNS translates website names (like google.com) into IP addresses. If someone tampers with this, they can redirect you to fake websites that look real — to steal passwords or install malware."
        ),
        GlossaryEntry(
            "TLS",
            "The encryption that secures your web browsing (the lock icon)",
            "Transport Layer Security — what makes HTTPS work. Edge Sentinel checks that your TLS connections aren't being intercepted or downgraded."
        ),

        // 5G terms
        GlossaryEntry(
            "NR Anomaly",
            "Something suspicious detected on the 5G New Radio connection",
            "5G NR (New Radio) is the latest cellular technology. Even 5G can be attacked — forced downgrades, fake gNodeB towers, or timing anomalies can indicate someone targeting your 5G connection."
        ),

        // Baseline terms
        GlossaryEntry(
            "RF Baseline",
            "The normal radio environment Edge Sentinel has learned for your location",
            "Edge Sentinel learns what towers, WiFi networks, and signal levels are normal for places you frequent. When something new or unusual appears, it stands out against this baseline."
        ),
        GlossaryEntry(
            "Signal Anomaly",
            "The signal strength is unusual compared to what's normal here",
            "If a tower you've seen before is suddenly much stronger or weaker than usual, it could mean a new transmitter appeared nearby — potentially a fake tower boosting its signal to attract your phone."
        )
    )

    private val lookupMap: Map<String, GlossaryEntry> = entries.associateBy { it.term.lowercase() }

    /**
     * Look up a glossary entry by term (case-insensitive).
     * Also tries partial matches for compound terms like "LAC oscillation".
     */
    fun lookup(term: String): GlossaryEntry? {
        val lower = term.lowercase().trim()
        return lookupMap[lower]
            ?: entries.firstOrNull { lower.contains(it.term.lowercase()) }
            ?: entries.firstOrNull { it.term.lowercase().contains(lower) }
    }

    /**
     * Find all glossary entries that are relevant to a given text string.
     * Scans text for known terms and returns matching entries.
     */
    fun findRelevantTerms(text: String): List<GlossaryEntry> {
        val lower = text.lowercase()
        return entries.filter { entry ->
            lower.contains(entry.term.lowercase())
        }.distinctBy { it.term }
    }

    /** All entries for a full glossary screen */
    fun allEntries(): List<GlossaryEntry> = entries.sortedBy { it.term }
}
