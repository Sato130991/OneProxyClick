package com.oneproxyclick.feature.repeater

import androidx.compose.material.icons.filled.Lock
import com.oneproxyclick.core.common.pro.Pro
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.filled.Done
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproxyclick.core.interceptor.codec.Formatters

@Composable
fun RepeaterScreen(
    viewModel: RepeaterViewModel,
    modifier: Modifier = Modifier,
    /** Called when "+" is tapped in the free tier (1 tab); the app shows the Pro dialog. */
    onProRequired: () -> Unit = {},
    /**
     * "Save JSON": exports the shown request/response; returns the Snackbar text
     * (provided by the app, which owns the exporter). Null hides the button.
     */
    onSaveJson: (suspend (RepeaterEntry) -> String)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val save: (RepeaterEntry) -> Unit = { e ->
        if (!saving && onSaveJson != null) {
            saving = true
            scope.launch {
                val msg = try { onSaveJson(e) } finally { saving = false }
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(msg, withDismissAction = true, duration = SnackbarDuration.Long)
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        RepeaterBody(viewModel, Modifier.fillMaxSize(), onProRequired, if (onSaveJson != null) save else null, saving)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RepeaterBody(
    viewModel: RepeaterViewModel,
    modifier: Modifier,
    onProRequired: () -> Unit,
    onSave: ((RepeaterEntry) -> Unit)?,
    saving: Boolean,
) {
    val isPro by Pro.manager.isPro.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ignoreTls by viewModel.ignoreTlsErrors.collectAsStateWithLifecycle()
    // Tabs, index and selected tab all come from one immutable snapshot -> always consistent.
    val tabs = state.tabs
    val selectedIndex = state.selectedIndex.coerceIn(0, tabs.lastIndex)
    val tab = state.selectedTab
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val stripState = rememberLazyListState()

    LaunchedEffect(selectedIndex, tabs.size) {
        if (selectedIndex in tabs.indices) stripState.animateScrollToItem(selectedIndex)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        ) {
            LazyRow(
                state = stripState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(tabs, key = { _, t -> t.id }) { index, t ->
                    val sends = if (t.history.isEmpty()) "" else " (${t.history.size})"
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { viewModel.select(t.id) },
                        label = { Text(stringResource(R.string.rep_tab_title, t.id) + sends, fontSize = 12.sp) },
                    )
                }
            }
            // "+" = new tab (and focus it)
            val tabsLocked = !isPro && tabs.size >= RepeaterViewModel.FREE_TAB_LIMIT
            IconButton(onClick = { if (!viewModel.addTab()) onProRequired() }) {
                Icon(
                    if (tabsLocked) Icons.Default.Lock else Icons.Default.Add,
                    contentDescription = stringResource(R.string.rep_new_tab),
                )
            }
            // "X" = close ONLY the active tab; disabled when it is the last one
            IconButton(onClick = { viewModel.closeTab(tab.id) }, enabled = state.canClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rep_close_tab))
            }
        }

        // Target + actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Checkbox(checked = tab.https, onCheckedChange = { viewModel.updateHttps(tab.id, it) })
            Text(stringResource(R.string.rep_tls), style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = tab.host,
                onValueChange = { viewModel.updateHost(tab.id, it) },
                label = { Text(stringResource(R.string.rep_host)) },
                singleLine = true,
                textStyle = mono,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = tab.port,
                onValueChange = { viewModel.updatePort(tab.id, it) },
                label = { Text(stringResource(R.string.rep_port), maxLines = 1, softWrap = false) },
                singleLine = true,
                textStyle = mono,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Button(onClick = { viewModel.send(tab.id) }, enabled = !tab.sending) {
                if (tab.sending) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.rep_send))
                }
            }
            IconButton(onClick = { viewModel.historyBack(tab.id) }, enabled = tab.historyIndex > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.rep_previous))
            }
            Text(
                if (tab.history.isEmpty()) "–" else "${tab.historyIndex + 1}/${tab.history.size}",
                style = MaterialTheme.typography.labelMedium,
            )
            IconButton(onClick = { viewModel.historyForward(tab.id) }, enabled = tab.historyIndex < tab.history.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.rep_next))
            }
            Spacer(Modifier.weight(1f))
            Checkbox(checked = ignoreTls, onCheckedChange = viewModel::setIgnoreTlsErrors)
            Text(stringResource(R.string.rep_ignore_tls), style = MaterialTheme.typography.labelSmall)
        }

        tab.parseError?.let {
            Text(stringResource(R.string.rep_parse_error, it.ifEmpty { stringResource(R.string.rep_invalid_request) }), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }

        OutlinedTextField(
            value = tab.raw,
            onValueChange = { viewModel.updateRaw(tab.id, it) },
            textStyle = mono,
            label = { Text(stringResource(R.string.rep_request_raw)) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .padding(horizontal = 8.dp),
        )

        HorizontalDivider(Modifier.padding(top = 6.dp))

        val entry = tab.current
        Box(Modifier.fillMaxWidth().weight(0.55f)) {
            when {
                entry == null -> Text(
                    stringResource(R.string.rep_response_placeholder),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.error != null -> Column(Modifier.fillMaxSize()) {
                    if (onSave != null && entry.request != null) {
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                            SaveJsonButton(saving) { onSave(entry) }
                        }
                    }
                    Text(
                        entry.error,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                entry.response != null -> Column(Modifier.fillMaxSize()) {
                    val r = entry.response
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
                    ) {
                        Text(
                            "${r.statusCode} ${r.reason}  ·  ${entry.durationMs} ms  ·  ${Formatters.humanSize(r.body.size.toLong())}",
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor(r.statusCode),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        )
                        if (onSave != null && entry.request != null) SaveJsonButton(saving) { onSave(entry) }
                    }
                    MessageViewer(
                        startLine = "${r.protocol} ${r.statusCode} ${r.reason}",
                        headers = r.headers,
                        body = r.body,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveJsonButton(saving: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = !saving,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.rep_save_json), fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
fun statusColor(code: Int) = when (code) {
    in 200..299 -> androidx.compose.ui.graphics.Color(0xFF3FB950)
    in 300..399 -> androidx.compose.ui.graphics.Color(0xFF58A6FF)
    in 400..499 -> androidx.compose.ui.graphics.Color(0xFFD29922)
    in 500..599 -> androidx.compose.ui.graphics.Color(0xFFF85149)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
