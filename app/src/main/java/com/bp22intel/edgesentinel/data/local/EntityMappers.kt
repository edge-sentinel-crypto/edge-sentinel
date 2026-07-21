/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.local

import com.bp22intel.edgesentinel.data.local.entity.AlertEntity
import com.bp22intel.edgesentinel.data.local.entity.CellTowerEntity
import com.bp22intel.edgesentinel.data.local.entity.ScanEntity
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.ScanResult
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType

// ── CellTower ───────────────────────────────────────────────────────────────────

fun CellTowerEntity.toDomain(): CellTower = CellTower(
    id = id,
    cid = cid,
    lacTac = lacTac,
    mcc = mcc,
    mnc = mnc,
    signalStrength = signalStrength,
    networkType = NetworkType.valueOf(networkType),
    latitude = latitude,
    longitude = longitude,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    timesSeen = timesSeen,
    earfcn = earfcn,
    pci = pci
)

fun CellTower.toEntity(): CellTowerEntity = CellTowerEntity(
    id = id,
    cid = cid,
    lacTac = lacTac,
    mcc = mcc,
    mnc = mnc,
    signalStrength = signalStrength,
    networkType = networkType.name,
    latitude = latitude,
    longitude = longitude,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    timesSeen = timesSeen,
    earfcn = earfcn,
    pci = pci
)

// ── Alert ───────────────────────────────────────────────────────────────────────

fun AlertEntity.toDomain(): Alert = Alert(
    id = id,
    timestamp = timestamp,
    threatType = ThreatType.valueOf(threatType),
    severity = ThreatLevel.valueOf(severity),
    confidence = Confidence.valueOf(confidence),
    summary = summary,
    detailsJson = detailsJson,
    cellId = cellId,
    acknowledged = acknowledged
)

fun Alert.toEntity(): AlertEntity = AlertEntity(
    id = id,
    timestamp = timestamp,
    threatType = threatType.name,
    severity = severity.name,
    confidence = confidence.name,
    summary = summary,
    detailsJson = detailsJson,
    cellId = cellId,
    acknowledged = acknowledged
)

// ── ScanResult ──────────────────────────────────────────────────────────────────

fun ScanEntity.toDomain(): ScanResult = ScanResult(
    id = id,
    timestamp = timestamp,
    cellCount = cellCount,
    threatLevel = ThreatLevel.valueOf(threatLevel),
    durationMs = durationMs
)

fun ScanResult.toEntity(): ScanEntity = ScanEntity(
    id = id,
    timestamp = timestamp,
    cellCount = cellCount,
    threatLevel = threatLevel.name,
    durationMs = durationMs
)
