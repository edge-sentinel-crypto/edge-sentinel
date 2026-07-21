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
import android.telephony.CellInfo
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.bp22intel.edgesentinel.domain.model.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observed NR cell snapshot with all available fields from CellInfoNr.
 */
data class NrCellSnapshot(
    val nci: Long,
    val tac: Int,
    val nrarfcn: Int,
    val pci: Int,
    val mcc: String?,
    val mnc: String?,
    val ssRsrp: Int,
    val ssRsrq: Int,
    val ssSinr: Int,
    val csiRsrp: Int,
    val csiRsrq: Int,
    val csiSinr: Int,
    val isRegistered: Boolean,
    val timestamp: Long
)

/**
 * NR connection state as reported by the device.
 */
enum class NrConnectionState {
    /** Device is connected to NR Standalone. */
    NR_SA,
    /** Device is connected via EN-DC (NSA — NR as secondary). */
    NR_NSA,
    /** Device is on NR NSA with mmWave carrier. */
    NR_NSA_MMWAVE,
    /** Device is on NR Advanced (carrier aggregation / higher tier). */
    NR_ADVANCED,
    /** Device is not connected to any NR network. */
    NOT_ON_NR
}

/**
 * Events emitted by [NrMonitor] for consumption by the detection layer.
 */
sealed class NrEvent {
    data class CellInfoUpdate(val nrCells: List<NrCellSnapshot>) : NrEvent()
    data class ConnectionStateChanged(
        val previous: NrConnectionState,
        val current: NrConnectionState
    ) : NrEvent()
    data class NrArfcnChanged(val previousArfcn: Int, val currentArfcn: Int) : NrEvent()
}

/**
 * Dedicated 5G NR monitoring class.
 *
 * Uses TelephonyCallback.CellInfoListener (Android 12+) with fallback to
 * PhoneStateListener for older devices. Tracks NR connection state changes
 * over time, maintains a history of NR cells seen, and detects EN-DC status
 * via TelephonyDisplayInfo override network types.
 */
@Singleton
class NrMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val telephonyManager: TelephonyManager
        get() = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _events = MutableSharedFlow<NrEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NrEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(NrConnectionState.NOT_ON_NR)
    val connectionState: StateFlow<NrConnectionState> = _connectionState.asStateFlow()

    private val _nrCellHistory = mutableListOf<NrCellSnapshot>()

    /** Maximum number of NR cell snapshots to retain in history. */
    private val maxHistorySize = 500

    private var lastPrimaryArfcn: Int = Int.MIN_VALUE
    private var previousConnectionState: NrConnectionState = NrConnectionState.NOT_ON_NR
    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    /**
     * Returns a copy of the NR cell observation history.
     */
    fun getNrCellHistory(): List<NrCellSnapshot> = synchronized(_nrCellHistory) {
        _nrCellHistory.toList()
    }

    /**
     * Start monitoring NR cell info and connection state.
     */
    @Suppress("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startApi31()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startLegacy()
        }
        // Below API 29 there is no CellInfoNr — nothing to monitor
    }

    /**
     * Stop monitoring and release telephony callbacks.
     */
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("MissingPermission")
    private fun startApi31() {
        val callback = object :
            TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.DisplayInfoListener {

            override fun onCellInfoChanged(cellInfoList: MutableList<CellInfo>) {
                handleCellInfoUpdate(cellInfoList)
            }

            override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                handleDisplayInfoChanged(displayInfo)
            }
        }
        telephonyCallback = callback
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("DEPRECATION", "MissingPermission")
    private fun startLegacy() {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCellInfoChanged(cellInfoList: MutableList<CellInfo>?) {
                cellInfoList?.let { handleCellInfoUpdate(it) }
            }
        }
        phoneStateListener = listener
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
    }

    private fun handleCellInfoUpdate(cellInfoList: List<CellInfo>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val nrSnapshots = cellInfoList
            .filterIsInstance<CellInfoNr>()
            .map { extractNrSnapshot(it) }

        if (nrSnapshots.isEmpty()) return

        // Track ARFCN changes on the primary (registered) NR cell
        val primaryNr = nrSnapshots.firstOrNull { it.isRegistered } ?: nrSnapshots.first()
        if (lastPrimaryArfcn != Int.MIN_VALUE && primaryNr.nrarfcn != lastPrimaryArfcn
            && primaryNr.nrarfcn != Int.MAX_VALUE
        ) {
            _events.tryEmit(NrEvent.NrArfcnChanged(lastPrimaryArfcn, primaryNr.nrarfcn))
        }
        if (primaryNr.nrarfcn != Int.MAX_VALUE) {
            lastPrimaryArfcn = primaryNr.nrarfcn
        }

        // Add to history
        synchronized(_nrCellHistory) {
            _nrCellHistory.addAll(nrSnapshots)
            while (_nrCellHistory.size > maxHistorySize) {
                _nrCellHistory.removeAt(0)
            }
        }

        _events.tryEmit(NrEvent.CellInfoUpdate(nrSnapshots))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun extractNrSnapshot(cellInfo: CellInfoNr): NrCellSnapshot {
        val identity = cellInfo.cellIdentity as CellIdentityNr
        val signal = cellInfo.cellSignalStrength as CellSignalStrengthNr
        val now = System.currentTimeMillis()

        return NrCellSnapshot(
            nci = identity.nci,
            tac = identity.tac,
            nrarfcn = identity.nrarfcn,
            pci = identity.pci,
            mcc = identity.mccString,
            mnc = identity.mncString,
            ssRsrp = signal.ssRsrp,
            ssRsrq = signal.ssRsrq,
            ssSinr = signal.ssSinr,
            csiRsrp = signal.csiRsrp,
            csiRsrq = signal.csiRsrq,
            csiSinr = signal.csiSinr,
            isRegistered = cellInfo.isRegistered,
            timestamp = now
        )
    }

    private fun handleDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val newState = when (displayInfo.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> NrConnectionState.NR_NSA
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> NrConnectionState.NR_NSA_MMWAVE
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> NrConnectionState.NR_ADVANCED
            else -> {
                // Check if we're on SA by looking at the network type
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    displayInfo.networkType == TelephonyManager.NETWORK_TYPE_NR
                ) {
                    NrConnectionState.NR_SA
                } else {
                    NrConnectionState.NOT_ON_NR
                }
            }
        }

        if (newState != previousConnectionState) {
            _connectionState.value = newState
            _events.tryEmit(
                NrEvent.ConnectionStateChanged(
                    previous = previousConnectionState,
                    current = newState
                )
            )
            previousConnectionState = newState
        }
    }

    /**
     * Determine if the device is currently in EN-DC (Dual Connectivity) mode.
     *
     * EN-DC = LTE anchor + NR secondary carrier (NSA deployment).
     */
    fun isInEndcMode(): Boolean {
        val state = _connectionState.value
        return state == NrConnectionState.NR_NSA ||
            state == NrConnectionState.NR_NSA_MMWAVE
    }

    /**
     * Determine if the device is on NR Standalone.
     */
    fun isOnNrStandalone(): Boolean {
        return _connectionState.value == NrConnectionState.NR_SA
    }

    /**
     * Get the count of distinct NR cells observed in the given time window.
     */
    fun getDistinctNrCellCount(windowMs: Long): Int {
        val cutoff = System.currentTimeMillis() - windowMs
        return synchronized(_nrCellHistory) {
            _nrCellHistory
                .filter { it.timestamp >= cutoff }
                .map { it.nci }
                .distinct()
                .count()
        }
    }
}
