package com.airbridge.airplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbridge.airplay.StreamPhase
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.airplay.SyncOffset
import com.airbridge.airplay.audio.CaptureHealthMonitor
import com.airbridge.airplay.audio.LevelMeter
import com.airbridge.airplay.mdns.AirPlayReceiver
import com.airbridge.airplay.mdns.ProtocolPreference
import com.airbridge.airplay.multiroom.SpeakerGroup
import com.airbridge.airplay.session.SessionState
import com.airbridge.airplay.ui.theme.AirBridgeColors
import kotlin.math.roundToInt

/** Callbacks from the screen to the activity / view model. */
interface ScreenActions {
    fun onStartStreaming()
    fun onStopStreaming()
    fun onToggle(r: AirPlayReceiver, enabled: Boolean)
    fun onVolume(id: String, percent: Int)
    fun onPreference(r: AirPlayReceiver, pref: ProtocolPreference)
    fun onRetry(id: String)
    fun onRefresh()
    fun onAddManual()
    fun onRemoveManual(r: AirPlayReceiver)
    fun onEnterPin(r: ReceiverUi)
    fun onEnterPassword(r: ReceiverUi)
    fun onShowDiagnostics()
    fun onBatteryAllow()
    fun onBatteryDismiss()

    /** Master slider moved (0..100); called on every step of a drag. */
    fun onMasterVolume(percent: Int)

    /** Master slider released. */
    fun onMasterVolumeDone()

    /** Quick toggle: true = switch on every listed speaker, false = switch every speaker off. */
    fun onAllSpeakers(on: Boolean)

    fun onApplyGroup(groupId: String)
    fun onSaveGroup()
    fun onEditGroup(group: SpeakerGroup)
    fun onSyncOffset(r: ReceiverUi)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirBridgeScreen(state: MainUiState, showBatteryCard: Boolean, actions: ScreenActions) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("AirBridge", fontWeight = FontWeight.SemiBold)
                        Text(
                            "AirPlay from your phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions::onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Search again") }
                    IconButton(onClick = actions::onAddManual) { Icon(Icons.Filled.Add, contentDescription = "Add speaker by IP address") }
                    IconButton(onClick = actions::onShowDiagnostics) { Icon(Icons.Filled.Info, contentDescription = "Diagnostics and help") }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") { HeroStreamCard(state, actions) }
            if (state.stream.phase == StreamPhase.STREAMING && state.health == CaptureHealthMonitor.Status.BLOCKED) {
                item(key = "blocked") { CaptureBlockedBanner(actions::onShowDiagnostics) }
            }
            if (showBatteryCard) item(key = "battery") { BatteryCard(actions) }
            item(key = "header") {
                SectionHeader(
                    title = "Speakers on your Wi-Fi",
                    trailing = if (state.receivers.isEmpty()) null else "${state.receivers.size} found",
                )
            }
            if (state.receivers.isEmpty()) {
                item(key = "empty") { EmptyReceivers(actions::onAddManual) }
            } else {
                item(key = "multiroom") { MultiRoomBar(state, actions) }
                if (state.enabledCount > 0) item(key = "master") { MasterVolumeCard(state, actions) }
                items(state.receivers, key = { it.receiver.id }) { row -> ReceiverCard(row, state.stream.phase, actions) }
            }
            item(key = "footer") {
                Text(
                    "Audio stays on your local network. AirBridge has no accounts, ads or analytics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HeroStreamCard(state: MainUiState, actions: ScreenActions) {
    val phase = state.stream.phase
    val streaming = phase == StreamPhase.STREAMING
    val gradient = Brush.linearGradient(
        listOf(Color(0xFF1B2A4A), Color(0xFF16203A), Color(0xFF10322F)),
    )
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .background(gradient)
                .border(1.dp, Color(0x334F8CFF), RoundedCornerShape(28.dp))
                .padding(20.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (streaming) AirBridgeColors.Teal.copy(alpha = 0.18f) else Color(0x224F8CFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Broadcast,
                        contentDescription = null,
                        tint = if (streaming) AirBridgeColors.Teal else AirBridgeColors.Blue,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(heroTitle(state), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        heroSubtitle(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AirBridgeColors.Slate300,
                    )
                }
                CountPill(active = state.activeCount, enabled = state.enabledCount)
            }
            Spacer(Modifier.height(18.dp))
            AudioMeter(level = state.level, live = streaming)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (streaming) meterCaption(state) else "Level meter shows the audio being captured",
                    style = MaterialTheme.typography.labelSmall,
                    color = AirBridgeColors.Slate400,
                    modifier = Modifier.weight(1f),
                )
                if (streaming) {
                    Text(
                        String.format(java.util.Locale.US, "%.0f dBFS", state.level.displayPeakDb.coerceAtLeast(-60f)),
                        style = MaterialTheme.typography.labelSmall,
                        color = AirBridgeColors.Slate400,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            when (phase) {
                StreamPhase.IDLE -> Button(
                    onClick = actions::onStartStreaming,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AirBridgeColors.Blue),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start streaming", style = MaterialTheme.typography.labelLarge)
                }
                StreamPhase.STARTING, StreamPhase.STOPPING -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (phase == StreamPhase.STARTING) "Starting…" else "Stopping…")
                }
                StreamPhase.STREAMING -> FilledTonalButton(
                    onClick = actions::onStopStreaming,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0x33FF6B6B),
                        contentColor = Color(0xFFFFD6D6),
                    ),
                ) {
                    Icon(AppIcons.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop streaming", style = MaterialTheme.typography.labelLarge)
                }
            }
            val message = state.stream.message
            if (message != null && phase == StreamPhase.IDLE) {
                Spacer(Modifier.height(10.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = AirBridgeColors.Amber)
            }
        }
    }
}

