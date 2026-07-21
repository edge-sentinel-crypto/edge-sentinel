/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao
import com.bp22intel.edgesentinel.data.sensor.CellInfoCollector
import com.bp22intel.edgesentinel.data.sensor.TelephonyMonitor
import com.bp22intel.edgesentinel.detection.bluetooth.BleAlertManager
import com.bp22intel.edgesentinel.detection.bluetooth.BleTrackingDetector
import com.bp22intel.edgesentinel.analysis.FalsePositiveFilter
import com.bp22intel.edgesentinel.analysis.SuppressionAction
import com.bp22intel.edgesentinel.detection.engine.ThreatDetectionEngine
import com.bp22intel.edgesentinel.detection.network.CaptivePortalDetector
import com.bp22intel.edgesentinel.detection.network.DnsIntegrityChecker
import com.bp22intel.edgesentinel.detection.network.TlsIntegrityChecker
import com.bp22intel.edgesentinel.detection.network.VpnMonitor
import com.bp22intel.edgesentinel.detection.wifi.WifiMonitor
import com.bp22intel.edgesentinel.detection.wifi.WifiThreatDetector
import com.bp22intel.edgesentinel.domain.model.Alert
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.DetectionResult
import com.bp22intel.edgesentinel.domain.model.ScanResult
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.repository.AlertRepository
import com.bp22intel.edgesentinel.domain.repository.CellRepository
import com.bp22intel.edgesentinel.domain.repository.ScanRepository
import com.bp22intel.edgesentinel.notification.NotificationChannels
import com.bp22intel.edgesentinel.sensor.MotionDetector
import com.bp22intel.edgesentinel.sensor.MotionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject lateinit var cellInfoCollector: CellInfoCollector
    @Inject lateinit var telephonyMonitor: TelephonyMonitor
    @Inject lateinit var threatDetectionEngine: ThreatDetectionEngine
    @Inject lateinit var falsePositiveFilter: FalsePositiveFilter
    @Inject lateinit var cellRepository: CellRepository
    @Inject lateinit var alertRepository: AlertRepository
    @Inject lateinit var scanRepository: ScanRepository
    @Inject lateinit var sensorFusionEngine: com.bp22intel.edgesentinel.fusion.SensorFusionEngine
    @Inject lateinit var motionDetector: MotionDetector

    // WiFi detection
    @Inject lateinit var wifiMonitor: WifiMonitor
    @Inject lateinit var wifiThreatDetector: WifiThreatDetector
    @Inject lateinit var trustedNetworkDao: TrustedNetworkDao

    // BLE detection
    @Inject lateinit var bleTrackingDetector: BleTrackingDetector
    @Inject lateinit var bleAlertManager: BleAlertManager

    // Network integrity detection
    @Inject lateinit var dnsIntegrityChecker: DnsIntegrityChecker
    @Inject lateinit var tlsIntegrityChecker: TlsIntegrityChecker
    @Inject lateinit var captivePortalDetector: CaptivePortalDetector
    @Inject lateinit var vpnMonitor: VpnMonitor

    @Inject lateinit var knownTowerDao: com.bp22intel.edgesentinel.data.local.dao.KnownTowerDao
    @Inject lateinit var towerPositionTracker: com.bp22intel.edgesentinel.detection.geo.TowerPositionTracker

    private var scanJob: Job? = null
    private var wifiJob: Job? = null
    private var bleJob: Job? = null
    private var networkJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    /** Timestamp of the most recent alert, used for adaptive interval logic. */
    private var lastAlertTimestamp = 0L

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "com.bp22intel.edgesentinel.action.START"
        private const val ACTION_STOP = "com.bp22intel.edgesentinel.action.STOP"

        // --- Adaptive scan intervals -----------------------------------------
        /** Alert just detected — aggressive scanning for 5 minutes. */
        private const val ALERT_SCAN_INTERVAL_MS = 15_000L
        /** Alert cooldown window: stay aggressive for this long after last alert. */
        private const val ALERT_COOLDOWN_MS = 5 * 60_000L

        /** No alerts for 10 min — environment stable. */
        private const val STABLE_SCAN_INTERVAL_MS = 120_000L
        private const val STABLE_THRESHOLD_MS = 10 * 60_000L

        /** No alerts for 30 min — deep idle. */
        private const val IDLE_SCAN_INTERVAL_MS = 300_000L
        private const val IDLE_THRESHOLD_MS = 30 * 60_000L

        /** Battery saver override when charge < 20%. */
        private const val LOW_BATTERY_SCAN_INTERVAL_MS = 600_000L
        private const val LOW_BATTERY_THRESHOLD_PERCENT = 20

        // Legacy constants kept as fallback references
        private const val ACTIVE_SCAN_INTERVAL_MS = 30_000L
        private const val PASSIVE_SCAN_INTERVAL_MS = 300_000L

        private val _threatLevel = MutableStateFlow(ThreatLevel.CLEAR)
        val threatLevel: StateFlow<ThreatLevel> = _threatLevel.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildStatusNotification(ThreatLevel.CLEAR))
                startMonitoring()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun startMonitoring() {
        _isRunning.value = true
        telephonyMonitor.start()
        motionDetector.start()

        // Cellular scan loop
        scanJob = lifecycleScope.launch {
            while (isActive) {
                performScan()
                val interval = computeAdaptiveInterval()
                delay(interval)
            }
        }

        // WiFi detection → fusion
        startWifiMonitoring()

        // BLE detection → fusion
        startBleMonitoring()

        // Network integrity → fusion
        startNetworkMonitoring()
    }

    /**
     * Determines the next scan interval based on alert recency, battery level,
     * and current threat state.
     *
     * Priority (highest → lowest):
     * 1. Low battery (< 20%) → 10-minute scans regardless of other factors
     * 2. Recent alert (within 5 min) → 15-second aggressive scans
     * 3. Stable (no alert for 10+ min) → 2-minute scans
     * 4. Deep idle (no alert for 30+ min) → 5-minute scans
     * 5. Fallback → legacy active/passive interval based on threat level
     */
    private fun computeAdaptiveInterval(): Long {
        // 1. Battery saver override
        if (getBatteryPercent() < LOW_BATTERY_THRESHOLD_PERCENT) {
            return LOW_BATTERY_SCAN_INTERVAL_MS
        }

        val now = System.currentTimeMillis()
        val sinceLastAlert = now - lastAlertTimestamp

        return when {
            // 2. Alert just happened — aggressive scan
            lastAlertTimestamp > 0 && sinceLastAlert < ALERT_COOLDOWN_MS -> ALERT_SCAN_INTERVAL_MS
            // 3. Stable environment
            lastAlertTimestamp > 0 && sinceLastAlert < IDLE_THRESHOLD_MS -> STABLE_SCAN_INTERVAL_MS
            // 4. Deep idle (30+ min no alerts, or never alerted)
            lastAlertTimestamp == 0L || sinceLastAlert >= IDLE_THRESHOLD_MS -> IDLE_SCAN_INTERVAL_MS
            // 5. Fallback (shouldn't normally reach here)
            else -> if (_threatLevel.value == ThreatLevel.CLEAR) PASSIVE_SCAN_INTERVAL_MS
                    else ACTIVE_SCAN_INTERVAL_MS
        }
    }

    /** Returns current battery level as a percentage (0–100), or 100 if unavailable. */
    private fun getBatteryPercent(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }

    private fun stopMonitoring() {
        // Flush tower position estimates to disk before stopping
        lifecycleScope.launch {
            try { towerPositionTracker.flushToDisk() } catch (_: Exception) {}
        }
        _isRunning.value = false
        _threatLevel.value = ThreatLevel.CLEAR
        scanJob?.cancel()
        scanJob = null
        wifiJob?.cancel()
        wifiJob = null
        bleJob?.cancel()
        bleJob = null
        networkJob?.cancel()
        networkJob = null
        telephonyMonitor.stop()
        motionDetector.stop()
        bleTrackingDetector.stopScanning()
        vpnMonitor.stopMonitoring()
        lastAlertTimestamp = 0L
    }

    // ── WiFi → Sensor Fusion ──────────────────────────────────────────────

    private fun startWifiMonitoring() {
        wifiJob = lifecycleScope.launch {
            wifiMonitor.scanFlow()
                .catch { /* WiFi unavailable — ignore */ }
                .collect { snapshot ->
                    val history = wifiMonitor.getHistory()
                    val trustedBssids = trustedNetworkDao.getAllTrustedBssids().toSet()
                    val trustedSsids = trustedNetworkDao.getAllTrustedSsids().toSet()
                    val results = wifiThreatDetector.analyze(
                        snapshot, history, emptyList(), trustedBssids, trustedSsids
                    )
                    val now = System.currentTimeMillis()
                    val fusionDetections = results.map { result ->
                        com.bp22intel.edgesentinel.fusion.ActiveDetection(
                            sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.WIFI,
                            detectionType = result.threatType.name,
                            description = result.summary,
                            score = result.score.coerceAtMost(1.0),
                            timestamp = now
                        )
                    }
                    if (fusionDetections.isNotEmpty()) {
                        sensorFusionEngine.ingestDetections(fusionDetections)
                    }
                }
        }
    }

    // ── BLE → Sensor Fusion ───────────────────────────────────────────────

    private fun startBleMonitoring() {
        bleTrackingDetector.startScanning()

        bleJob = lifecycleScope.launch {
            bleAlertManager.activeAlerts.collect { alerts ->
                val now = System.currentTimeMillis()
                val fusionDetections = alerts.map { alert ->
                    com.bp22intel.edgesentinel.fusion.ActiveDetection(
                        sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.BLUETOOTH,
                        detectionType = alert.type.name,
                        description = alert.summary,
                        score = alert.confidence.toDouble().coerceAtMost(1.0),
                        timestamp = now
                    )
                }
                if (fusionDetections.isNotEmpty()) {
                    sensorFusionEngine.ingestDetections(fusionDetections)
                }
            }
        }
    }

    // ── Network Integrity → Sensor Fusion ─────────────────────────────────

    /** Run network integrity checks every 5 minutes. */
    private val networkCheckIntervalMs = 5 * 60 * 1000L

    private fun startNetworkMonitoring() {
        vpnMonitor.startMonitoring()

        networkJob = lifecycleScope.launch {
            while (isActive) {
                performNetworkIntegrityCheck()
                delay(networkCheckIntervalMs)
            }
        }
    }

    private suspend fun performNetworkIntegrityCheck() {
        val now = System.currentTimeMillis()
        val detections = mutableListOf<com.bp22intel.edgesentinel.fusion.ActiveDetection>()

        try {
            // DNS integrity
            val dnsResult = dnsIntegrityChecker.runFullCheck()
            if (!dnsResult.overallClean) {
                for (domain in dnsResult.hijackedDomains) {
                    detections.add(
                        com.bp22intel.edgesentinel.fusion.ActiveDetection(
                            sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.NETWORK,
                            detectionType = "DNS_HIJACK",
                            description = "DNS hijack detected for $domain",
                            score = 0.8,
                            timestamp = now
                        )
                    )
                }
                if (dnsResult.nxdomainHijacked) {
                    detections.add(
                        com.bp22intel.edgesentinel.fusion.ActiveDetection(
                            sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.NETWORK,
                            detectionType = "DNS_NXDOMAIN_HIJACK",
                            description = "NXDOMAIN hijack detected — failed lookups redirected",
                            score = 0.6,
                            timestamp = now
                        )
                    )
                }
            }

            // TLS integrity
            val tlsResult = tlsIntegrityChecker.runFullCheck()
            if (!tlsResult.overallClean) {
                for (endpoint in tlsResult.mitmEndpoints) {
                    detections.add(
                        com.bp22intel.edgesentinel.fusion.ActiveDetection(
                            sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.NETWORK,
                            detectionType = "TLS_MITM",
                            description = "TLS MITM interception detected for $endpoint",
                            score = 0.9,
                            timestamp = now
                        )
                    )
                }
            }

            // Captive portal
            val portalResult = captivePortalDetector.runCheck()
            if (portalResult.captivePortalDetected && portalResult.jsInjectionDetected) {
                detections.add(
                    com.bp22intel.edgesentinel.fusion.ActiveDetection(
                        sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.NETWORK,
                        detectionType = "CAPTIVE_PORTAL_INJECT",
                        description = "Captive portal with JavaScript injection detected",
                        score = 0.7,
                        timestamp = now
                    )
                )
            }

            // VPN drop
            val vpnStatus = vpnMonitor.vpnStatus.value
            if (vpnStatus.vpnDropDetected) {
                detections.add(
                    com.bp22intel.edgesentinel.fusion.ActiveDetection(
                        sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.NETWORK,
                        detectionType = "VPN_DROP",
                        description = "VPN connection was silently dropped",
                        score = 0.7,
                        timestamp = now
                    )
                )
            }
            if (vpnStatus.bypassLeakDetected) {
                detections.add(
                    com.bp22intel.edgesentinel.fusion.ActiveDetection(
                        sensorCategory = com.bp22intel.edgesentinel.domain.model.SensorCategory.NETWORK,
                        detectionType = "VPN_BYPASS_LEAK",
                        description = "Traffic leaking outside VPN tunnel: ${vpnStatus.leakDetails ?: "unknown"}",
                        score = 0.75,
                        timestamp = now
                    )
                )
            }
        } catch (_: Exception) {
            // Network checks can fail (no connectivity, etc.) — don't crash
        }

        if (detections.isNotEmpty()) {
            sensorFusionEngine.ingestDetections(detections)
        }
    }

    private suspend fun performScan() {
        val startTime = System.currentTimeMillis()

        try {
            val currentCells = cellInfoCollector.getCurrentCellInfo()
            val history = cellRepository.getAllCells().first()

            // Save observed cells
            for (cell in currentCells) {
                cellRepository.insertOrUpdateCell(cell)
            }

            // Feed every visible tower into the continuous position tracker.
            // Get user GPS for position estimation.
            val userLocation = try {
                val locMgr = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                @Suppress("MissingPermission")
                locMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locMgr.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) { null }

            if (userLocation != null) {
                towerPositionTracker.processScanResults(
                    cells = currentCells,
                    userLat = userLocation.latitude,
                    userLng = userLocation.longitude
                )
            }

            // Run threat detection
            val detectionResults = threatDetectionEngine.runScan(
                cells = currentCells,
                history = history
            )

            // Determine overall threat level from detection results
            val scanThreatLevel = determineThreatLevel(detectionResults)
            _threatLevel.value = scanThreatLevel

            // Save scan result
            val durationMs = System.currentTimeMillis() - startTime
            val scanResult = ScanResult(
                timestamp = startTime,
                cellCount = currentCells.size,
                threatLevel = scanThreatLevel,
                durationMs = durationMs
            )
            scanRepository.insertScan(scanResult)

            // Create alerts for suspicious or threatening results
            for (result in detectionResults) {
                val severity = when {
                    result.score >= 0.7 -> ThreatLevel.THREAT
                    result.score >= 0.4 -> ThreatLevel.SUSPICIOUS
                    else -> ThreatLevel.CLEAR
                }

                if (severity == ThreatLevel.SUSPICIOUS || severity == ThreatLevel.THREAT) {
                    // Build enriched detailsJson with cell tower context + motion state.
                    val detailsJson = org.json.JSONObject().apply {
                        // Copy detection indicators
                        result.details.forEach { (k, v) -> put(k, v) }
                        // Add current cell tower context
                        currentCells.firstOrNull()?.let { cell ->
                            put("cellId", cell.cid)
                            put("lac", cell.lacTac)
                            put("mcc", cell.mcc)
                            put("mnc", cell.mnc)
                            put("signalStrength", cell.signalStrength)
                            put("networkType", cell.networkType.name)
                            put("nearbyTowerCount", currentCells.size)
                        }
                        // Motion state for ThreatAnalyst context
                        put("isMoving", motionDetector.isMoving)
                        put("motionState", motionDetector.motionState.value.name)
                    }.toString()

                    // Enrich detailsJson with tower location from known tower DB
                    val enrichedJson = try {
                        val obj = org.json.JSONObject(detailsJson)
                        // Skip if already has location
                        if (!obj.has("latitude")) {
                            // Try 1: look up from the alert's own cellId/mcc/mnc/lac
                            val alertCid = obj.optLong("cellId", -1L)
                            val alertMcc = obj.optInt("mcc", -1)
                            val alertMnc = obj.optInt("mnc", -1)
                            val alertLac = obj.optInt("lac", -1)
                            val known = if (alertCid > 0 && alertMcc > 0) {
                                knownTowerDao.findTower(alertMcc, alertMnc, alertLac, alertCid.toInt())
                            } else {
                                // Try 2: fall back to serving cell
                                currentCells.firstOrNull()?.let { cell ->
                                    knownTowerDao.findTower(cell.mcc, cell.mnc, cell.lacTac, cell.cid.toInt())
                                }
                            }
                            if (known != null && known.latitude != 0.0) {
                                obj.put("latitude", known.latitude)
                                obj.put("longitude", known.longitude)
                                obj.put("towerRange", known.range)
                                obj.put("accuracyMeters", known.range.coerceAtLeast(200))
                            }
                        }
                        obj.toString()
                    } catch (_: Exception) { detailsJson }

                    val alert = Alert(
                        timestamp = startTime,
                        threatType = result.threatType,
                        severity = severity,
                        confidence = result.confidence,
                        summary = result.summary,
                        detailsJson = enrichedJson,
                        cellId = currentCells.firstOrNull()?.id,
                        acknowledged = false
                    )
                    // Check false-positive filter before persisting.
                    val filterResult = falsePositiveFilter.evaluate(alert)
                    if (filterResult.action == SuppressionAction.SUPPRESS) {
                        // Learned suppression — skip this alert entirely.
                        continue
                    }

                    // If REDUCE, lower severity by one level.
                    val finalAlert = if (filterResult.action == SuppressionAction.REDUCE) {
                        val reducedSeverity = when (severity) {
                            ThreatLevel.THREAT -> ThreatLevel.SUSPICIOUS
                            else -> ThreatLevel.CLEAR
                        }
                        if (reducedSeverity == ThreatLevel.CLEAR) continue
                        alert.copy(severity = reducedSeverity)
                    } else {
                        alert
                    }

                    alertRepository.insertAlert(finalAlert)
                    showAlertNotification(finalAlert)

                    // Track last alert time for adaptive interval logic
                    lastAlertTimestamp = startTime

                    // Feed into sensor fusion engine
                    val sensorCategory = when (result.threatType) {
                        com.bp22intel.edgesentinel.domain.model.ThreatType.FAKE_BTS,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.NETWORK_DOWNGRADE,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.SILENT_SMS,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.CIPHER_ANOMALY,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.SIGNAL_ANOMALY,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.NR_ANOMALY,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.REGISTRATION_FAILURE,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.TEMPORAL_ANOMALY,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.KNOWN_TOWER_ANOMALY,
                        com.bp22intel.edgesentinel.domain.model.ThreatType.COMPOUND_PATTERN ->
                            com.bp22intel.edgesentinel.domain.model.SensorCategory.CELLULAR
                        com.bp22intel.edgesentinel.domain.model.ThreatType.TRACKING_PATTERN ->
                            com.bp22intel.edgesentinel.domain.model.SensorCategory.BLUETOOTH
                    }
                    sensorFusionEngine.ingestDetection(
                        com.bp22intel.edgesentinel.fusion.ActiveDetection(
                            sensorCategory = sensorCategory,
                            detectionType = result.threatType.name,
                            description = result.summary,
                            score = result.score,
                            timestamp = startTime
                        )
                    )
                }
            }

            // Update foreground notification
            notificationManager.notify(NOTIFICATION_ID, buildStatusNotification(scanThreatLevel))
        } catch (e: Exception) {
            // Log but don't crash the scan loop
            val durationMs = System.currentTimeMillis() - startTime
            val scanResult = ScanResult(
                timestamp = startTime,
                cellCount = 0,
                threatLevel = ThreatLevel.CLEAR,
                durationMs = durationMs
            )
            scanRepository.insertScan(scanResult)
        }
    }

    private fun determineThreatLevel(results: List<DetectionResult>): ThreatLevel {
        if (results.isEmpty()) return ThreatLevel.CLEAR

        val maxScore = results.maxOf { it.score }
        return when {
            maxScore >= 0.7 -> ThreatLevel.THREAT
            maxScore >= 0.4 -> ThreatLevel.SUSPICIOUS
            else -> ThreatLevel.CLEAR
        }
    }

    private fun buildStatusNotification(threatLevel: ThreatLevel): Notification {
        val statusText = when (threatLevel) {
            ThreatLevel.CLEAR -> "All clear - monitoring cellular environment"
            ThreatLevel.SUSPICIOUS -> "Suspicious activity detected"
            ThreatLevel.THREAT -> "THREAT DETECTED - Cellular anomaly found"
        }

        val priority = when (threatLevel) {
            ThreatLevel.CLEAR -> NotificationCompat.PRIORITY_LOW
            ThreatLevel.SUSPICIOUS -> NotificationCompat.PRIORITY_DEFAULT
            ThreatLevel.THREAT -> NotificationCompat.PRIORITY_HIGH
        }

        return NotificationCompat.Builder(this, NotificationChannels.MONITORING)
            .setContentTitle("Edge Sentinel")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(priority)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showAlertNotification(alert: Alert) {
        val notificationId = (alert.timestamp % Int.MAX_VALUE).toInt() + 100

        val title = when (alert.severity) {
            ThreatLevel.THREAT -> "THREAT: ${alert.threatType.name}"
            ThreatLevel.SUSPICIOUS -> "Suspicious: ${alert.threatType.name}"
            ThreatLevel.CLEAR -> return
        }

        val channelId = when (alert.severity) {
            ThreatLevel.THREAT -> NotificationChannels.CRITICAL
            else -> NotificationChannels.WARNING
        }

        // Deep-link to Alert Detail screen when notification is tapped
        val deepLinkIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("edgesentinel://alert_detail/${alert.id}"),
            this,
            com.bp22intel.edgesentinel.MainActivity::class.java
        ).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            notificationId,
            deepLinkIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(alert.summary)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(
                if (alert.severity == ThreatLevel.THREAT) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
