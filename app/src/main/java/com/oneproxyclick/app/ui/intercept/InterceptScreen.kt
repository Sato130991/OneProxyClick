package com.oneproxyclick.app.ui.intercept

import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproxyclick.app.ui.theme.MonoSmall
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.app.ui.theme.opcSwitchColors
import com.oneproxyclick.core.interceptor.InterceptPhase
import com.oneproxyclick.core.interceptor.InterceptorBus
import com.oneproxyclick.core.interceptor.codec.RawHttp
import com.oneproxyclick.feature.repeater.RepeaterSeed

@Composable
fun InterceptScreen(onSendToRepeater: (RepeaterSeed) -> Unit) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text(stringResource(R.string.int_queue)) })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text(stringResource(R.string.int_match_replace)) })
        }
        if (sub == 0) InterceptQueue(onSendToRepeater) else MatchReplaceScreen()
    }
}

@Composable
private fun InterceptQueue(onSendToRepeater: (RepeaterSeed) -> Unit) {
    val enabled by InterceptorBus.interceptEnabled.collectAsStateWithLifecycle()
    val responses by InterceptorBus.interceptResponses.collectAsStateWithLifecycle()
    val skipStatic by InterceptorBus.skipStaticResources.collectAsStateWithLifecycle()
    val hostFilter by InterceptorBus.hostFilter.collectAsStateWithLifecycle()
    val queue by InterceptorBus.queue.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<Long?>(null) }

    val selected = queue.firstOrNull { it.id == selectedId } ?: queue.firstOrNull()

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(if (enabled) R.string.int_on else R.string.int_off),
                color = if (enabled) OpcColors.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = InterceptorBus::setInterceptEnabled, colors = opcSwitchColors())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = responses, onClick = { InterceptorBus.setInterceptResponses(!responses) }, label = { Text(stringResource(R.string.int_responses), fontSize = 12.sp) })
            FilterChip(selected = skipStatic, onClick = { InterceptorBus.setSkipStaticResources(!skipStatic) }, label = { Text(stringResource(R.string.int_skip_static), fontSize = 12.sp) })
        }
        OutlinedTextField(
            value = hostFilter,
            onValueChange = InterceptorBus::setHostFilter,
            singleLine = true,
            label = { Text(stringResource(R.string.int_host_filter)) },
            textStyle = MonoSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        if (queue.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                queue.forEach { item ->
                    FilterChip(
                        selected = item.id == selected?.id,
                        onClick = { selectedId = item.id },
                        label = {
                            Text(
                                (if (item.phase == InterceptPhase.RESPONSE) "⇠ " else "⇢ ") + item.summary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(180.dp),
                            )
                        },
                    )
                }
            }
        }

        if (selected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (enabled) R.string.int_waiting else R.string.int_passthrough),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        var text by remember(selected.id) { mutableStateOf(selected.originalRaw) }
        var error by remember(selected.id) { mutableStateOf<String?>(null) }

        Text(
            stringResource(
                if (selected.phase == InterceptPhase.RESPONSE) R.string.int_response_from else R.string.int_request_to,
                "${selected.request.scheme}://${selected.request.authority}",
            ),
            style = MaterialTheme.typography.labelMedium,
            color = OpcColors.Orange,
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            textStyle = MonoSmall,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Button(
                onClick = { error = InterceptorBus.forward(selected.id, text) },
                colors = ButtonDefaults.buttonColors(containerColor = OpcColors.Green),
            ) { Text(stringResource(R.string.int_forward)) }
            OutlinedButton(
                onClick = { InterceptorBus.drop(selected.id) },
                border = BorderStroke(1.dp, OpcColors.Red),
            ) { Text(stringResource(R.string.int_drop), color = OpcColors.Red) }
            OutlinedButton(onClick = { InterceptorBus.forwardAll() }) { Text(stringResource(R.string.int_forward_all)) }
            Spacer(Modifier.weight(1f))
        }
        if (selected.phase == InterceptPhase.REQUEST) {
            OutlinedButton(
                onClick = {
                    val req = runCatching {
                        RawHttp.parseRequest(text, selected.request.scheme, selected.request.host, selected.request.port)
                    }.getOrDefault(selected.request)
                    onSendToRepeater(RepeaterSeed.from(req))
                },
                modifier = Modifier.padding(bottom = 6.dp),
            ) { Text(stringResource(R.string.detail_send_repeater)) }
        }
    }
}
