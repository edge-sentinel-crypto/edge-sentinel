package com.airbridge.airplay.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.airbridge.airplay.AirBridgeApp
import com.airbridge.airplay.MainActivity
import com.airbridge.airplay.R
import com.airbridge.airplay.util.AirLog

/**
 * Foreground service (types mediaProjection | mediaPlayback) that owns the MediaProjection,
 * the AudioRecord capture pipeline, a partial WakeLock and a Wi-Fi lock for as long as streaming
 * runs. Started from the activity right after the user accepts the screen/audio capture prompt;
 * stopped from the notification's Stop action or the app.
 *
 * Android 14+ requires startForeground() with the mediaProjection type *before*
 * getMediaProjection(), and a consent token can be used only once; both are honoured here.
 */
class AudioCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var engine: CaptureEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var stopping = false

    private val controller get() = (application as AirBridgeApp).controller

    /** Keeps the notification's "streaming to N speakers" text current. */
    private var lastActiveCount = -1
    private val notificationRefresher = object : Runnable {
        override fun run() {
            if (engine == null || stopping) return
            val active = controller.sessions.activeCount
            if (active != lastActiveCount) {
                lastActiveCount = active
                notifyManager().notify(NOTIFICATION_ID, buildNotification())
            }
            main.postDelayed(this, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming("Stopped from notification")
                return START_NOT_STICKY
            }
            ACTION_START -> startStreaming(intent)
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent) {
        if (engine != null) return
        createChannel()
        // 1. Become a foreground service first (required before getMediaProjection on Android 14+).
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), types)
        } catch (e: Exception) {
            AirLog.e(TAG, "startForeground failed", e)
            controller.onCaptureFailed("Android refused to start the streaming service: ${e.message}")
            stopSelf()
            return
        }

        // 2. Obtain the MediaProjection from the one-time consent token.
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            if (data != null) mpm.getMediaProjection(resultCode, data) else null
        } catch (e: Exception) {
            AirLog.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (mp == null) {
            controller.onCaptureFailed("Screen/audio capture permission was not granted")
            stopStreaming(null)
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AirLog.i(TAG, "MediaProjection stopped by the system")
                main.post { stopStreaming("Android ended audio capture (screen locked or capture revoked)") }
            }
        }, main)

        // 3. Keep CPU and Wi-Fi awake while streaming with the screen off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirBridge:streaming").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // HIGH_PERF keeps Wi-Fi out of power save with the screen off on Android 10-13; it is a no-op
        // from Android 14, where LOW_LATENCY (effective while AirBridge is visible) is the remaining option.
        val mode = if (android.os.Build.VERSION.SDK_INT >= 34) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "AirBridge:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }

        // 4. Start capture and the AirPlay sessions.
        val c = controller
        val timelineClock = c.clock
        val e = CaptureEngine(this, mp, c.sessions, timelineClock, object : CaptureEngine.Listener {
            override fun onLevel(level: LevelMeter.Level) = c.onLevel(level)
            override fun onHealth(status: CaptureHealthMonitor.Status) = c.onCaptureHealth(status)
            override fun onCaptureError(message: String) {
                main.post { stopStreaming(message) }
            }
        })
        try {
            c.onCaptureStarting()
            e.start()
            engine = e
            c.onCaptureStarted()
            notifyManager().notify(NOTIFICATION_ID, buildNotification())
            main.postDelayed(notificationRefresher, 2_000)
        } catch (ex: Exception) {
            AirLog.e(TAG, "capture start failed", ex)
            controller.onCaptureFailed("Could not start audio capture: ${ex.message}")
            stopStreaming(null)
        }
    }

    private fun stopStreaming(reason: String?) {
        if (stopping) return
        stopping = true
        main.removeCallbacks(notificationRefresher)
        engine?.stop()
        engine = null
        // TEARDOWN can block on the network: do it off the main thread.
        Thread({ controller.onCaptureStopped(reason) }, "airbridge-teardown").start()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) stopStreaming(null)
        super.onDestroy()
    }

    private fun notifyManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notifyManager().createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = controller.sessions.activeCount
        val text = if (active == 0) {
            getString(R.string.notification_text_none)
        } else {
            resources.getQuantityString(R.plurals.notification_text_some, active, active)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airbridge)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.airbridge.airplay.action.START"
        const val ACTION_STOP = "com.airbridge.airplay.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "airbridge_streaming"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, AudioCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent = Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP)
    }
}
