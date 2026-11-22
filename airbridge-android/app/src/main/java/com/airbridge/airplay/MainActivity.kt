package com.airbridge.airplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbridge.airplay.audio.AudioCaptureService
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.multiroom.GroupChange
import com.airbridge.airplay.multiroom.SpeakerGroup
import com.airbridge.airplay.ui.AirBridgeScreen
import com.airbridge.airplay.ui.DiagnosticsSheet
import com.airbridge.airplay.ui.EditGroupDialog
import com.airbridge.airplay.ui.MainViewModel
import com.airbridge.airplay.ui.ManualReceiverDialog
import com.airbridge.airplay.ui.PasswordDialog
import com.airbridge.airplay.ui.PinDialog
import com.airbridge.airplay.ui.ReceiverUi
import com.airbridge.airplay.ui.SaveGroupDialog
import com.airbridge.airplay.ui.ScreenActions
import com.airbridge.airplay.ui.SyncOffsetDialog
import com.airbridge.airplay.ui.theme.AirBridgeTheme

/**
 * Single-activity UI. Handles the Android-side permission flow for streaming:
 * RECORD_AUDIO (+ POST_NOTIFICATIONS on 13+) -> MediaProjection consent -> start the capture
 * foreground service with the one-time consent token.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val controller get() = viewModel.controller

    private var showManual by mutableStateOf(false)
    private var showDiagnostics by mutableStateOf(false)
    private var pinFor by mutableStateOf<ReceiverUi?>(null)
    private var passwordFor by mutableStateOf<ReceiverUi?>(null)
    private var syncOffsetFor by mutableStateOf<ReceiverUi?>(null)
    private var showSaveGroup by mutableStateOf(false)
    private var editGroup by mutableStateOf<SpeakerGroup?>(null)
    private var batteryCardVisible by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true || hasRecordPermission()) {
            requestProjection()
        } else {
            controller.onStartCancelled("AirBridge needs the audio capture permission to stream your phone's audio.")
        }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, AudioCaptureService.startIntent(this, result.resultCode, data))
        } else {
            controller.onStartCancelled("Capture was not allowed, so streaming did not start.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AirBridgeTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                AirBridgeScreen(state, batteryCardVisible, actions)
                if (showManual) {
                    ManualReceiverDialog(
                        onDismiss = { showManual = false },
                        onAdd = { host, port, protocol, name ->
                            showManual = false
                            val r = viewModel.addManual(host, port, protocol, name)
                            viewModel.setEnabled(r, true)
                        },
                    )
                }
                pinFor?.let { row ->
                    PinDialog(
                        receiverName = row.receiver.name,
                        message = row.status?.message,
                        onDismiss = { pinFor = null },
                        onSubmit = { pin -> pinFor = null; viewModel.providePin(row.receiver.id, pin) },
                    )
                }
                passwordFor?.let { row ->
                    PasswordDialog(
                        receiverName = row.receiver.name,
                        message = row.status?.message,
                        onDismiss = { passwordFor = null },
                        onSubmit = { pw -> passwordFor = null; viewModel.providePassword(row.receiver.id, pw) },
                    )
                }
                syncOffsetFor?.let { row ->
                    SyncOffsetDialog(
                        receiverName = row.receiver.name,
                        initialMs = row.syncOffsetMs,
                        onChange = { ms -> viewModel.setSyncOffset(row.receiver.id, ms) },
                        onDismiss = { syncOffsetFor = null },
                    )
                }
                if (showSaveGroup) {
                    SaveGroupDialog(
                        speakerNames = state.receivers.filter { it.enabled }.map { it.receiver.name },
                        existingNames = state.groups.map { it.name },
                        onDismiss = { showSaveGroup = false },
                        onSave = { name ->
                            showSaveGroup = false
                            report(viewModel.saveGroup(name))
                        },
                    )
                }
                editGroup?.let { group ->
                    val byId = state.receivers.associateBy { it.receiver.id }
                    EditGroupDialog(
                        group = group,
                        memberNames = group.receiverIds.mapNotNull { byId[it]?.receiver?.name },
                        offlineCount = group.receiverIds.count { it !in byId },
                        otherGroupNames = state.groups.filter { it.id != group.id }.map { it.name },
                        onDismiss = { editGroup = null },
                        onRename = { name ->
                            editGroup = null
                            report(viewModel.renameGroup(group.id, name))
                        },
                        onDelete = {
                            editGroup = null
                            viewModel.deleteGroup(group.id)
                        },
                    )
                }
                if (showDiagnostics) {
                    DiagnosticsSheet(
                        state = state,
                        logLines = viewModel.recentLog(),
                        onCopyLog = { copyLog() },
                        onDismiss = { showDiagnostics = false },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.startDiscovery()
        batteryCardVisible = !controller.settings.batteryPromptDismissed && !isIgnoringBatteryOptimizations()
    }

    override fun onStop() {
        controller.stopDiscovery()
        super.onStop()
    }

    private val actions = object : ScreenActions {
        override fun onStartStreaming() = startStreamingFlow()
        override fun onStopStreaming() {
            startService(AudioCaptureService.stopIntent(this@MainActivity))
        }
        override fun onToggle(r: AirPlayReceiver, enabled: Boolean) = viewModel.setEnabled(r, enabled)
        override fun onVolume(id: String, percent: Int) = viewModel.setVolume(id, percent)
        override fun onPreference(r: AirPlayReceiver, pref: ProtocolPreference) = viewModel.setPreference(r, pref)
        override fun onRetry(id: String) = viewModel.retry(id)
        override fun onRefresh() = viewModel.refresh()
        override fun onAddManual() {
            showManual = true
        }
        override fun onRemoveManual(r: AirPlayReceiver) = viewModel.removeManual(r)
        override fun onEnterPin(r: ReceiverUi) {
            pinFor = r
        }
        override fun onEnterPassword(r: ReceiverUi) {
            passwordFor = r
        }
        override fun onShowDiagnostics() {
            showDiagnostics = true
        }
        override fun onBatteryAllow() = requestIgnoreBatteryOptimizations()
        override fun onBatteryDismiss() {
            controller.settings.batteryPromptDismissed = true
            batteryCardVisible = false
        }
        override fun onMasterVolume(percent: Int) = viewModel.dragMasterVolume(percent)
        override fun onMasterVolumeDone() = viewModel.endMasterVolumeDrag()
        override fun onAllSpeakers(on: Boolean) = viewModel.setAllSpeakers(on)
        override fun onApplyGroup(groupId: String) {
            if (!viewModel.applyGroup(groupId)) {
                Toast.makeText(this@MainActivity, "None of this group's speakers is on the network right now", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onSaveGroup() {
            showSaveGroup = true
        }
        override fun onEditGroup(group: SpeakerGroup) {
            editGroup = group
        }
        override fun onSyncOffset(r: ReceiverUi) {
            syncOffsetFor = r
        }
    }

    /** Explains a rejected group change (for example a name that another group already uses). */
    private fun report(change: GroupChange) {
        if (change is GroupChange.Rejected) Toast.makeText(this, change.reason, Toast.LENGTH_SHORT).show()
    }

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startStreamingFlow() {
        controller.onStartRequested()
        val needed = ArrayList<String>()
        if (!hasRecordPermission()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) requestProjection() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+: offer only "entire screen" so playback capture covers every app.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife") // Sideloaded personal app: asking for unrestricted battery is intended.
    private fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        batteryCardVisible = false
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AirBridge log", viewModel.recentLog().joinToString("\n")))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }
}
