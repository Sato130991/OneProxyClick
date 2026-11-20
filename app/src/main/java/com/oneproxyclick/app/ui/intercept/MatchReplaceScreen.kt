package com.oneproxyclick.app.ui.intercept

import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.app.ui.pro.rememberProUnlocked
import com.oneproxyclick.app.ui.pro.ProLockedRow
import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproxyclick.app.appGraph
import com.oneproxyclick.app.ui.theme.MonoSmall
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.app.ui.theme.opcSwitchColors
import com.oneproxyclick.core.interceptor.rules.MatchReplaceRule
import com.oneproxyclick.core.interceptor.rules.RuleTarget

@Composable
fun MatchReplaceScreen() {
    val graph = LocalContext.current.appGraph
    // Saved rules (the engine only applies them while MATCH_REPLACE is unlocked).
    val rules by graph.storedRules.collectAsStateWithLifecycle()
    val unlocked = rememberProUnlocked(ProFeature.MATCH_REPLACE)
    if (!unlocked) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            ProLockedRow(stringResource(R.string.int_match_replace))
        }
        return
    }

    var target by rememberSaveable { mutableStateOf(RuleTarget.REQUEST_HEADER) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val addMarker = stringResource(R.string.mr_add_marker)
    val removeMarker = stringResource(R.string.mr_remove_marker)
    val candidate = MatchReplaceRule(0, true, target, pattern, replacement, comment)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.mr_new_rule), style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text(stringResource(R.string.mr_target, ruleTargetLabel(target))) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        RuleTarget.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(ruleTargetLabel(t)) }, onClick = {
                                target = t
                                menuOpen = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.mr_match)) },
                    textStyle = MonoSmall,
                    isError = !candidate.isValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(R.string.mr_replace)) },
                    textStyle = MonoSmall,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.mr_comment)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = candidate.isValid && (pattern.isNotEmpty() || replacement.contains(':')),
                    onClick = {
                        val id = (rules.maxOfOrNull { it.id } ?: 0) + 1
                        graph.saveRules(rules + candidate.copy(id = id))
                        pattern = ""
                        replacement = ""
                        comment = ""
                    },
                ) { Text(stringResource(R.string.mr_add)) }
                Text(
                    stringResource(R.string.mr_examples),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (rules.isEmpty()) {
            item { Text(stringResource(R.string.mr_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(rules, key = { it.id }) { rule ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(ruleTargetLabel(rule.target), color = OpcColors.Orange, fontSize = 12.sp)
                        Text(
                            "${rule.pattern.ifEmpty { addMarker }}  →  ${rule.replacement.ifEmpty { removeMarker }}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        if (rule.comment.isNotEmpty()) {
                            Text(rule.comment, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!rule.isValid) Text(stringResource(R.string.mr_invalid), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { on -> graph.saveRules(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) },
                        colors = opcSwitchColors(),
                    )
                    IconButton(onClick = { graph.saveRules(rules.filterNot { it.id == rule.id }) }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.mr_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun ruleTargetLabel(t: RuleTarget): String = stringResource(
    when (t) {
        RuleTarget.REQUEST_HEADER -> R.string.rt_request_header
        RuleTarget.REQUEST_BODY -> R.string.rt_request_body
        RuleTarget.RESPONSE_HEADER -> R.string.rt_response_header
        RuleTarget.RESPONSE_BODY -> R.string.rt_response_body
    },
)
