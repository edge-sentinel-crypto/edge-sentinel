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

import android.content.Context
import android.content.Intent

fun interface ProcessRestarter {
    fun restartProcess()
}

class DefaultProcessRestarter(private val context: Context) : ProcessRestarter {
    override fun restartProcess() {
        val launch = context.packageManager?.getLaunchIntentForPackage(context.packageName)
        val component = launch?.component
        if (component != null) {
            context.startActivity(Intent.makeRestartActivityTask(component))
        }
        Runtime.getRuntime().exit(0)
    }
}
