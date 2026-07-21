/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.domain.model

data class CellTower(
    val id: Long,
    val cid: Int,
    val lacTac: Int,
    val mcc: Int,
    val mnc: Int,
    val signalStrength: Int,
    val networkType: NetworkType,
    val latitude: Double?,
    val longitude: Double?,
    val firstSeen: Long,
    val lastSeen: Long,
    val timesSeen: Int,
    /** EARFCN (LTE), ARFCN (GSM), UARFCN (WCDMA), or NRARFCN (NR). [Int.MAX_VALUE] = unavailable. */
    val earfcn: Int = Int.MAX_VALUE,
    /** Physical Cell ID (LTE: 0..503, NR: 0..1007) or PSC (WCDMA: 0..511). [Int.MAX_VALUE] = unavailable. */
    val pci: Int = Int.MAX_VALUE
)