private fun heroTitle(state: MainUiState): String = when (state.stream.phase) {
    StreamPhase.IDLE -> "Ready"
    StreamPhase.STARTING -> "Starting capture"
    StreamPhase.STOPPING -> "Stopping"
    StreamPhase.STREAMING -> when (state.activeCount) {
        0 -> "Capturing audio"
        1 -> "Streaming to 1 speaker"
        else -> "Streaming to ${state.activeCount} speakers"
    }
}

private fun heroSubtitle(state: MainUiState): String = when (state.stream.phase) {
    StreamPhase.IDLE -> if (state.enabledCount == 0) "Turn on one or more speakers, then start" else "Play music in any app, then tap Start"
    StreamPhase.STARTING -> "Accept Android's capture prompt"
    StreamPhase.STOPPING -> "Disconnecting speakers"
    StreamPhase.STREAMING -> when (state.health) {
        CaptureHealthMonitor.Status.BLOCKED -> "The playing app seems to block capture"
        CaptureHealthMonitor.Status.NOTHING_PLAYING -> "Waiting for audio from another app"
        else -> if (state.enabledCount == 0) "Turn on a speaker below" else "About 2 seconds behind your phone"
    }
}

private fun meterCaption(state: MainUiState): String = when (state.health) {
    CaptureHealthMonitor.Status.OK -> "Live audio"
    CaptureHealthMonitor.Status.NOTHING_PLAYING -> "Silence: nothing is playing"
    CaptureHealthMonitor.Status.BLOCKED -> "Silence: the app seems to block capture"
    CaptureHealthMonitor.Status.STARTING -> "Listening…"
}

