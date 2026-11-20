package com.oneproxyclick.app.ui.tools

import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.app.ui.pro.rememberProUnlocked
import com.oneproxyclick.app.ui.pro.ProLockedRow
import com.oneproxyclick.app.ControlServerState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneproxyclick.app.EngineSettings
import com.oneproxyclick.app.appGraph
import com.oneproxyclick.app.bridge.BridgeToken
import com.oneproxyclick.app.bridge.CliScript
import com.oneproxyclick.app.bridge.LocalForwarder
import com.oneproxyclick.app.ui.theme.MonoSmall
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.app.ui.theme.opcSwitchColors
import com.oneproxyclick.core.storage.export.ExportWriter
import com.oneproxyclick.core.storage.export.LiveJsonlRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KaliTermBridgeSection(engine: EngineSettings) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var token by remember { mutableStateOf(BridgeToken.get(context)) }
    var scriptStatus by remember { mutableStateOf(context.getString(R.string.br_exporting)) }
    var exportTick by remember { mutableIntStateOf(0) }
    var target by remember(engine.forwardTarget) { mutableStateOf(engine.forwardTarget) }
    var apiPortText by remember(engine.controlPort) { mutableStateOf(engine.controlPort.toString()) }
    val apiState by graph.controlServerState.collectAsState()
    val liveUnlocked = rememberProUnlocked(ProFeature.LIVE_JSON)
    val bridgeUnlocked = rememberProUnlocked(ProFeature.KALITERM_BRIDGE)

    val liveWritten by graph.liveJsonl.written.collectAsState()
    val liveError by graph.liveJsonl.lastError.collectAsState()
    val fwdSent by graph.forwarder.sent.collectAsState()
    val fwdFailed by graph.forwarder.failed.collectAsState()
    val fwdError by graph.forwarder.lastError.collectAsState()

    // Export the helper script every time Settings opens (and after token / API port changes).
    LaunchedEffect(exportTick, engine.controlPort) {
        scriptStatus = withContext(Dispatchers.IO) {
            runCatching { CliScript.export(context, engine.controlPort) }
                .fold({ "✔ ${it.displayPath}" }, { context.getString(R.string.br_export_failed, it.message.orEmpty()) })
        }
    }

    val dir = ExportWriter.PUBLIC_DIR
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.br_title), style = MaterialTheme.typography.titleMedium)

        if (liveUnlocked) {
            // ---- Live stream ----
            Text(stringResource(R.string.br_live_title), style = MaterialTheme.typography.labelLarge, color = OpcColors.Orange)
            Text(
                stringResource(if (engine.autoJsonLive) R.string.br_live_active else R.string.br_live_inactive),
                fontSize = 12.sp,
            )
            SelectionContainer { Text("$dir/${LiveJsonlRecorder.STREAM_FILE}", style = MonoSmall) }
            Text(
                stringResource(R.string.br_live_count, liveWritten) +
                    (liveError?.let { " · " + stringResource(R.string.br_last_error, it) } ?: ""),
                fontSize = 11.sp,
                color = if (liveError != null) OpcColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
            )

        } else {
            ProLockedRow(stringResource(R.string.br_live_title))
        }

        if (bridgeUnlocked) {
            // ---- Local forwarder ----
            Text(stringResource(R.string.br_fwd_title), style = MaterialTheme.typography.labelLarge, color = OpcColors.Orange)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.br_fwd_switch), modifier = Modifier.weight(1f), fontSize = 14.sp)
                Switch(
                    checked = engine.forwardEnabled,
                    onCheckedChange = { on -> scope.launch { graph.engineSettings.update { it.copy(forwardEnabled = on) } } },
                    colors = opcSwitchColors(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it.trim() },
                    label = { Text(stringResource(R.string.br_target_hint)) },
                    singleLine = true,
                    isError = LocalForwarder.parseTarget(target) == null,
                    textStyle = MonoSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = target != engine.forwardTarget && LocalForwarder.parseTarget(target) != null,
                    onClick = { scope.launch { graph.engineSettings.update { it.copy(forwardTarget = target) } } },
                ) { Text(stringResource(R.string.common_save)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !engine.forwardTcp, onClick = {
                    scope.launch { graph.engineSettings.update { it.copy(forwardTcp = false) } }
                })
                Text(stringResource(R.string.br_http), fontSize = 13.sp)
                RadioButton(selected = engine.forwardTcp, onClick = {
                    scope.launch { graph.engineSettings.update { it.copy(forwardTcp = true) } }
                })
                Text(stringResource(R.string.br_tcp), fontSize = 13.sp)
            }
            Text(
                stringResource(R.string.br_fwd_stats, fwdSent, fwdFailed) + (fwdError?.let { " · $it" } ?: ""),
                fontSize = 11.sp,
                color = if (fwdError != null) OpcColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.br_receiver,
                    "opc listen ${LocalForwarder.parseTarget(engine.forwardTarget)?.second ?: 9090}" +
                        if (engine.forwardTcp) " tcp" else "",
                ),
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Local control API ----
            Text(stringResource(R.string.br_remote_title), style = MaterialTheme.typography.labelLarge, color = OpcColors.Orange)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parsed = apiPortText.toIntOrNull()
                val valid = parsed != null && parsed in 1024..65535 && parsed != engine.port
                OutlinedTextField(
                    value = apiPortText,
                    onValueChange = { apiPortText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.br_api_port)) },
                    singleLine = true,
                    isError = !valid,
                    textStyle = MonoSmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = valid && parsed != engine.controlPort,
                    onClick = { scope.launch { graph.engineSettings.update { it.copy(controlPort = parsed!!) } } },
                ) { Text(stringResource(R.string.common_save)) }
            }
            when (val s = apiState) {
                is ControlServerState.Listening -> Text(
                    stringResource(R.string.br_api_listening, s.port),
                    color = OpcColors.Green,
                    fontSize = 12.sp,
                )
                is ControlServerState.Error -> Text(
                    stringResource(R.string.br_api_error, s.port, s.message),
                    color = OpcColors.Red,
                    fontSize = 12.sp,
                )
                ControlServerState.Stopped -> Text(stringResource(R.string.br_api_stopped), fontSize = 12.sp)
                ControlServerState.Locked -> Text(stringResource(R.string.br_api_locked), fontSize = 12.sp)
            }
            val testCmd = "curl -s -H 'Authorization: Bearer $token' http://127.0.0.1:${engine.controlPort}/status"
            SelectionContainer { Text(testCmd, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(testCmd)) }) { Text(stringResource(R.string.br_copy_test)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.br_require_token), modifier = Modifier.weight(1f), fontSize = 14.sp)
                Switch(
                    checked = engine.requireToken,
                    onCheckedChange = { on -> scope.launch { graph.engineSettings.update { it.copy(requireToken = on) } } },
                    colors = opcSwitchColors(),
                )
            }
            if (!engine.requireToken) {
                Text(
                    stringResource(R.string.br_no_token_warning),
                    color = OpcColors.Yellow,
                    fontSize = 11.sp,
                )
            }
            SelectionContainer { Text(stringResource(R.string.br_token, token), style = MonoSmall) }
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text(stringResource(R.string.br_copy_token)) }
                TextButton(onClick = {
                    token = BridgeToken.regenerate(context)
                    exportTick++ // re-export the script with the new token
                }) { Text(stringResource(R.string.br_regenerate), color = OpcColors.Red) }
            }

            // ---- CLI script ----
            Text("opc-cli.sh", style = MaterialTheme.typography.labelLarge, color = OpcColors.Orange)
            Text(scriptStatus, fontSize = 11.sp, style = MonoSmall)
            val install = "cp $dir/opc-cli.sh ~/opc && chmod +x ~/opc && ~/opc help"
            SelectionContainer { Text(install, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                OutlinedButton(onClick = { exportTick++ }) { Text(stringResource(R.string.br_reexport)) }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(install)) }) { Text(stringResource(R.string.br_copy_install)) }
            }
            Text(
                "opc on [port] · opc off · opc status · opc intercept on|off · opc filter \"<regex>\" · opc watch · opc listen",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ProLockedRow(stringResource(R.string.br_fwd_title) + " · " + stringResource(R.string.br_remote_title))
        }
    }
}
