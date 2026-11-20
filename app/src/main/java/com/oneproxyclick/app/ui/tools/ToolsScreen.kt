package com.oneproxyclick.app.ui.tools

import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.oneproxyclick.app.ui.PermissionStatus
import com.oneproxyclick.feature.decoder.DecoderScreen

@Composable
fun ToolsScreen(permissions: PermissionStatus, onOpenSetup: () -> Unit) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text(stringResource(R.string.tools_ca)) })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text(stringResource(R.string.tools_decoder)) })
            Tab(selected = sub == 2, onClick = { sub = 2 }, text = { Text(stringResource(R.string.tools_settings)) })
        }
        when (sub) {
            0 -> CaCertScreen()
            1 -> DecoderScreen()
            else -> SettingsScreen(permissions, onOpenSetup)
        }
    }
}
