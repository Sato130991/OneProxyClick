package com.oneproxyclick.feature.decoder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** [error] is either a resolved message or null; [errorRes] a resource to resolve. */
private data class StepResult(val output: ByteArray?, val error: String?, val errorRes: Int? = null)

/** Chained encoder/decoder: input → step 1 → step 2 → … each step shows its own output. */
@Composable
fun DecoderScreen(modifier: Modifier = Modifier, initialInput: String = "") {
    var input by rememberSaveable { mutableStateOf(initialInput) }
    val steps = remember { mutableStateListOf(DecoderOp.BASE64_DECODE) }
    val clipboard = LocalClipboardManager.current
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    val results = run {
        var current: ByteArray? = input.toByteArray(Charsets.UTF_8)
        steps.map { op ->
            val bytes = current
            if (bytes == null) {
                StepResult(null, null, R.string.dec_previous_failed)
            } else try {
                val out = DecoderOps.apply(op, bytes)
                current = out
                StepResult(out, null)
            } catch (e: DecoderError) {
                current = null
                StepResult(null, null, e.messageRes)
            } catch (e: Exception) {
                current = null
                StepResult(null, e.message, if (e.message == null) R.string.dec_err_generic else null)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.dec_input)) },
            textStyle = mono,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clipboard.getText()?.let { input = it.text } }) { Text(stringResource(R.string.dec_paste)) }
            TextButton(onClick = { input = "" }) { Text(stringResource(R.string.dec_clear)) }
        }

        steps.forEachIndexed { index, op ->
            val result = results.getOrNull(index)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        OpSelector(op) { steps[index] = it }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { steps.removeAt(index) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dec_remove_step))
                        }
                    }
                    when {
                        result != null && (result.error != null || result.errorRes != null) -> Text(
                            stringResource(R.string.dec_error, result.error ?: stringResource(result.errorRes!!)),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        result?.output != null -> {
                            val shown = DecoderOps.display(result.output)
                            SelectionContainer {
                                Text(shown, style = mono, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                            }
                            Row {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(shown)) }) { Text(stringResource(R.string.dec_copy)) }
                                if (!DecoderOps.isBinary(result.output)) {
                                    TextButton(onClick = {
                                        input = shown
                                        steps.clear()
                                        steps.add(DecoderOp.BASE64_DECODE)
                                    }) { Text(stringResource(R.string.dec_use_as_input)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = { steps.add(DecoderOp.URL_DECODE) }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dec_add_step))
        }
    }
}

@Composable
private fun OpSelector(selected: DecoderOp, onSelect: (DecoderOp) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(selected.labelRes) + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DecoderOp.entries.forEach { op ->
                DropdownMenuItem(text = { Text(stringResource(op.labelRes)) }, onClick = {
                    onSelect(op)
                    open = false
                })
            }
        }
    }
}
