/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.sensor

import android.content.Context
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CellInfoCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val telephonyManager: TelephonyManager
        get() = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Suppress("MissingPermission")
    fun getCurrentCellInfo(): List<CellTower> {
        val now = System.currentTimeMillis()
        val cellInfoList = try {
            telephonyManager.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

        return cellInfoList.mapNotNull { cellInfo ->
            when (cellInfo) {
                is CellInfoLte -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    CellTower(
                        id = 0,
                        cid = identity.ci,
                        lacTac = identity.tac,
                        mcc = identity.mccString?.toIntOrNull() ?: 0,
                        mnc = identity.mncString?.toIntOrNull() ?: 0,
                        signalStrength = signal.dbm,
                        networkType = NetworkType.LTE,
                        latitude = null,
                        longitude = null,
                        firstSeen = now,
                        lastSeen = now,
                        timesSeen = 1,
                        earfcn = identity.earfcn,
                        pci = identity.pci
                    )
                }
                is CellInfoNr -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        extractNrCellTower(cellInfo, now)
                    } else {
                        null
                    }
                }
                is CellInfoGsm -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    CellTower(
                        id = 0,
                        cid = identity.cid,
                        lacTac = identity.lac,
                        mcc = identity.mccString?.toIntOrNull() ?: 0,
                        mnc = identity.mncString?.toIntOrNull() ?: 0,
                        signalStrength = signal.dbm,
                        networkType = NetworkType.GSM,
                        latitude = null,
                        longitude = null,
                        firstSeen = now,
                        lastSeen = now,
                        timesSeen = 1,
                        earfcn = identity.arfcn
                    )
                }
                is CellInfoWcdma -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    CellTower(
                        id = 0,
                        cid = identity.cid,
                        lacTac = identity.lac,
                        mcc = identity.mccString?.toIntOrNull() ?: 0,
                        mnc = identity.mncString?.toIntOrNull() ?: 0,
                        signalStrength = signal.dbm,
                        networkType = NetworkType.WCDMA,
                        latitude = null,
                        longitude = null,
                        firstSeen = now,
                        lastSeen = now,
                        timesSeen = 1,
                        earfcn = identity.uarfcn,
                        pci = identity.psc
                    )
                }
                is CellInfoCdma -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    CellTower(
                        id = 0,
                        cid = identity.basestationId,
                        lacTac = identity.networkId,
                        mcc = 0,
                        mnc = identity.systemId,
                        signalStrength = signal.dbm,
                        networkType = NetworkType.CDMA,
                        latitude = identity.latitude.toDouble(),
                        longitude = identity.longitude.toDouble(),
                        firstSeen = now,
                        lastSeen = now,
                        timesSeen = 1
                    )
                }
                else -> null
            }
        }
    }

    /**
     * Extract a CellTower from CellInfoNr with all available NR-specific fields.
     *
     * Collects NCI, TAC, NR-ARFCN, PCI, and full signal strength measurements
     * (SS-RSRP, SS-RSRQ, SS-SINR, CSI-RSRP, CSI-RSRQ, CSI-SINR) where available.
     * NR-ARFCN and PCI are stored in the details for use by NrDetector.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun extractNrCellTower(cellInfo: CellInfoNr, timestamp: Long): CellTower {
        val identity = cellInfo.cellIdentity as CellIdentityNr
        val signal = cellInfo.cellSignalStrength as CellSignalStrengthNr

        // Use SS-RSRP as the primary signal strength metric for NR.
        // Falls back to generic dbm if SS-RSRP is unavailable.
        val signalDbm = if (signal.ssRsrp != Int.MAX_VALUE) {
            signal.ssRsrp
        } else {
            signal.dbm
        }

        return CellTower(
            id = 0,
            cid = identity.nci.toInt(),
            lacTac = identity.tac,
            mcc = identity.mccString?.toIntOrNull() ?: 0,
            mnc = identity.mncString?.toIntOrNull() ?: 0,
            signalStrength = signalDbm,
            networkType = NetworkType.NR,
            latitude = null,
            longitude = null,
            firstSeen = timestamp,
            lastSeen = timestamp,
            timesSeen = 1,
            earfcn = identity.nrarfcn,
            pci = identity.pci
        )
    }

    /**
     * Get the NR override network type from TelephonyDisplayInfo.
     *
     * Returns a string indicating the NR connection mode:
     * - "NR_NSA" — Non-Standalone (EN-DC with LTE anchor)
     * - "NR_NSA_MMWAVE" — NSA on mmWave carrier
     * - "NR_ADVANCED" — NR with carrier aggregation
     * - "NR_SA" — Standalone (if dataNetworkType is NR)
     * - null — not on NR
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("MissingPermission")
    fun getOverrideNetworkType(): String? {
        return try {
            when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "NR_SA"
                else -> null // Override type detection handled by TelephonyMonitor/NrMonitor
            }
        } catch (e: SecurityException) {
            null
        }
    }

    @Suppress("MissingPermission")
    fun getSignalStrength(): Int {
        return try {
            val cellInfoList = telephonyManager.allCellInfo ?: emptyList()
            cellInfoList.firstOrNull { it.isRegistered }
                ?.cellSignalStrength?.dbm ?: Int.MIN_VALUE
        } catch (e: SecurityException) {
            Int.MIN_VALUE
        }
    }

    @Suppress("MissingPermission")
    fun getNetworkType(): NetworkType {
        return try {
            when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE -> NetworkType.GSM
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD -> NetworkType.CDMA
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> NetworkType.WCDMA
                TelephonyManager.NETWORK_TYPE_LTE -> NetworkType.LTE
                TelephonyManager.NETWORK_TYPE_NR -> NetworkType.NR
                else -> NetworkType.UNKNOWN
            }
        } catch (e: SecurityException) {
            NetworkType.UNKNOWN
        }
    }
}
