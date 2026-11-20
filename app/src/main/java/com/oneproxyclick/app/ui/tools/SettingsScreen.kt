package com.oneproxyclick.app.ui.tools

import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.app.ui.pro.rememberProUnlocked
import com.oneproxyclick.app.ui.pro.ProLockedRow
import com.oneproxyclick.app.ui.pro.ProCard
import com.oneproxyclick.core.common.LanguageManager
import com.oneproxyclick.core.common.AppLanguage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneproxyclick.app.EngineSettings
import com.oneproxyclick.app.appGraph
import com.oneproxyclick.app.ui.PermissionStatus
import com.oneproxyclick.app.ui.theme.MonoSmall
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.app.ui.theme.opcSwitchColors
import com.oneproxyclick.core.storage.export.ExportWriter
import com.oneproxyclick.core.vpn.InstalledApp
import com.oneproxyclick.core.vpn.InstalledApps
import com.oneproxyclick.core.vpn.SplitMode
import com.oneproxyclick.core.vpn.TunnelSettings
import com.oneproxyclick.core.vpn.VpnController
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(permissions: PermissionStatus, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val liveUnlocked = rememberProUnlocked(ProFeature.LIVE_JSON)
    val engine by graph.engineSettings.settings.collectAsState(initial = EngineSettings())
    val tunnel by graph.tunnelSettings.settings.collectAsState(initial = TunnelSettings())
    val lastError by VpnController.lastError.collectAsState()
    val apps by produceState(initialValue = emptyList<InstalledApp>()) { value = InstalledApps.load(context) }

    var portText by remember { mutableStateOf("") }
    var passthrough by remember { mutableStateOf<String?>(null) }
    var appQuery by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    LaunchedEffect(engine.port) { portText = engine.port.toString() }

    val visibleApps = apps.filter {
        (showSystem || !it.isSystem || it.packageName in tunnel.packages) &&
            (appQuery.isBlank() || it.label.contains(appQuery, true) || it.packageName.contains(appQuery, true))
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LanguageCard()
                ProCard()
                if (!permissions.all) {
                    OutlinedButton(onClick = onOpenSetup) { Text(stringResource(R.string.set_open_setup)) }
                }
                lastError?.let { Text(stringResource(R.string.set_last_error, it), color = OpcColors.Red, fontSize = 12.sp) }

                Text(stringResource(R.string.set_engine), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.set_port)) },
                        singleLine = true,
                        textStyle = MonoSmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val p = portText.toIntOrNull()
                        if (p != null && p in 1024..65535 && p != engine.controlPort) scope.launch { graph.engineSettings.update { it.copy(port = p) } }
                    }) { Text(stringResource(R.string.common_save)) }
                }
                Text(stringResource(R.string.set_port_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToggleRow(stringResource(R.string.set_mitm), engine.mitmEnabled) { on ->
                    scope.launch { graph.engineSettings.update { it.copy(mitmEnabled = on) } }
                }
                ToggleRow(stringResource(R.string.set_verify), engine.verifyUpstreamTls) { on ->
                    scope.launch { graph.engineSettings.update { it.copy(verifyUpstreamTls = on) } }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(stringResource(R.string.set_json_export), style = MaterialTheme.typography.titleMedium)
                if (liveUnlocked) {
                    ToggleRow(stringResource(R.string.hist_auto_json), engine.autoJsonLive) { on ->
                        scope.launch { graph.engineSettings.update { it.copy(autoJsonLive = on) } }
                    }
                } else {
                    ProLockedRow(stringResource(R.string.hist_auto_json))
                }
                Text(
                    stringResource(
                        R.string.set_auto_json_note,
                        ExportWriter.PUBLIC_DIR,
                        stringResource(R.string.detail_save_json),
                        stringResource(R.string.hist_export_json),
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = passthrough ?: engine.passthroughHosts,
                    onValueChange = { passthrough = it },
                    label = { Text(stringResource(R.string.set_passthrough)) },
                    textStyle = MonoSmall,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (passthrough != null && passthrough != engine.passthroughHosts) {
                    TextButton(onClick = {
                        val value = passthrough.orEmpty()
                        scope.launch { graph.engineSettings.update { it.copy(passthroughHosts = value) } }
                        passthrough = null
                    }) { Text(stringResource(R.string.set_save_passthrough)) }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                KaliTermBridgeSection(engine)

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(stringResource(R.string.set_split), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = tunnel.mode == SplitMode.EXCLUDE, onClick = {
                        scope.launch { graph.tunnelSettings.setMode(SplitMode.EXCLUDE) }
                    })
                    Text(stringResource(R.string.set_split_exclude), fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = tunnel.mode == SplitMode.INCLUDE, onClick = {
                        scope.launch { graph.tunnelSettings.setMode(SplitMode.INCLUDE) }
                    })
                    Text(stringResource(R.string.set_split_include), fontSize = 13.sp)
                }
                Text(
                    stringResource(R.string.set_always_excluded, InstalledApps.alwaysExcluded(context).joinToString()),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.set_search_apps)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                    Text(stringResource(R.string.set_show_system), fontSize = 13.sp)
                    if (tunnel.packages.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { graph.tunnelSettings.clearPackages() } }) {
                            Text(stringResource(R.string.set_clear_selection, tunnel.packages.size))
                        }
                    }
                }
            }
        }
        items(visibleApps, key = { it.packageName }) { app ->
            val checked = app.packageName in tunnel.packages
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { graph.tunnelSettings.setPackageSelected(app.packageName, !checked) } }
                    .padding(vertical = 2.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = { on ->
                    scope.launch { graph.tunnelSettings.setPackageSelected(app.packageName, on) }
                })
                Column {
                    Text(app.label, fontSize = 14.sp)
                    Text(app.packageName, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange, colors = opcSwitchColors())
    }
}

/** "Idioma / Language": switches the whole UI, overlay and notifications instantly. */
@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by LanguageManager.language.collectAsState()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.set_language_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Spanish first, as requested; native names so both are always readable.
                listOf(AppLanguage.SPANISH, AppLanguage.ENGLISH).forEach { lang ->
                    FilterChip(
                        selected = current == lang,
                        onClick = { scope.launch { LanguageManager.set(context, lang) } },
                        label = { Text(lang.nativeName) },
                    )
                }
            }
            Text(
                stringResource(R.string.set_language_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
