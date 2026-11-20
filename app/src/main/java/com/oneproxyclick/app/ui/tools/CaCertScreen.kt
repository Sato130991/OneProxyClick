package com.oneproxyclick.app.ui.tools

import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oneproxyclick.app.appGraph
import com.oneproxyclick.app.ui.theme.MonoSmall
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.core.ca.CaExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CaInfo(
    val subject: String,
    val notAfter: String,
    val sha256: String,
    val installed: Boolean,
)

@Composable
fun CaCertScreen() {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<CaInfo?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        info = withContext(Dispatchers.IO) {
            val cert = graph.ca.caCertificate
            CaInfo(
                subject = cert.subjectX500Principal.name,
                notAfter = cert.notAfter.toString(),
                sha256 = graph.ca.fingerprintSha256(),
                installed = CaExporter.isInstalledInUserStore(graph.ca),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ca_root), style = MaterialTheme.typography.titleMedium)
                val i = info
                if (i == null) {
                    Text(stringResource(R.string.ca_loading))
                } else {
                    SelectionContainer {
                        Column {
                            Text(i.subject, style = MonoSmall)
                            Text(stringResource(R.string.ca_valid_until, i.notAfter), style = MonoSmall)
                            Text(stringResource(R.string.ca_sha, i.sha256), style = MonoSmall)
                        }
                    }
                    Text(
                        stringResource(if (i.installed) R.string.ca_installed else R.string.ca_not_installed),
                        color = if (i.installed) OpcColors.Green else OpcColors.Yellow,
                    )
                }
            }
        }

        Button(onClick = {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { CaExporter.exportToDownloads(context, graph.ca) } }
                Toast.makeText(
                    context,
                    result.fold({ context.getString(R.string.ca_saved) }, { context.getString(R.string.ca_export_failed, it.message.orEmpty()) }),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ca_btn_export)) }

        OutlinedButton(onClick = {
            try {
                context.startActivity(CaExporter.installIntent(graph.ca))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(CaExporter.securitySettingsIntent())
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ca_btn_installer)) }

        OutlinedButton(onClick = {
            runCatching { context.startActivity(CaExporter.securitySettingsIntent()) }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ca_btn_security)) }

        OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ca_btn_recheck)) }

        Text(
            stringResource(R.string.ca_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = { confirmRegenerate = true }) {
            Text(stringResource(R.string.ca_regenerate_btn), color = OpcColors.Red)
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.ca_regenerate_title)) },
            text = { Text(stringResource(R.string.ca_regenerate_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            graph.ca.regenerate()
                            graph.engine.clearTlsCache()
                        }
                        refresh++
                    }
                }) { Text(stringResource(R.string.ca_regenerate), color = OpcColors.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
