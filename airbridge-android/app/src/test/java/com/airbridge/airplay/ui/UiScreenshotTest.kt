package com.airbridge.airplay.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.airbridge.airplay.StreamPhase
import com.airbridge.airplay.StreamUiState
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.audio.CaptureHealthMonitor
import com.airbridge.airplay.audio.LevelMeter
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.multiroom.SpeakerGroup
import com.airbridge.airplay.session.ReceiverStatus
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.ui.theme.AirBridgeTheme
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real Compose screen with sample data (Robolectric native graphics) to PNG files so
 * the UI can be reviewed without a phone. Optional: excluded unless -Pairbridge.screenshots is set.
 * Speaker names are made up and the addresses come from the RFC 5737 documentation range
 * (192.0.2.0/24), so no real network appears in the images.
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
        override fun onMasterVolume(percent: Int) = Unit
        override fun onMasterVolumeDone() = Unit
        override fun onAllSpeakers(on: Boolean) = Unit
        override fun onApplyGroup(groupId: String) = Unit
        override fun onSaveGroup() = Unit
        override fun onEditGroup(group: SpeakerGroup) = Unit
        override fun onSyncOffset(r: ReceiverUi) = Unit
    }

    private val homePod = AirPlayReceiver(
        "A1B2C3D4E5F6", "Kitchen", "192.0.2.40", raopPort = 7000, airplayPort = 7000,
        airplayTxt = mapOf("features" to "0x4A7FCA00,0x56BD0", "model" to "AudioAccessory5,1"),
    )
    private val sonos = AirPlayReceiver(
        "7828CAC3238C", "Living Room", "192.0.2.31", raopPort = 7000, airplayPort = 7000,
        airplayTxt = mapOf("features" to "0x445F8A00,0x1C340", "model" to "One", "manufacturer" to "Sonos"),
    )
    private val appleTv = AirPlayReceiver(
        "0011223344AA", "Family Room TV", "192.0.2.50", raopPort = 7000, airplayPort = 7000,
        airplayTxt = mapOf("features" to "0x4A7FFFF7,0x3C155FDE", "flags" to "0x30644", "model" to "AppleTV6,2"),
    )
    private val avr = AirPlayReceiver(
        "5C5A3C112233", "Den Receiver", "192.0.2.70", raopPort = 7000,
        raopTxt = mapOf("am" to "AVR-X3700H", "ft" to "0x44F0A00", "cn" to "0,1", "et" to "0,4"),
    )

    private val groups = listOf(
        SpeakerGroup("g1", "Downstairs", linkedSetOf(avr.id, homePod.id)),
        SpeakerGroup("g2", "Movie night", linkedSetOf(appleTv.id, sonos.id)),
        SpeakerGroup("g3", "Everywhere", linkedSetOf(avr.id, appleTv.id, homePod.id, sonos.id)),
    )

    /** A generic AirPlay 2 speaker that also offers AirPlay 1 (for the fallback screenshot). */
    private val bedroom = AirPlayReceiver(
        "02AB34CD56EF", "Bedroom", "192.0.2.60", raopPort = 7000, airplayPort = 7000,
        airplayTxt = mapOf("features" to "0x401C4A00,0x1C340", "model" to "AirPlay speaker"),
        raopTxt = mapOf("cn" to "0,1", "et" to "0,4"),
    )

    private fun row(
        r: AirPlayReceiver,
        enabled: Boolean,
        state: SessionState? = null,
        volume: Int = 50,
        message: String? = null,
        offset: Int = 0,
        fallback: Boolean = false,
    ) =
        ReceiverUi(
            receiver = r,
            enabled = enabled,
            status = state?.let {
                if (fallback) {
                    ReceiverStatus(
                        r.id, r.name, it, AirPlayProtocol.AIRPLAY_1, volume, message, packetsSent = 4210, syncOffsetMs = offset,
                        fallbackFrom = AirPlayProtocol.AIRPLAY_2, fallbackReason = "AirPlay 2 failed: SETUP (session) failed with 500",
                    )
                } else {
                    ReceiverStatus(r.id, r.name, it, r.recommendedProtocol, volume, message, packetsSent = 4210, syncOffsetMs = offset)
                }
            },
            volume = volume,
            preference = ProtocolPreference.AUTO,
            syncOffsetMs = offset,
        )

    private fun outputDir() = File(System.getProperty("airbridge.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun save(bitmap: Bitmap, name: String) =
        FileOutputStream(File(outputDir(), "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun render(name: String, state: MainUiState, battery: Boolean = false, dialog: (@Composable () -> Unit)? = null) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            AirBridgeTheme {
                AirBridgeScreen(state, battery, noop)
                dialog?.invoke()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        if (dialog != null) {
            // A dialog has its own window: dim the screen behind it, then draw it centred on top.
            val window = ShadowDialog.getLatestDialog()?.window
            assertNotNull("dialog window", window)
            val dv = window!!.decorView
            canvas.drawColor(0x99000000.toInt())
            canvas.save()
            canvas.translate(((root.width - dv.width) / 2).toFloat(), ((root.height - dv.height) / 2).toFloat())
            dv.draw(canvas)
            canvas.restore()
        }
        save(bitmap, name)
    }

    private val loud = LevelMeter.Level(rmsDb = -14f, peakDb = -6f, displayPeakDb = -6f)

    /** Launcher icon (adaptive icon: background color + vector foreground) and the status-bar icon. */
    @Test
    fun appIcons() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
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
            save(bitmap, name)
        }
    }

    @Test
    fun idleWithSpeakers() = render(
        "01-ready",
        MainUiState(
            stream = StreamUiState(StreamPhase.IDLE),
            receivers = listOf(row(avr, true, volume = 55, offset = 40), row(appleTv, false), row(homePod, true, volume = 65), row(sonos, false)),
            groups = groups,
        ),
        battery = true,
    )

    /** Downstairs group playing (AirPlay 1 receiver + AirPlay 2 HomePod): master volume, group matched, +40 ms badge. */
    @Test
    fun streamingToTwoSpeakers() = render(
        "02-streaming",
        MainUiState(
            stream = StreamUiState(StreamPhase.STREAMING),
            level = loud,
            health = CaptureHealthMonitor.Status.OK,
            receivers = listOf(
                row(avr, true, SessionState.STREAMING, volume = 55, offset = 40),
                row(appleTv, false),
                row(homePod, true, SessionState.STREAMING, volume = 65),
                row(sonos, false),
            ),
            groups = groups,
        ),
    )

    /** Music is active but the captured audio is silent: the playing app seems to opt out of capture. */
    @Test
    fun captureBlockedWarning() = render(
        "03-capture-blocked",
        MainUiState(
            stream = StreamUiState(StreamPhase.STREAMING),
            level = LevelMeter.Level(LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB, LevelMeter.SILENCE_DB),
            health = CaptureHealthMonitor.Status.BLOCKED,
            receivers = listOf(row(avr, true, SessionState.STREAMING, volume = 55, offset = 40), row(homePod, true, SessionState.STREAMING, volume = 65)),
            groups = groups,
        ),
    )

    /** Every speaker on ("All off" offered, "Everywhere" matched), two sync badges, a PIN prompt. */
    @Test
    fun everySpeakerOn() = render(
        "04-multi-room",
        MainUiState(
            stream = StreamUiState(StreamPhase.STREAMING),
            level = loud,
            health = CaptureHealthMonitor.Status.OK,
            receivers = listOf(
                row(avr, true, SessionState.STREAMING, volume = 55, offset = 40),
                row(appleTv, true, SessionState.NEEDS_PIN, volume = 50, message = "Family Room TV is showing a 4-digit code; enter it in AirBridge"),
                row(homePod, true, SessionState.STREAMING, volume = 65),
                row(sonos, true, SessionState.STREAMING, volume = 40, offset = -25),
            ),
            groups = groups,
        ),
    )

    /** The per-speaker sync offset dialog, opened from the Den Receiver's menu. */
    @Test
    fun syncOffsetDialog() = render(
        "05-sync-offset",
        MainUiState(
            stream = StreamUiState(StreamPhase.STREAMING),
            level = loud,
            health = CaptureHealthMonitor.Status.OK,
            receivers = listOf(
                row(avr, true, SessionState.STREAMING, volume = 55, offset = 40),
                row(appleTv, false),
                row(homePod, true, SessionState.STREAMING, volume = 65),
                row(sonos, false),
            ),
            groups = groups,
        ),
        dialog = { SyncOffsetDialog(receiverName = "Den Receiver", initialMs = 40, onChange = {}, onDismiss = {}) },
    )

    /** Auto mode fell back to AirPlay 1 for the Bedroom speaker (it rejected AirPlay 2's NTP timing). */
    @Test
    fun airPlay1Fallback() = render(
        "06-airplay1-fallback",
        MainUiState(
            stream = StreamUiState(StreamPhase.STREAMING),
            level = loud,
            health = CaptureHealthMonitor.Status.OK,
            receivers = listOf(
                row(bedroom, true, SessionState.STREAMING, volume = 60, fallback = true),
                row(avr, true, SessionState.STREAMING, volume = 55, offset = 40),
                row(homePod, true, SessionState.STREAMING, volume = 65),
                row(sonos, false),
            ),
            groups = groups,
        ),
    )
}
