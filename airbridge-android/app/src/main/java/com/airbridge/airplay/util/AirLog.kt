package com.airbridge.airplay.util

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Tiny logging facade so the protocol engine stays pure JVM (unit-testable without Android).
 * The app installs an android.util.Log sink at startup; a bounded in-memory tail feeds the
 * in-app diagnostics sheet. Nothing is ever written to disk or sent anywhere.
 */
object AirLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    private const val MAX_LINES = 400

    @Volatile
    var sink: Sink = Sink { level, tag, message, error ->
        println("${level.name.first()}/$tag: $message" + (error?.let { " ($it)" } ?: ""))
    }

    @Volatile
    var minLevel: Level = Level.DEBUG

    private val recent = ArrayDeque<String>()
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)

    fun log(level: Level, tag: String, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        if (level.ordinal >= Level.INFO.ordinal) {
            val line = "${timeFormat.get()!!.format(Date())} ${level.name.first()} $tag: $message" +
                (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
            synchronized(recent) {
                recent.addLast(line)
                while (recent.size > MAX_LINES) recent.removeFirst()
            }
        }
        try {
            sink.write(level, tag, message, error)
        } catch (_: Throwable) {
            // Logging must never break streaming.
        }
    }

    fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

    fun clearRecent() = synchronized(recent) { recent.clear() }
}
