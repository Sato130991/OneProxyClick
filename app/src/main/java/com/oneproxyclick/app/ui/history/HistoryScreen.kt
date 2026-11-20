package com.oneproxyclick.app.ui.history

import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.app.ui.pro.rememberProUnlocked
import com.oneproxyclick.app.ui.pro.ProBadge
import com.oneproxyclick.app.ui.pro.LocalShowProUpsell
import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import com.oneproxyclick.app.ui.theme.opcSwitchColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oneproxyclick.app.ui.theme.MonoSmall
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.app.ui.theme.methodColor
import com.oneproxyclick.app.ui.theme.statusColorOf
import com.oneproxyclick.core.interceptor.codec.Formatters
import com.oneproxyclick.core.storage.HttpTransactionSummary
import com.oneproxyclick.core.storage.StatusFilter
import com.oneproxyclick.feature.repeater.RepeaterSeed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val METHODS = listOf("GET", "POST", "PUT", "DELETE")

@Composable
fun HistoryScreen(
    openTransactionId: Long?,
    onOpenTransaction: (Long?) -> Unit,
    onSendToRepeater: (RepeaterSeed) -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    Box(Modifier.fillMaxSize()) {
        HistoryBody(openTransactionId, onOpenTransaction, onSendToRepeater, viewModel)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun HistoryBody(
    openTransactionId: Long?,
    onOpenTransaction: (Long?) -> Unit,
    onSendToRepeater: (RepeaterSeed) -> Unit,
    viewModel: HistoryViewModel,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val total by viewModel.totalCount.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val autoJson by viewModel.autoJsonLive.collectAsStateWithLifecycle()
    val liveUnlocked = rememberProUnlocked(ProFeature.LIVE_JSON)
    val showUpsell = LocalShowProUpsell.current
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(openTransactionId) { viewModel.load(openTransactionId) }

    if (openTransactionId != null) {
        BackHandler { onOpenTransaction(null) }
        val d = detail
        if (d != null && d.entity.id == openTransactionId) {
            TransactionDetailScreen(
                detail = d,
                onBack = { onOpenTransaction(null) },
                onSendToRepeater = { onSendToRepeater(RepeaterSeed.from(d.request)) },
                onSaveJson = { viewModel.exportDetail(d) },
                onSendToKaliTerm = { viewModel.sendToKaliTerm(context, d) },
                saving = exporting,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.hist_loading)) }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // Export bar: filtered history export + live auto-save toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 4.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::exportFiltered,
                enabled = !exporting && items.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.hist_export_json), fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.hist_auto_json),
                fontSize = 12.sp,
                color = if (autoJson && liveUnlocked) OpcColors.Orange else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 6.dp),
            )
            if (!liveUnlocked) ProBadge(Modifier.padding(end = 6.dp))
            Switch(
                // Locked (play, no Pro): shown OFF; tapping opens the Pro dialog instead of toggling.
                checked = autoJson && liveUnlocked,
                onCheckedChange = { on -> if (liveUnlocked) viewModel.setAutoJsonLive(on) else showUpsell() },
                colors = opcSwitchColors(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = filter.search,
                onValueChange = viewModel::setSearch,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.hist_filter_hint), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (filter.search.isNotEmpty()) IconButton(onClick = { viewModel.setSearch("") }) {
                        Icon(Icons.Default.Clear, stringResource(R.string.hist_clear_filter))
                    }
                },
                textStyle = MonoSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, stringResource(R.string.hist_clear_history)) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            StatusFilter.entries.forEach { s ->
                FilterChip(selected = filter.status == s, onClick = { viewModel.setStatus(s) }, label = { Text(statusLabel(s), fontSize = 12.sp) })
            }
            Spacer(Modifier.width(6.dp))
            METHODS.forEach { m ->
                FilterChip(selected = filter.method == m, onClick = { viewModel.setMethod(m) }, label = { Text(m, fontSize = 12.sp) })
            }
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = filter.hideStatic,
                onClick = { viewModel.setHideStatic(!filter.hideStatic) },
                label = { Text(stringResource(R.string.hist_hide_static), fontSize = 12.sp) },
            )
        }
        Text(
            stringResource(R.string.hist_count, items.size, total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        HorizontalDivider()
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.hist_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { row ->
                    HistoryRow(row) { onOpenTransaction(row.id) }
                    HorizontalDivider(color = OpcColors.Border.copy(alpha = 0.5f))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.hist_clear_title)) },
            text = { Text(stringResource(R.string.hist_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun statusLabel(s: StatusFilter): String = when (s) {
    StatusFilter.ALL -> stringResource(R.string.status_all)
    StatusFilter.ERROR -> stringResource(R.string.status_err)
    else -> s.label // 2xx..5xx are universal
}

@Composable
private fun HistoryRow(row: HttpTransactionSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.method,
                color = methodColor(row.method),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(72.dp),
            )
            Text(
                row.host + if (row.port != 80 && row.port != 443) ":${row.port}" else "",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.edited) Text("✎ ", color = OpcColors.Orange, fontSize = 12.sp)
            Text(
                if (row.statusCode > 0) row.statusCode.toString() else stringResource(R.string.hist_err),
                color = statusColorOf(row.statusCode),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
        }
        Row {
            Text(
                row.path + if (row.query.isNotEmpty()) "?${row.query}" else "",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${row.durationMs}ms · ${Formatters.humanSize(row.responseSize)} · ${timeFormat.format(Date(row.timestamp))}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.error?.let {
            Text(it, color = OpcColors.Red, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
