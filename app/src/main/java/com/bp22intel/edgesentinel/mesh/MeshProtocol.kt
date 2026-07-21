/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.mesh

import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType
import org.json.JSONObject
import java.util.UUID

/**
 * Mesh message protocol for device-to-device alert sharing.
 * Privacy-first: no identity, IMSI, or location data is transmitted.
 */
object MeshProtocol {
    const val PROTOCOL_VERSION = 2
    const val SERVICE_UUID = "ed9e-5e71-1ne1-mesh"

    /** Valid BLE service UUID for Edge Sentinel mesh. */
    val BLE_SERVICE_UUID: java.util.UUID = java.util.UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000001")

    /** Cooperative observation GATT characteristics. */
    val OBSERVATION_SHARE_UUID: java.util.UUID = java.util.UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000010")
    val OBSERVATION_RECEIVE_UUID: java.util.UUID = java.util.UUID.fromString("ed9e5e71-1ae1-4d3a-b5c7-ae5b00000011")

    /** Message types exchanged between mesh peers. */
    enum class MessageType {
        ALERT,                  // Threat alert broadcast
        HEARTBEAT,              // Peer presence announcement
        ACK,                    // Alert acknowledgement
        COOPERATIVE_OBSERVATION // Cooperative localization observation
    }
}

/** An alert shared over the mesh network. */
data class MeshAlert(
    val messageId: String = UUID.randomUUID().toString(),
    val protocolVersion: Int = MeshProtocol.PROTOCOL_VERSION,
    val messageType: MeshProtocol.MessageType = MeshProtocol.MessageType.ALERT,
    val deviceId: String,       // Anonymous UUID — no PII
    val timestamp: Long,
    val threatType: ThreatType,
    val severity: ThreatLevel,
    val confidence: Confidence,
    val summary: String,
    /** Cell snapshot: MCC, MNC, LAC/TAC, CID — identifies the cell, not the user. */
    val cellMcc: Int? = null,
    val cellMnc: Int? = null,
    val cellLacTac: Int? = null,
    val cellCid: Int? = null,
    /**
     * Coarse peer position (grid-snapped to ~100m for privacy).
     * Used for cooperative multi-device geolocation.
     * Protocol v2+. Null if peer opts out of position sharing.
     */
    val peerLatCoarse: Double? = null,
    val peerLngCoarse: Double? = null,
    /** RSRP of the suspicious cell as seen by the peer. */
    val cellRsrp: Int? = null,
    val hopCount: Int = 0
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("v", protocolVersion)
        json.put("mt", messageType.name)
        json.put("mid", messageId)
        json.put("did", deviceId)
        json.put("ts", timestamp)
        json.put("tt", threatType.name)
        json.put("sev", severity.name)
        json.put("conf", confidence.name)
        json.put("sum", summary)
        cellMcc?.let { json.put("mcc", it) }
        cellMnc?.let { json.put("mnc", it) }
        cellLacTac?.let { json.put("lac", it) }
        cellCid?.let { json.put("cid", it) }
        peerLatCoarse?.let { json.put("plat", it) }
        peerLngCoarse?.let { json.put("plng", it) }
        cellRsrp?.let { json.put("rsrp", it) }
        json.put("hop", hopCount)
        return json.toString()
    }

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun snapToGrid(coord: Double): Double =
            (coord * 1000.0).toLong() / 1000.0

        fun fromBytes(bytes: ByteArray): MeshAlert? = fromJson(String(bytes, Charsets.UTF_8))

        fun fromJson(raw: String): MeshAlert? {
            return try {
                val json = JSONObject(raw)
                MeshAlert(
                    protocolVersion = json.optInt("v", MeshProtocol.PROTOCOL_VERSION),
                    messageType = MeshProtocol.MessageType.valueOf(
                        json.optString("mt", MeshProtocol.MessageType.ALERT.name)
                    ),
                    messageId = json.getString("mid"),
                    deviceId = json.getString("did"),
                    timestamp = json.getLong("ts"),
                    threatType = ThreatType.valueOf(json.getString("tt")),
                    severity = ThreatLevel.valueOf(json.getString("sev")),
                    confidence = Confidence.valueOf(json.getString("conf")),
                    summary = json.getString("sum"),
                    cellMcc = if (json.has("mcc")) json.getInt("mcc") else null,
                    cellMnc = if (json.has("mnc")) json.getInt("mnc") else null,
                    cellLacTac = if (json.has("lac")) json.getInt("lac") else null,
                    cellCid = if (json.has("cid")) json.getInt("cid") else null,
                    peerLatCoarse = if (json.has("plat")) json.getDouble("plat") else null,
                    peerLngCoarse = if (json.has("plng")) json.getDouble("plng") else null,
                    cellRsrp = if (json.has("rsrp")) json.getInt("rsrp") else null,
                    hopCount = json.optInt("hop", 0)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Heartbeat message for peer presence. */
data class MeshHeartbeat(
    val deviceId: String,
    val protocolVersion: Int = MeshProtocol.PROTOCOL_VERSION,
    val timestamp: Long = System.currentTimeMillis(),
    val alertCount: Int = 0
) {
    fun toBytes(): ByteArray {
        val json = JSONObject()
        json.put("v", protocolVersion)
        json.put("mt", MeshProtocol.MessageType.HEARTBEAT.name)
        json.put("did", deviceId)
        json.put("ts", timestamp)
        json.put("ac", alertCount)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): MeshHeartbeat? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                MeshHeartbeat(
                    deviceId = json.getString("did"),
                    protocolVersion = json.optInt("v", MeshProtocol.PROTOCOL_VERSION),
                    timestamp = json.getLong("ts"),
                    alertCount = json.optInt("ac", 0)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
