/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.detection.network.NetworkIntegritySnapshot
import com.bp22intel.edgesentinel.detection.network.VpnStatusResult
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.StatusThreat
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun NetworkIntegrityScreen(
    onBack: () -> Unit = {},
    viewModel: NetworkIntegrityViewModel = hiltViewModel()
) {
    val vpnStatus by viewModel.vpnStatus.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val history by viewModel.checkHistory.collectAsState()
    val isChecking by viewModel.isChecking.collectAsState()
    val trustedMitmServices by viewModel.trustedMitmServices.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Integrity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundPrimary,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        },
        containerColor = BackgroundPrimary
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Trust Score
        item {
            TrustScoreCard(snapshot)
        }

        // Run Check Button
        item {
            Button(
                onClick = { viewModel.runFullCheck() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isChecking,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Running Checks...", color = TextPrimary)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Check Now", color = TextPrimary)
                }
            }
        }

        // VPN Status
        item { SectionHeader(title = "VPN Status") }
        item { VpnStatusCard(vpnStatus) }

        // DNS Integrity
        item { SectionHeader(title = "DNS Integrity") }
        item { DnsStatusCard(snapshot) }

        // TLS Integrity
        item { SectionHeader(title = "TLS Integrity") }
        item {
            TlsStatusCard(
                snapshot = snapshot,
                trustedMitmServices = trustedMitmServices,
                onTrustService = { viewModel.trustMitmService(it) },
                onUntrustService = { viewModel.untrustMitmService(it) }
            )
        }

        // Captive Portal
        item { SectionHeader(title = "Captive Portal") }
        item { CaptivePortalCard(snapshot) }

        // Check History
        if (history.isNotEmpty()) {
            item { SectionHeader(title = "Check History") }
            items(history.take(20)) { entry ->
                HistoryRow(entry)
            }
        }
    }
    }
}

@Composable
private fun TrustScoreCard(snapshot: NetworkIntegritySnapshot?) {
    val score = snapshot?.trustScore ?: 100
    val scoreColor = when {
        score >= 80 -> StatusClear
        score >= 50 -> StatusSuspicious
        else -> StatusThreat
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Network Trust Score",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "$score",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = scoreColor
            )
            Text(
                text = when {
                    score >= 80 -> "Network appears clean"
                    score >= 50 -> "Potential issues detected"
                    else -> "Active threats detected"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scoreColor
            )
            if (snapshot != null && snapshot.threats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                snapshot.threats.forEach { threat ->
                    Text(
                        text = threat.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusThreat
                    )
                }
            }
        }
    }
}

@Composable
private fun VpnStatusCard(vpnStatus: VpnStatusResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (vpnStatus.isVpnActive) StatusClear else StatusThreat)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (vpnStatus.isVpnActive) "VPN Connected" else "VPN Disconnected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            if (vpnStatus.isVpnActive && vpnStatus.vpnUptimeMs > 0) {
                StatusRow("Uptime", formatDuration(vpnStatus.vpnUptimeMs))
            }
            if (vpnStatus.vpnDropDetected) {
                StatusRow("Status", "VPN DROP DETECTED", StatusThreat)
            }
            if (vpnStatus.bypassLeakDetected) {
                StatusRow("Leak", vpnStatus.leakDetails ?: "Bypass detected", StatusSuspicious)
            }
            vpnStatus.lastDropTimestamp?.let { ts ->
                StatusRow("Last Drop", formatTimestamp(ts))
            }
        }
    }
}

@Composable
private fun DnsStatusCard(snapshot: NetworkIntegritySnapshot?) {
    val dns = snapshot?.dnsIntegrity
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (dns == null) {
                Text(
                    text = "No DNS check performed yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                StatusIcon(dns.overallClean)
                StatusRow("Last Check", formatTimestamp(dns.timestamp))
                StatusRow("Domains Checked", "${dns.domainResults.size}")
                if (dns.hijackedDomains.isNotEmpty()) {
                    StatusRow(
                        "Hijacked",
                        dns.hijackedDomains.joinToString(", "),
                        StatusThreat
                    )
                }
                if (dns.nxdomainHijacked) {
                    StatusRow("NXDOMAIN", "Hijacked — failed lookups redirected", StatusSuspicious)
                }
            }
        }
    }
}