@Composable
private fun CountPill(active: Int, enabled: Int) {
    Surface(shape = RoundedCornerShape(50), color = Color(0x22FFFFFF)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Speaker, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("$active/$enabled", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

/** Segmented level meter: 28 bars, teal -> amber -> red, peak segment highlighted. */
@Composable
fun AudioMeter(level: LevelMeter.Level, live: Boolean) {
    val rms by animateFloatAsState(if (live) level.rmsFraction else 0f, tween(90), label = "rms")
    val peak by animateFloatAsState(if (live) level.peakFraction else 0f, tween(120), label = "peak")
    val segments = 28
    Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until segments) {
            val pos = (i + 0.5f) / segments
            val lit = pos <= rms
            val isPeak = live && peak > 0.02f && kotlin.math.abs(pos - peak) < 0.5f / segments
            val base = when {
                pos > 0.9f -> AirBridgeColors.Red
                pos > 0.75f -> AirBridgeColors.Amber
                else -> AirBridgeColors.Teal
            }
            val color = when {
                lit || isPeak -> base
                else -> Color(0x1FFFFFFF)
            }
            val h = 0.45f + 0.55f * pos
            Box(
                Modifier.weight(1f).fillMaxSize(fraction = 1f)
                    .padding(top = (30 * (1 - h)).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Banner text for [CaptureHealthMonitor.Status.BLOCKED]: music is active but the captured audio has
 * been digital silence for about 3 seconds. Android's playback capture works with most media apps;
 * this happens only when the playing app (or a version of it) opts out of capture.
 */
internal const val CAPTURE_BLOCKED_TEXT =
    "The app that is playing (or this version of it) seems to block Android's playback capture, so " +
        "AirBridge receives silence. Check that app's settings, try another player, or use Spotify " +
        "Connect or Cast where your speaker supports it."

@Composable
private fun CaptureBlockedBanner(onHelp: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = AirBridgeColors.Amber)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Music is playing, but AirBridge hears silence", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(4.dp))
                Text(
                    CAPTURE_BLOCKED_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onHelp, contentPadding = PaddingValues(0.dp)) { Text("What can I do?") }
            }
        }
    }
}

@Composable
private fun BatteryCard(actions: ScreenActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(AppIcons.BatteryAlert, contentDescription = null, tint = AirBridgeColors.Amber)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Keep streaming with the screen off", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Some phones pause background apps to save battery. Allow AirBridge to run unrestricted so music doesn't cut out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = actions::onBatteryAllow, shape = RoundedCornerShape(12.dp)) { Text("Allow") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = actions::onBatteryDismiss) { Text("Not now") }
                }
            }
        }
    }
}

@Composable
private fun EmptyReceivers(onAddManual: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text("Looking for AirPlay speakers…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Make sure your phone is on the same Wi-Fi network as your speakers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAddManual) { Text("Add a speaker by IP address") }
        }
    }
}

@Composable
fun ReceiverCard(row: ReceiverUi, phase: StreamPhase, actions: ScreenActions) {
    val r = row.receiver
    val state = row.state
    val active = state == SessionState.STREAMING
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (row.enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (active) AirBridgeColors.Teal.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Speaker, contentDescription = null, tint = if (active) AirBridgeColors.Teal else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (r.isLocked) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.Lock, contentDescription = "Needs a code or password", modifier = Modifier.size(15.dp), tint = AirBridgeColors.Amber)
                        }
                        if (row.syncOffsetMs != 0) {
                            Spacer(Modifier.width(6.dp))
                            SyncOffsetBadge(row.syncOffsetMs)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProtocolBadge(row)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            r.modelLabel + " · " + r.host,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Two lines, so the address stays readable next to the wider fallback badge.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ReceiverMenu(row, actions)
                Switch(
                    checked = row.enabled,
                    onCheckedChange = { actions.onToggle(r, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = AirBridgeColors.Blue),
                )
            }
            AnimatedVisibility(visible = row.enabled) {
                Column(Modifier.padding(top = 8.dp, end = 8.dp)) {
                    StatusLine(row, phase, actions)
                    VolumeRow(row, actions)
                }
            }
        }
    }
}

/**
 * "AirPlay 2" (blue) or "AirPlay 1" (grey) badge. After an automatic fallback (Auto mode, AirPlay 2
 * failed, for example because the speaker rejects NTP timing) it reads "AirPlay 1 (fallback)" in
 * amber, so it is clear why an AirPlay 2 speaker plays over AirPlay 1.
 */
