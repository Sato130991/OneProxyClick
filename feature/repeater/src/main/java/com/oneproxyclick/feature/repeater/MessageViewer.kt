package com.oneproxyclick.feature.repeater

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneproxyclick.core.interceptor.codec.ContentCodec
import com.oneproxyclick.core.interceptor.codec.Formatters
import com.oneproxyclick.core.interceptor.model.HttpHeader
import com.oneproxyclick.core.interceptor.model.firstValue

private val VIEW_TABS = listOf(R.string.viewer_pretty, R.string.viewer_raw, R.string.viewer_headers, R.string.viewer_hex)

/**
 * Read-only HTTP message viewer with Pretty (JSON/HTML), Raw, Headers and Hex tabs.
 * Body decompression is for display only; [body] stays the wire bytes.
 */
@Composable
fun MessageViewer(
    startLine: String,
    headers: List<HttpHeader>,
    body: ByteArray,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val contentType = headers.firstValue("Content-Type")
    val encoding = headers.firstValue("Content-Encoding")
    val bodyText = remember(body, encoding) { ContentCodec.bodyAsText(body, contentType, encoding) }

    Column(modifier) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            VIEW_TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(title), fontSize = 12.sp) })
            }
        }
        when (tab) {
            0 -> {
                val pretty = remember(bodyText, contentType) { Formatters.pretty(bodyText, contentType) }
                CodeText(pretty.ifEmpty { stringResource(R.string.viewer_empty_body) })
            }
            1 -> {
                val raw = remember(startLine, headers, bodyText) {
                    buildString {
                        append(startLine).append('\n')
                        headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
                        append('\n').append(bodyText)
                    }
                }
                CodeText(raw)
            }
            2 -> LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                item {
                    Text(startLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                items(headers) { h ->
                    SelectionContainer {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                                    append(h.name)
                                }
                                append(": ")
                                append(h.value)
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            else -> {
                val hex = remember(body) { Formatters.hexDump(body) }
                CodeText(hex, wrap = false)
            }
        }
    }
}

@Composable
fun CodeText(text: String, modifier: Modifier = Modifier, wrap: Boolean = true) {
    val vertical = rememberScrollState()
    val inner = Modifier
        .fillMaxSize()
        .verticalScroll(vertical)
        .padding(8.dp)
    SelectionContainer(modifier) {
        if (wrap) {
            Text(text, modifier = inner.fillMaxWidth(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        } else {
            Row(inner.horizontalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
            }
        }
    }
}
