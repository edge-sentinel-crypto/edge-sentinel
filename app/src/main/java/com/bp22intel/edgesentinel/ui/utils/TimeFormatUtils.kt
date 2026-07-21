/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shared time / duration formatting utilities.
 *
 * Consolidates the many private `formatTimestamp()`, `formatRelativeTime()`,
 * and `formatDuration()` copies that were scattered across UI files.
 */
object TimeFormatUtils {

    // ── Relative time ────────────────────────────────────────────────

    /**
     * Human-friendly relative time string ("just now", "5 min ago", "2 days ago", …).
     * Previously duplicated in AlertCard.
     */
    fun formatRelativeTime(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = now - timestampMs
        val diffSec = diffMs / 1000
        val diffMin = diffSec / 60
        val diffHour = diffMin / 60
        val diffDay = diffHour / 24

        return when {
            diffSec < 60 -> "just now"
            diffMin < 60 -> "${diffMin} min ago"
            diffHour < 24 -> "${diffHour} hour${if (diffHour != 1L) "s" else ""} ago"
            diffDay < 7 -> "${diffDay} day${if (diffDay != 1L) "s" else ""} ago"
            else -> "${diffDay / 7} week${if (diffDay / 7 != 1L) "s" else ""} ago"
        }
    }

    /**
     * Compact relative time ("5s ago", "3m ago", "2h ago", "1d ago").
     * Previously used in ThreatMapScreen.
     */
    fun formatRelativeTimeCompact(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - timestampMs) / 1000

        return when {
            diff < 60 -> "${diff}s ago"
            diff < 3600 -> "${diff / 60}m ago"
            diff < 86400 -> "${diff / 3600}h ago"
            else -> "${diff / 86400}d ago"
        }
    }

    // ── Timestamps ───────────────────────────────────────────────────

    /**
     * Full timestamp: "MMM dd HH:mm:ss" (e.g. "Apr 09 18:05:23").
     * Previously in NetworkIntegrityScreen.
     */
    fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("MMM dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * Short timestamp: "HH:mm" (e.g. "18:05").
     * Previously in SweepModeScreen, MeshScreen.
     */
    fun formatTimestampShort(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * Date + time: "MMM d, HH:mm" (e.g. "Apr 9, 18:05").
     * Previously in BaselineScreen.
     */
    fun formatTimestampDate(timestamp: Long): String {
        return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    // ── Durations ────────────────────────────────────────────────────

    /**
     * Human-friendly duration ("2 days, 3 hr", "45 minutes", "Less than a minute").
     * Previously in AlertDetailScreen.
     */
    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "$days day${if (days != 1L) "s" else ""}, ${hours % 24} hr"
            hours > 0 -> "$hours hour${if (hours != 1L) "s" else ""}, ${minutes % 60} min"
            minutes > 0 -> "$minutes minute${if (minutes != 1L) "s" else ""}"
            else -> "Less than a minute"
        }
    }

    /**
     * Compact duration using TimeUnit ("2h 15m", "3m 20s", "45s").
     * Previously in NetworkIntegrityScreen.
     */
    fun formatDurationCompact(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Clock-style duration: "00:05:23".
     * Previously in SweepModeScreen / SweepModeViewModel.
     */
    fun formatDurationClock(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}
