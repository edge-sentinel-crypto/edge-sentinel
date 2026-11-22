package com.airbridge.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.airbridge.airplay.airplay.AirPlayProtocol
import com.airbridge.airplay.audio.CaptureHealthMonitor
import com.airbridge.airplay.session.SessionState

/** Manual IP:port entry for speakers that mDNS cannot see (other subnet, VLAN, guest Wi-Fi). */
@Composable
fun ManualReceiverDialog(onDismiss: () -> Unit, onAdd: (host: String, port: Int, protocol: AirPlayProtocol?, name: String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("7000") }
    var name by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf<String>("auto") }
    val hostValid = isIpLiteral(host.trim())
    val portValue = port.trim().toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add speaker by IP") },
        text = {
            Column {
                Text(
                    "Use this when a speaker does not show up automatically. AirPlay usually listens on port 7000 (older units: 5000).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, label = { Text("IP address") },
                    placeholder = { Text("192.168.1.40") }, singleLine = true,
                    isError = host.isNotBlank() && !hostValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") },
                    singleLine = true, isError = !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, label = { Text("Name (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                listOf("auto" to "Try AirPlay 2, then AirPlay 1", "2" to "AirPlay 2 only", "1" to "AirPlay 1 only").forEach { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = protocol == code, onClick = { protocol = code }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = protocol == code, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = hostValid && portValid,
                onClick = {
                    val proto = when (protocol) {
                        "1" -> AirPlayProtocol.AIRPLAY_1
                        "2" -> AirPlayProtocol.AIRPLAY_2
                        else -> null
                    }
                    onAdd(host.trim(), portValue!!, proto, name.trim())
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Accepts IPv4 dotted quads and IPv6 literals only (no DNS lookups from the app). */
fun isIpLiteral(s: String): Boolean {
    if (s.isEmpty()) return false
    val v4 = s.split('.')
    if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }) return true
    return s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
}

/** 4-digit code shown by an Apple TV (or other AirPlay 2 receiver) after a pairing request. */
@Composable
fun PinDialog(receiverName: String, message: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter the code") },
        text = {
            Column {
                Text(
                    message ?: "$receiverName is showing a 4-digit code on its screen. Type it here to pair once.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = pin.length >= 4, onClick = { onSubmit(pin) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** AirPlay password for receivers set to "require password". Kept in memory only. */
@Composable
fun PasswordDialog(receiverName: String, message: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speaker password") },
        text = {
            Column {
                Text(message ?: "$receiverName asks for its AirPlay password.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(64) },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "AirBridge keeps this only until the app closes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { onSubmit(password) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Diagnostics + help sheet: capture status, the Spotify limitation, per-speaker counters, log tail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(state: MainUiState, logLines: List<String>, onCopyLog: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Diagnostics & help", style = MaterialTheme.typography.titleLarge)
            Text("Capture: ${captureText(state)}", style = MaterialTheme.typography.bodyMedium)

            HelpBlock(
                "Spotify plays, speakers stay silent?",
                "Spotify (and some other DRM-protected apps) tell Android not to share their audio with capture apps. " +
                    "Android then hands AirBridge digital silence, and AirBridge shows a warning after 3 seconds. " +
                    "No app can get around this without root. For Spotify, use Spotify Connect (built into Sonos, " +
                    "Denon/Marantz HEOS, Yamaha MusicCast, WiiM and many others), or play the music from an app that allows capture.",
            )
            HelpBlock(
                "Speaker not listed?",
                "Phone and speaker must be on the same Wi-Fi network (not a guest network). Tap the search icon to look again, " +
                    "or add the speaker by its IP address with the + icon.",
            )
            HelpBlock(
                "Asked for a code or password?",
                "Apple TV can show a 4-digit code: enter it once and AirBridge remembers the pairing. " +
                    "Speakers set to \"require password\" ask for their AirPlay password each time the app starts.",
            )
            HelpBlock(
                "Music stops when the screen turns off?",
                "Allow AirBridge to ignore battery optimization (the card on the main screen, or Android Settings > Apps > AirBridge > Battery > Unrestricted).",
            )

            HorizontalDivider()
            Text("Speakers", style = MaterialTheme.typography.titleMedium)
            val connected = state.receivers.filter { it.status != null }
            if (connected.isEmpty()) {
                Text("No speaker sessions yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            connected.forEach { row ->
                val s = row.status!!
                Text(
                    "${row.receiver.name}: ${s.state.label()} via ${s.protocol?.label ?: "-"}; packets ${s.packetsSent}, resent ${s.retransmits}, dropped ${s.droppedFrames}" +
                        (s.message?.let { " - $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCopyLog) { Text("Copy") }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                val tail = logLines.takeLast(120)
                if (tail.isEmpty()) Text("(empty)", style = MaterialTheme.typography.bodySmall)
                tail.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun captureText(state: MainUiState): String = when (state.stream.phase) {
    com.airbridge.airplay.StreamPhase.STREAMING -> when (state.health) {
        CaptureHealthMonitor.Status.OK -> "receiving audio"
        CaptureHealthMonitor.Status.NOTHING_PLAYING -> "silent (no app is playing)"
        CaptureHealthMonitor.Status.BLOCKED -> "silent while music plays: the app blocks capture"
        CaptureHealthMonitor.Status.STARTING -> "starting"
    }
    else -> "not running"
}

private fun SessionState.label(): String = name.lowercase().replace('_', ' ')
