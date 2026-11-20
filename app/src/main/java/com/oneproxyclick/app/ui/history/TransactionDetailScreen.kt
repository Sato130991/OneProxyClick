package com.oneproxyclick.app.ui.history

import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.app.ui.theme.statusColorOf
import com.oneproxyclick.core.interceptor.codec.CurlCommand
import com.oneproxyclick.core.interceptor.codec.Formatters
import com.oneproxyclick.core.interceptor.codec.RawHttp
import com.oneproxyclick.core.storage.TransactionDetail
import com.oneproxyclick.feature.repeater.MessageViewer

@Composable
fun TransactionDetailScreen(
    detail: TransactionDetail,
    onBack: () -> Unit,
    onSendToRepeater: () -> Unit,
    onSaveJson: () -> Unit,
    onSendToKaliTerm: () -> Unit,
    saving: Boolean,
) {
    var part by rememberSaveable { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val e = detail.entity
    val request = detail.request
    val response = detail.response

    val noResponse = stringResource(R.string.detail_no_response)
    val editedLabel = stringResource(R.string.detail_edited)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.detail_back)) }
            Column(Modifier.weight(1f)) {
                Text(
                    "#${e.id} ${request.method} ${request.url}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(if (e.statusCode > 0) "${e.statusCode} ${e.reason}" else noResponse)
                        append(" · ${e.durationMs} ms · ${Formatters.humanSize(e.responseSize)}")
                        if (e.edited) append(" · ").append(editedLabel)
                    },
                    color = statusColorOf(e.statusCode),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        e.error?.let { Text(it, color = OpcColors.Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
        // Scrollable action strip: on narrow screens buttons scroll instead of being clipped.
        val actions = listOf<Triple<String, Boolean, () -> Unit>>(
            Triple(stringResource(R.string.detail_save_json), !saving, onSaveJson),
            Triple(stringResource(R.string.detail_send_repeater), true, onSendToRepeater),
            Triple(stringResource(R.string.detail_send_kaliterm), !saving, onSendToKaliTerm),
            Triple(stringResource(R.string.detail_copy_curl), true) { clipboard.setText(AnnotatedString(CurlCommand.build(request))) },
            Triple(stringResource(R.string.detail_copy_url), true) { clipboard.setText(AnnotatedString(request.url)) },
            Triple(stringResource(R.string.detail_copy_raw), true) { clipboard.setText(AnnotatedString(RawHttp.requestToRaw(request))) },
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(actions) { (label, enabled, onClick) ->
                OutlinedButton(
                    onClick = onClick,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) { Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        TabRow(selectedTabIndex = part, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = part == 0, onClick = { part = 0 }, text = { Text(stringResource(R.string.detail_request)) })
            Tab(selected = part == 1, onClick = { part = 1 }, text = { Text(stringResource(R.string.detail_response)) })
        }
        if (part == 0) {
            MessageViewer(
                startLine = "${request.method} ${request.target} ${request.protocol}",
                headers = request.headers,
                body = request.body,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (response != null) {
            MessageViewer(
                startLine = "${response.protocol} ${response.statusCode} ${response.reason}",
                headers = response.headers,
                body = response.body,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(stringResource(R.string.detail_no_response_recorded), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