@Composable
private fun ProtocolBadge(row: ReceiverUi) {
    val fallback = row.isFallback
    val ap2 = row.protocol == AirPlayProtocol.AIRPLAY_2
    val color = when {
        fallback -> AirBridgeColors.Amber
        ap2 -> AirBridgeColors.Blue
        else -> AirBridgeColors.Slate400
    }
    val textColor = when {
        fallback -> Color(0xFFFFD9A0)
        ap2 -> Color(0xFFB9D0FF)
        else -> AirBridgeColors.Slate300
    }
    val description = if (fallback) "${row.protocolLabel}: AirPlay 2 did not connect, so AirPlay 1 is used" else row.protocolLabel
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(row.protocolLabel, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1)
    }
}

@Composable
private fun StatusLine(row: ReceiverUi, phase: StreamPhase, actions: ScreenActions) {
    val status = row.status
    val (dot, text) = when {
        phase != StreamPhase.STREAMING -> AirBridgeColors.Slate400 to "Will connect when you start streaming"
        status == null -> AirBridgeColors.Slate400 to "Waiting for speaker on the network"
        else -> when (status.state) {
            SessionState.CONNECTING -> AirBridgeColors.Amber to (status.message ?: "Connecting…")
            SessionState.STREAMING -> AirBridgeColors.Teal to "Playing · ${status.protocolLabel ?: ""}"
            SessionState.NEEDS_PIN -> AirBridgeColors.Amber to (status.message ?: "Enter the code shown on the TV")
            SessionState.NEEDS_PASSWORD -> AirBridgeColors.Amber to (status.message ?: "Password required")
            SessionState.RETRYING -> AirBridgeColors.Amber to (status.message ?: "Reconnecting…")
            SessionState.FAILED -> AirBridgeColors.Red to (status.message ?: "Could not connect")
            SessionState.STOPPED -> AirBridgeColors.Slate400 to "Stopped"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        when (status?.state) {
            SessionState.NEEDS_PIN -> TextButton(onClick = { actions.onEnterPin(row) }) { Text("Enter code") }
            SessionState.NEEDS_PASSWORD -> TextButton(onClick = { actions.onEnterPassword(row) }) { Text("Enter password") }
            SessionState.FAILED, SessionState.RETRYING -> TextButton(onClick = { actions.onRetry(row.receiver.id) }) { Text("Retry") }
            else -> Unit
        }
    }
}

@Composable
private fun VolumeRow(row: ReceiverUi, actions: ScreenActions) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(row.volume.toFloat()) }
    val value = if (dragging) local else row.volume.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.VolumeUp, contentDescription = "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Slider(
            value = value,
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { dragging = false; actions.onVolume(row.receiver.id, local.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = AirBridgeColors.Blue,
                inactiveTrackColor = Color(0x2EFFFFFF),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("${value.toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReceiverMenu(row: ReceiverUi, actions: ScreenActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Speaker options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Protocol",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val auto = "Automatic (${row.receiver.recommendedProtocol.label})"
            listOf(
                ProtocolPreference.AUTO to auto,
                ProtocolPreference.AIRPLAY_2 to "Force AirPlay 2",
                ProtocolPreference.AIRPLAY_1 to "Force AirPlay 1",
            ).forEach { (pref, label) ->
                DropdownMenuItem(
                    text = { Text((if (row.preference == pref) "✓  " else "     ") + label) },
                    onClick = { open = false; actions.onPreference(row.receiver, pref) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Sync offset") },
                leadingIcon = { Icon(AppIcons.Tune, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    Text(
                        if (row.syncOffsetMs == 0) "0 ms" else SyncOffset.badge(row.syncOffsetMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { open = false; actions.onSyncOffset(row) },
            )
            if (row.receiver.manual) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Remove speaker") }, onClick = { open = false; actions.onRemoveManual(row.receiver) })
            }
        }
    }
}

/** Small pill on a speaker card when its sync offset is not 0 ("+40 ms" / "-25 ms"). */
@Composable
private fun SyncOffsetBadge(ms: Int) {
    val description = "Sync offset " + SyncOffset.label(ms)
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(AirBridgeColors.Teal.copy(alpha = 0.14f))
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(SyncOffset.badge(ms), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FEBDD), maxLines = 1)
    }
}

/**
 * Multi-room bar above the speaker list: the All on / All off quick toggle, one chip per saved
 * group (tap: play that group; long-press: rename or delete) and "Save group". Chips flow onto a
 * second line instead of scrolling sideways, so every group stays visible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiRoomBar(state: MainUiState, actions: ScreenActions) {
    val allOn = state.allOn
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BarChip(
            label = if (allOn) "All off" else "All on",
            icon = AppIcons.SpeakerGroup,
            tonal = true,
            contentDescription = if (allOn) "Turn all speakers off" else "Turn all speakers on",
            onClick = { actions.onAllSpeakers(!allOn) },
        )
        state.groupChips.forEach { chip ->
            val name = chip.group.name
            val label = if (chip.offline > 0) "$name · ${chip.available}/${chip.total}" else name
            BarChip(
                label = label,
                icon = if (chip.active) Icons.Filled.Check else null,
                selected = chip.active,
                dimmed = chip.available == 0, // still long-pressable, so it can be renamed or deleted
                contentDescription = "Group $name" + (if (chip.offline > 0) ", ${chip.offline} of ${chip.total} speakers not found" else ""),
                onClick = { actions.onApplyGroup(chip.group.id) },
                onLongClick = { actions.onEditGroup(chip.group) },
                onLongClickLabel = "Rename or delete",
            )
        }
        BarChip(
            label = "Save group",
            icon = Icons.Filled.Add,
            enabled = state.enabledCount > 0,
            contentDescription = "Save the speakers that are on as a group",
            onClick = actions::onSaveGroup,
        )
    }
}

/** Compact Material 3 style chip with optional long-press (used by the multi-room bar). */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable (tap + long-press)
@Composable
private fun BarChip(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    selected: Boolean = false,
    tonal: Boolean = false,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val container = when {
        selected -> AirBridgeColors.Blue.copy(alpha = 0.24f)
        tonal -> AirBridgeColors.Blue.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val outline = when {
        selected -> AirBridgeColors.Blue.copy(alpha = 0.75f)
        tonal -> AirBridgeColors.Blue.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.outline
    }
    val faded = !enabled || dimmed
    val content = when {
        faded -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected || tonal -> Color(0xFFDCE6FF)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(34.dp)
            .clip(shape)
            .background(if (faded) MaterialTheme.colorScheme.surfaceContainerLow else container)
            .border(1.dp, if (faded) MaterialTheme.colorScheme.outlineVariant else outline, shape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                this.selected = selected
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .padding(start = if (icon != null) 10.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * Master volume for the speakers that are on. Shows the loudest speaker's level; dragging scales
 * every speaker and keeps their balance. While dragging, the thumb follows the finger (local
 * value); otherwise it shows the derived level, so speaker sliders never feed back into it.
 */
@Composable
private fun MasterVolumeCard(state: MainUiState, actions: ScreenActions) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(state.masterVolume.toFloat()) }
    val value = if (dragging) local else state.masterVolume.toFloat()
    val count = state.enabledCount
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Master volume", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (count == 1) "1 speaker on" else "$count speakers · balance kept",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.VolumeUp, contentDescription = null, tint = AirBridgeColors.Teal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = value,
                    onValueChange = {
                        dragging = true
                        local = it
                        actions.onMasterVolume(it.roundToInt())
                    },
                    onValueChangeFinished = {
                        dragging = false
                        actions.onMasterVolumeDone()
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = AirBridgeColors.Teal,
                        inactiveTrackColor = Color(0x2EFFFFFF),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "Master volume"
                        stateDescription = "${value.roundToInt()} percent"
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${value.roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(40.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
