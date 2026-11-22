package com.airbridge.airplay

import android.app.Application
import android.util.Log
import com.airbridge.airplay.util.AirLog

/** Application entry point: wires the logging sink and the process-wide controller. */
class AirBridgeApp : Application() {
    lateinit var controller: AirBridgeController
        private set

    override fun onCreate() {
        super.onCreate()
        AirLog.minLevel = if (BuildConfig.DEBUG) AirLog.Level.DEBUG else AirLog.Level.INFO
        AirLog.sink = AirLog.Sink { level, tag, message, error ->
            val t = "AirBridge/$tag"
            when (level) {
                AirLog.Level.DEBUG -> Log.d(t, message, error)
                AirLog.Level.INFO -> Log.i(t, message, error)
                AirLog.Level.WARN -> Log.w(t, message, error)
                AirLog.Level.ERROR -> Log.e(t, message, error)
            }
        }
        controller = AirBridgeController(this)
    }
}