@Composable
private fun TlsStatusCard(
    snapshot: NetworkIntegritySnapshot?,
    trustedMitmServices: Set<String> = emptySet(),
    onTrustService: (String) -> Unit = {},
    onUntrustService: (String) -> Unit = {}
) {
    val tls = snapshot?.tlsIntegrity
    var showExplainer by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tls == null) {
                Text(
                    text = "No TLS check performed yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                // Filter out trusted services from MITM list
                val untrustedMitm = tls.mitmEndpoints.filter { it !in trustedMitmServices }
                val trustedMitm = tls.mitmEndpoints.filter { it in trustedMitmServices }

                StatusIcon(untrustedMitm.isEmpty())
                StatusRow("Last Check", formatTimestamp(tls.timestamp))
                StatusRow("Endpoints Checked", "${tls.endpointResults.size}")

                // Untrusted MITM endpoints (shown as threats)
                if (untrustedMitm.isNotEmpty()) {
                    StatusRow(
                        "MITM Detected",
                        untrustedMitm.joinToString(", "),
                        StatusThreat
                    )

                    // "What's this?" button
                    TextButton(onClick = { showExplainer = !showExplainer }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showExplainer) "Hide explanation" else "What's this?",
                            color = AccentBlue,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (showExplainer) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = AccentBlue.copy(alpha = 0.1f)
                            ),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "TLS MITM (Man-in-the-Middle) means someone is intercepting your encrypted connections to these services.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "This can be malicious (someone spying on your traffic) OR legitimate:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "\u2022 Corporate security proxy / firewall\n" +
                                        "\u2022 Antivirus with HTTPS scanning\n" +
                                        "\u2022 Parental control software\n" +
                                        "\u2022 Network security appliance (e.g. Fortinet, Palo Alto)\n" +
                                        "\u2022 DNS-level security service (e.g. Cloudflare Gateway)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "If you recognize this as your own security service, tap 'Trust' below. If not, your traffic may be compromised.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    // Trust buttons for each untrusted MITM endpoint
                    untrustedMitm.forEach { endpoint ->
                        OutlinedButton(
                            onClick = { onTrustService(endpoint) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Trust $endpoint",
                                color = AccentBlue,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // Trusted MITM endpoints (shown as acknowledged)
                if (trustedMitm.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = StatusClear,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Trusted: ${trustedMitm.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusClear
                        )
                    }
                    trustedMitm.forEach { endpoint ->
                        TextButton(onClick = { onUntrustService(endpoint) }) {
                            Text(
                                text = "Remove trust for $endpoint",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                tls.endpointResults.forEach { result ->
                    if (result.error != null) {
                        StatusRow(result.hostname, result.error, TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptivePortalCard(snapshot: NetworkIntegritySnapshot?) {
    val portal = snapshot?.captivePortal
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (portal == null) {
                Text(
                    text = "No captive portal check performed yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                StatusIcon(!portal.captivePortalDetected)
                StatusRow("Last Check", formatTimestamp(portal.timestamp))
                if (portal.captivePortalDetected) {
                    StatusRow("Portal Detected", "Yes", StatusSuspicious)
                    portal.portalUrl?.let { StatusRow("Portal URL", it) }
                    if (portal.jsInjectionDetected) {
                        StatusRow("JS Injection", "DETECTED", StatusThreat)
                    }
                } else {
                    StatusRow("Status", "No captive portal", StatusClear)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(snapshot: NetworkIntegritySnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTimestamp(snapshot.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Score: ${snapshot.trustScore}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = when {
                    snapshot.trustScore >= 80 -> StatusClear
                    snapshot.trustScore >= 50 -> StatusSuspicious
                    else -> StatusThreat
                }
            )
            if (snapshot.threats.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${snapshot.threats.size} threat(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusThreat
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(isClean: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isClean) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isClean) StatusClear else StatusThreat,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isClean) "Clean" else "Issues Detected",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isClean) StatusClear else StatusThreat
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("MMM dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
