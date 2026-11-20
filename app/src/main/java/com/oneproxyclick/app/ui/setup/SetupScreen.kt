package com.oneproxyclick.app.ui.setup

import com.oneproxyclick.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oneproxyclick.app.ui.PermissionStatus
import com.oneproxyclick.app.ui.theme.OpcColors

/** Guided first-run permission flow: VPN → overlay → notifications. */
@Composable
fun SetupScreen(
    permissions: PermissionStatus,
    onRequestVpn: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("OneProxyClick", style = MaterialTheme.typography.titleLarge, color = OpcColors.Orange)
            Text(
                stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Step(
                number = 1,
                title = stringResource(R.string.setup_vpn_title),
                description = stringResource(R.string.setup_vpn_desc),
                granted = permissions.vpn,
                onGrant = onRequestVpn,
            )
            Step(
                number = 2,
                title = stringResource(R.string.setup_overlay_title),
                description = stringResource(R.string.setup_overlay_desc),
                granted = permissions.overlay,
                onGrant = onRequestOverlay,
            )
            Step(
                number = 3,
                title = stringResource(R.string.setup_notif_title),
                description = stringResource(R.string.setup_notif_desc),
                granted = permissions.notifications,
                onGrant = onRequestNotifications,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (permissions.all) R.string.setup_continue else R.string.setup_continue_missing))
            }
            Text(
                stringResource(R.string.setup_next_ca),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(number: Int, title: String, description: String, granted: Boolean, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number. $title", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (granted) Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.setup_granted), tint = OpcColors.Green)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) OutlinedButton(onClick = onGrant) { Text(stringResource(R.string.setup_grant)) }
        }
    }
}
