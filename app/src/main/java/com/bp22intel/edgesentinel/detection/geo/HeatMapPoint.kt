/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.geo

/**
 * A single geo-tagged signal strength sample for heat map visualization.
 *
 * @param lat       Latitude where the reading was taken (user position)
 * @param lng       Longitude where the reading was taken (user position)
 * @param rssi      Signal strength at this position (dBm)
 * @param cellId    Which cell/threat this reading is associated with
 * @param timestamp Epoch millis when the reading was collected
 * @param isPeer    True if this reading came from a mesh peer (false = local device)
 */
data class HeatMapPoint(
    val lat: Double,
    val lng: Double,
    val rssi: Int,
    val cellId: Long,
    val timestamp: Long,
    val isPeer: Boolean = false
)
