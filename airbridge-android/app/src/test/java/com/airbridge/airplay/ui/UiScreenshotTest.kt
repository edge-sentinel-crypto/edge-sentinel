package com.airbridge.airplay.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.airbridge.airplay.StreamPhase
import com.airbridge.airplay.StreamUiState
import com.airbridge.airplay.audio.CaptureHealthMonitor
import com.airbridge.airplay.audio.LevelMeter
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.session.ReceiverStatus
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.ui.theme.AirBridgeTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real Compose screen with sample data (Robolectric native graphics) to PNG files so
 * the UI can be reviewed without a phone. Optional: excluded unless -Pairbridge.screenshots is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi", application = android.app.Application::class)
class UiScreenshotTest {
    private val noop = object : ScreenActions {
        override fun onStartStreaming() = Unit
        override fun onStopStreaming() = Unit
        override fun onToggle(r: AirPlayReceiver, enabled: Boolean) = Unit
        override fun onVolume(id: String, percent: Int) = Unit
        override fun onPreference(r: AirPlayReceiver, pref: ProtocolPreference) = Unit
        override fun onRetry(id: String) = Unit
        override fun onRefresh() = Unit
        override fun onAddManual() = Unit
        override fun onRemoveManual(r: AirPlayReceiver) = Unit
        override fun onEnterPin(r: ReceiverUi) = Unit
        override fun onEnterPassword(r: ReceiverUi) = Unit
        override fun onShowDiagnostics() = Unit
        override fun onBatteryAllow() = Unit
        override fun onBatteryDismiss() = Unit
    }

    private val homePod = AirPlayReceiver(
        "A1B2C3D4E5F6", "Kitchen", "192.168.1.40", raopPort = 7000, airplayPort = 7000,
        airplayTxt = mapOf("features" to "0x4A7FCA00,0x56BD0", "model" to "AudioAccessory5,1"),
    )
    private val sonos = AirPlayReceiver(
        "7828CAC3238C", "Living Room", "192.168.1.31", raopPort = 7000, airplayPort = 7000,
        airplayTxt = mapOf("features" to "0x445F8A00,0x1C340", "model" to "One", "manufacturer" to "Sonos"),
    )
    private val appleTv = AirPlayReceiver(
        "0011223344AA", "Family Room TV", "192.168.1.50", raopPort = 7000, airplayPort = 7000,
        airplayTxt = mapOf("features" to "0x4A7FFFF7,0x3C155FDE", "flags" to "0x30644", "model" to "AppleTV6,2"),
    )
    private val avr = AirPlayReceiver(
        "5C5A3C112233", "Den Receiver", "192.168.1.70", raopPort = 7000,
        raopTxt = mapOf("am" to "AVR-X3700H", "ft" to "0x44F0A00", "cn" to "0,1", "et" to "0,4"),
    )

    private fun row(r: AirPlayReceiver, enabled: Boolean, state: SessionState? = null, volume: Int = 50, message: String? = null) =
        ReceiverUi(
            receiver = r,
            enabled = enabled,
            status = state?.let { ReceiverStatus(r.id, r.name, it, r.recommendedProtocol, volume, message, packetsSent = 4210) },
            volume = volume,
            preference = ProtocolPreference.AUTO,
        )

    private fun render(name: String, state: MainUiState, battery: Boolean = false) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { AirBridgeTheme { AirBridgeScreen(state, battery, noop) } }
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File(System.getProperty("airbridge.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val loud = LevelMeter.Level(rmsDb = -14f, peakDb = -6f, displayPeakDb = -6f)

    /** Launcher icon (adaptive icon: background color + vector foreground) and the status-bar icon. */
    @Test
    fun appIcons() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val dir = File(System.getProperty("airbridge.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        for ((name, res, size) in listOf(
            Triple("00-launcher-icon", com.airbridge.airplay.R.mipmap.ic_launcher, 432),
            Triple("00-status-icon", com.airbridge.airplay.R.drawable.ic_stat_airbridge, 96),
        )) {
            val d = activity.getDrawable(res)!!
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (name == "00-status-icon") canvas.drawColor(0xFF202833.toInt())
            d.setBounds(0, 0, size, size)
            d.draw(canvas)
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun idleWithSpeakers() = render(
        "01-ready",
        MainUiState(
            stream = StreamUiState(StreamPhase.IDLE),
            receivers = listOf(row(appleTv, false), row(homePod, true, volume = 65), row(sonos, true, volume = 40), row(avr, false)),
        ),
        battery = true,
    )

    @Test
    fun streamingToTwoSpeakers() = render(
        "02-streaming",
        MainUiState(
            stream = StreamUiState(StreamPhase.STREAMING),
            level = loud,
            health = CaptureHealthMonitor.Status.OK,
            receivers = listOf(
                row(appleTv, true, SessionState.NEEDS_PIN, message = "Family Room TV is showing a 4-digit code; enter it in AirBridge"),
                row(homePod, true, SessionState.STREAMING, volume = 65),
                row(sonos, true, SessionState.STREAMING, volume = 40),
                row(avr, false),
            ),
        ),
    )

    @Test
    fun spotifyBlockedWarning() = render(
        "03-capture-blocked",
        MainUiState(
            stream = StreamUiState(StreamPhase.STREAMING),
            level = LevelMeter.Level(LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB),
            health = CaptureHealthMonitor.Status.BLOCKED,
            receivers = listOf(row(homePod, true, SessionState.STREAMING, volume = 65), row(avr, true, SessionState.STREAMING, volume = 55)),
        ),
    )
}
