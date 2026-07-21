/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bp22intel.edgesentinel.service.MonitoringService
import com.bp22intel.edgesentinel.ui.navigation.EdgeSentinelNavHost
import com.bp22intel.edgesentinel.ui.theme.EdgeSentinelTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Auto-start monitoring once permissions are granted
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            MonitoringService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request all required permissions on first launch
        requestMissingPermissions()

        // Auto-start monitoring if permissions already granted
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation && !MonitoringService.isRunning.value) {
            MonitoringService.start(this)
        }

        // Check if launched from a notification deep link
        val deepLinkAlertId = parseAlertIdFromIntent(intent)

        setContent {
            EdgeSentinelTheme {
                EdgeSentinelNavHost(initialAlertId = deepLinkAlertId)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Handle deep link when app is already running
        // The NavHost will pick this up via the saved state
        setIntent(intent)
    }

    private fun parseAlertIdFromIntent(intent: android.content.Intent?): Long? {
        val uri = intent?.data ?: return null
        // Expected: edgesentinel://alert_detail/{alertId}
        if (uri.scheme == "edgesentinel" && uri.host == "alert_detail") {
            return uri.lastPathSegment?.toLongOrNull()
        }
        return null
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        }
    }
}
