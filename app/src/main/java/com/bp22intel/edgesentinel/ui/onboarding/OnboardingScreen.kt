/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusSuspicious
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import com.bp22intel.edgesentinel.ui.theme.TextTertiary

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val isPermissionPage: Boolean = false,
    val isBatteryPage: Boolean = false
)

private data class PermissionItem(
    val icon: ImageVector,
    val name: String,
    val reason: String
)

private val permissionItems = listOf(
    PermissionItem(
        icon = Icons.Default.LocationOn,
        name = "Location",
        reason = "Required to identify cell towers near you. Android ties cell tower data " +
            "to location permissions — without this, Edge Sentinel cannot see which " +
            "towers your phone is connected to or detect rogue base stations."
    ),
    PermissionItem(
        icon = Icons.Default.PhoneAndroid,
        name = "Phone State",
        reason = "Reads your network connection type (LTE, 5G, 3G) and monitors for " +
            "forced downgrades. This is how Edge Sentinel detects when an attacker " +
            "forces your phone onto a weaker, interceptable network."
    ),
    PermissionItem(
        icon = Icons.Default.Sms,
        name = "SMS",
        reason = "Detects silent SMS (Type-0) messages — invisible pings used by " +
            "surveillance systems to track your location without your knowledge. " +
            "Edge Sentinel never reads your personal messages."
    ),
    PermissionItem(
        icon = Icons.Default.Notifications,
        name = "Notifications",
        reason = "Sends you real-time alerts when threats are detected. Without this, " +
            "Edge Sentinel can still monitor but cannot warn you of active threats."
    )
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.Default.Shield,
        title = "Cellular Threat Detection",
        description = "Edge Sentinel monitors your cellular environment in real-time, " +
            "detecting fake base stations (IMSI catchers), network downgrades, silent SMS, " +
            "and other surveillance threats."
    ),
    OnboardingPage(
        icon = Icons.Default.Lock,
        title = "Why These Permissions?",
        description = "Every permission has a specific purpose. No data ever leaves your device.",
        isPermissionPage = true
    ),
    OnboardingPage(
        icon = Icons.Default.Notifications,
        title = "Understanding Alerts",
        description = "GREEN means all clear — no threats detected. " +
            "YELLOW indicates suspicious activity that may need investigation. " +
            "RED signals an active threat requiring immediate attention. " +
            "Tap any alert for detailed analysis and recommended actions."
    ),
    OnboardingPage(
        icon = Icons.Default.BatteryFull,
        title = "Continuous Protection",
        description = "Edge Sentinel needs to run continuously to detect threats. " +
            "Without this, Android will stop monitoring when your screen is off.",
        isBatteryPage = true
    ),
    OnboardingPage(
        icon = Icons.Default.GppGood,
        title = "You're Protected",
        description = "Edge Sentinel runs quietly in the background, scanning your cellular " +
            "environment at regular intervals. You'll be notified immediately when " +
            "suspicious activity is detected. Stay safe out there."
    )
)

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    onOnboardingComplete: () -> Unit
) {
    val isOnboardingComplete by viewModel.isOnboardingComplete.collectAsState()

    // Skip onboarding if already completed
    LaunchedEffect(isOnboardingComplete) {
        if (isOnboardingComplete) {
            onOnboardingComplete()
        }
    }

    // Don't render anything while checking DataStore
    if (isOnboardingComplete) return

    var currentPage by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var batteryOptExempt by remember { mutableStateOf(isBatteryOptimizationExempt(context)) }

    // Track permission denial state
    var deniedCritical by remember { mutableStateOf<List<String>>(emptyList()) }
    var deniedNiceToHave by remember { mutableStateOf<List<String>>(emptyList()) }
    var permissionsRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsRequested = true
        val criticalPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        val niceToHavePermissions = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        deniedCritical = criticalPermissions.filter { results[it] != true }
        deniedNiceToHave = niceToHavePermissions.filter { results[it] != true }

        // Always advance — but warning card will show on next page if permissions denied
        currentPage++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.1f))

        // Page content with animation
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
            },
            modifier = Modifier.weight(0.6f),
            label = "onboarding_page"
        ) { page ->
            if (pages[page].isPermissionPage) {
                // Detailed permission explanation page — scrollable
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Icon(
                        imageVector = pages[page].icon,
                        contentDescription = null,
                        tint = StatusClear,
                        modifier = Modifier.size(40.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = pages[page].title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = pages[page].description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    permissionItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = StatusClear,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(
                                modifier = Modifier.padding(start = 10.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = item.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            } else {
                // Standard page
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Icon(
                        imageVector = pages[page].icon,
                        contentDescription = null,
                        tint = StatusClear,
                        modifier = Modifier.size(80.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        text = pages[page].title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = pages[page].description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    // Show permission warning card after the permissions page
                    if (permissionsRequested && page == 2 && (deniedCritical.isNotEmpty() || deniedNiceToHave.isNotEmpty())) {
                        Spacer(modifier = Modifier.height(20.dp))
                        PermissionWarningCard(
                            deniedCritical = deniedCritical,
                            deniedNiceToHave = deniedNiceToHave
                        )
                    }
                }
            }
        }

        // Page indicators
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 24.dp)
        ) {
            pages.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == currentPage) StatusClear else TextTertiary
                        )
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.05f))

        // Buttons
        if (pages[currentPage].isBatteryPage) {
            // Battery optimization page
            Button(
                onClick = {
                    if (!batteryOptExempt) {
                        requestBatteryOptimizationExemption(context)
                    }
                    batteryOptExempt = isBatteryOptimizationExempt(context)
                    currentPage++
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (batteryOptExempt) StatusClear else AccentBlue
                )
            ) {
                Text(
                    text = if (batteryOptExempt) "Already Exempt ✓" else "Disable Battery Optimization",
                    color = BackgroundPrimary
                )
            }
        } else if (currentPage == 1) {
            // Permission request page
            Button(
                onClick = {
                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.RECEIVE_SMS
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                    // Don't advance here — the launcher callback handles it
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text(text = "Grant Permissions", color = BackgroundPrimary)
            }
        } else if (currentPage == pages.lastIndex) {
            // Last page
            Button(
                onClick = {
                    viewModel.completeOnboarding()
                    onOnboardingComplete()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = StatusClear)
            ) {
                Text(text = "Get Started", color = BackgroundPrimary, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { currentPage++ },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Surface)
            ) {
                Text(text = "Next", color = TextPrimary)
            }
        }

        if (currentPage < pages.lastIndex) {
            TextButton(onClick = {
                viewModel.completeOnboarding()
                onOnboardingComplete()
            }) {
                Text(text = "Skip", color = TextTertiary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun isBatteryOptimizationExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestBatteryOptimizationExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

private fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
    Manifest.permission.READ_PHONE_STATE -> "Phone State"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    Manifest.permission.RECEIVE_SMS -> "SMS"
    else -> permission.substringAfterLast('.')
}

private fun permissionImpact(permission: String): String = when (permission) {
    Manifest.permission.ACCESS_FINE_LOCATION ->
        "Cannot detect cell towers or rogue base stations"
    Manifest.permission.READ_PHONE_STATE ->
        "Cannot detect network downgrades or connection type changes"
    Manifest.permission.POST_NOTIFICATIONS ->
        "Will monitor silently — no real-time threat alerts"
    Manifest.permission.RECEIVE_SMS ->
        "Cannot detect silent SMS / Type-0 pings used for location tracking"
    else -> "Reduced detection capability"
}

@Composable
private fun PermissionWarningCard(
    deniedCritical: List<String>,
    deniedNiceToHave: List<String>
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (deniedCritical.isNotEmpty())
                StatusSuspicious.copy(alpha = 0.15f)
            else
                AccentBlue.copy(alpha = 0.10f)
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (deniedCritical.isNotEmpty()) StatusSuspicious else AccentBlue,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (deniedCritical.isNotEmpty())
                        "Limited Protection"
                    else
                        "Some Features Limited",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (deniedCritical.isNotEmpty()) StatusSuspicious else AccentBlue
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (deniedCritical.isNotEmpty()) {
                Text(
                    text = "Critical permissions were denied. Core detection will not work:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                deniedCritical.forEach { perm ->
                    Text(
                        text = "• ${permissionLabel(perm)}: ${permissionImpact(perm)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "You can grant these later in Settings → App Permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }

            if (deniedNiceToHave.isNotEmpty()) {
                if (deniedCritical.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Optional permissions not granted:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                deniedNiceToHave.forEach { perm ->
                    Text(
                        text = "• ${permissionLabel(perm)}: ${permissionImpact(perm)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
            }
        }
    }
}
