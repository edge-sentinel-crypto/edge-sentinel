/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build

object NotificationChannels {

    const val CRITICAL = "edge_sentinel_critical"
    const val WARNING = "edge_sentinel_warning"
    const val MONITORING = "edge_sentinel_monitoring"
    const val MESH = "edge_sentinel_mesh"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val critical = NotificationChannel(
            CRITICAL,
            "Critical Threat Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "High-severity threat detections requiring immediate attention"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 200, 250, 200, 250)
            enableLights(true)
            lightColor = Color.RED
            setShowBadge(true)
        }

        val warning = NotificationChannel(
            WARNING,
            "Warning Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Suspicious activity that may require investigation"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            enableLights(true)
            lightColor = Color.YELLOW
            setShowBadge(true)
        }

        val monitoring = NotificationChannel(
            MONITORING,
            "Monitoring Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification showing monitoring status"
            setShowBadge(false)
        }

        val mesh = NotificationChannel(
            MESH,
            "Mesh Peer Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts from nearby Edge Sentinel mesh peers"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 100, 150)
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(critical, warning, monitoring, mesh))
    }
}
