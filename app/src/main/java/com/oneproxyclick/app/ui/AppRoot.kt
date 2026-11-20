package com.oneproxyclick.app.ui

import com.oneproxyclick.app.ui.pro.ProUpsellDialog
import com.oneproxyclick.app.ui.pro.LocalShowProUpsell
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawWithContent
import com.oneproxyclick.app.R
import android.content.Context
import com.oneproxyclick.app.appGraph
import com.oneproxyclick.core.common.tr
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oneproxyclick.app.ui.history.HistoryScreen
import com.oneproxyclick.app.ui.intercept.InterceptScreen
import com.oneproxyclick.app.ui.setup.SetupScreen
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.app.ui.theme.opcSwitchColors
import com.oneproxyclick.app.ui.tools.ToolsScreen
import com.oneproxyclick.core.interceptor.InterceptorBus
import com.oneproxyclick.core.vpn.TunnelState
import com.oneproxyclick.core.vpn.VpnController
import com.oneproxyclick.feature.overlay.OverlayContract
import com.oneproxyclick.feature.repeater.RepeaterEntry
import com.oneproxyclick.feature.repeater.RepeaterScreen
import com.oneproxyclick.feature.repeater.RepeaterSeed
import com.oneproxyclick.feature.repeater.RepeaterViewModel

data class PermissionStatus(val vpn: Boolean, val overlay: Boolean, val notifications: Boolean) {
    val all: Boolean get() = vpn && overlay && notifications
}

/** Deep link from the overlay ("open app on the last captured packet"). */
data class NavRequest(val tab: String, val transactionId: Long?)

enum class MainTab(@StringRes val labelRes: Int) {
    PROXY(R.string.tab_proxy), INTERCEPT(R.string.tab_intercept), REPEATER(R.string.tab_repeater), TOOLS(R.string.tab_tools)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    permissions: PermissionStatus,
    navRequest: NavRequest?,
    onNavRequestConsumed: () -> Unit,
    onRequestVpn: (startAfter: Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(MainTab.PROXY) }
    var openTransactionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val repeaterViewModel: RepeaterViewModel = viewModel()

    val tunnelState by VpnController.state.collectAsStateWithLifecycle()
    val port by VpnController.activePort.collectAsStateWithLifecycle()
    val queue by InterceptorBus.queue.collectAsStateWithLifecycle()
    val interceptOn by InterceptorBus.interceptEnabled.collectAsStateWithLifecycle()
    val overlayShowing by OverlayContract.isShowing.collectAsStateWithLifecycle()

    LaunchedEffect(navRequest) {
        val req = navRequest ?: return@LaunchedEffect
        setupDismissed = true
        when (req.tab) {
            OverlayContract.TAB_INTERCEPT -> tab = MainTab.INTERCEPT
            else -> {
                tab = MainTab.PROXY
                openTransactionId = req.transactionId
            }
        }
        onNavRequestConsumed()
    }

    if (!permissions.all && !setupDismissed) {
        SetupScreen(
            permissions = permissions,
            onRequestVpn = { onRequestVpn(false) },
            onRequestOverlay = onRequestOverlay,
            onRequestNotifications = onRequestNotifications,
            onContinue = { setupDismissed = true },
        )
        return
    }

    val sendToRepeater: (RepeaterSeed) -> Unit = { seed ->
        repeaterViewModel.addTab(seed)
        tab = MainTab.REPEATER
    }

    var showPro by remember { mutableStateOf(false) }
    if (showPro) ProUpsellDialog(onDismiss = { showPro = false })
    CompositionLocalProvider(LocalShowProUpsell provides { showPro = true }) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    title = {
                        // Two compact lines that never wrap: name on top, LED + state below.
                        Column {
                            Text(
                                "OneProxyClick",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val (label, color) = when (tunnelState) {
                                TunnelState.RUNNING -> stringResource(R.string.top_state_on, port?.toString() ?: "?") to OpcColors.Green
                                TunnelState.STARTING -> stringResource(R.string.top_state_starting) to OpcColors.Yellow
                                TunnelState.ERROR -> stringResource(R.string.top_state_error) to OpcColors.Red
                                TunnelState.STOPPED -> stringResource(R.string.top_state_off) to OpcColors.TextDim
                            }
                            Text(
                                "● $label",
                                color = color,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        FilterChip(
                            selected = overlayShowing,
                            onClick = {
                                when {
                                    overlayShowing -> OverlayContract.hide(context)
                                    !OverlayContract.canDrawOverlays(context) -> onRequestOverlay()
                                    else -> OverlayContract.show(context)
                                }
                            },
                            label = { Text(stringResource(R.string.top_bubble), fontSize = 12.sp) },
                        )
                        Spacer(Modifier.width(8.dp))
                        val proxyOn = tunnelState == TunnelState.RUNNING || tunnelState == TunnelState.STARTING
                        Text(
                            stringResource(R.string.top_vpn),
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            color = if (proxyOn) OpcColors.Orange else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Switch(
                            checked = proxyOn,
                            onCheckedChange = { on ->
                                if (on) {
                                    if (!VpnController.start(context)) onRequestVpn(true)
                                } else {
                                    VpnController.stop(context)
                                }
                            },
                            colors = opcSwitchColors(),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    MainTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { AutoShrinkLabel(stringResource(t.labelRes)) },
                            icon = {
                                when (t) {
                                    MainTab.PROXY -> Icon(Icons.AutoMirrored.Filled.List, null)
                                    MainTab.INTERCEPT -> BadgedBox(badge = {
                                        if (queue.isNotEmpty()) Badge { Text(queue.size.toString()) }
                                    }) {
                                        Icon(Icons.Default.Lock, null, tint = if (interceptOn) OpcColors.Orange else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    MainTab.REPEATER -> Icon(Icons.AutoMirrored.Filled.Send, null)
                                    MainTab.TOOLS -> Icon(Icons.Default.Build, null)
                                }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    MainTab.PROXY -> HistoryScreen(
                        openTransactionId = openTransactionId,
                        onOpenTransaction = { openTransactionId = it },
                        onSendToRepeater = sendToRepeater,
                    )
                    MainTab.INTERCEPT -> InterceptScreen(onSendToRepeater = sendToRepeater)
                    MainTab.REPEATER -> RepeaterScreen(
                        repeaterViewModel,
                        onProRequired = { showPro = true },
                        onSaveJson = { entry -> saveRepeaterJson(context, entry) },
                    )
                    MainTab.TOOLS -> ToolsScreen(permissions = permissions, onOpenSetup = { setupDismissed = false })
                }
            }
        }
    }
}

/** Repeater "Save JSON" -> Download/OneProxyClick_Exports; returns the localized Snackbar text. */
private suspend fun saveRepeaterJson(context: Context, entry: RepeaterEntry): String {
    val request = entry.request ?: return context.tr(R.string.msg_export_error, "no request")
    return try {
        val r = context.appGraph.exporter.exportRepeater(
            request, entry.response, entry.sentAt, entry.durationMs, entry.error,
        )
        context.tr(R.string.msg_saved, r.saved.displayPath)
    } catch (e: Exception) {
        context.tr(R.string.msg_export_error, e.message ?: e.javaClass.simpleName)
    }
}

/**
 * Bottom-bar label that always fits on one line: starts at the theme's label size and shrinks
 * (down to 9sp) until the text no longer overflows, e.g. "Herramientas" on a 360dp-wide phone.
 * Drawing is skipped until the final size is found, so there is no visible flicker; ellipsis is
 * only a last resort for extreme font scales.
 */
@Composable
private fun AutoShrinkLabel(text: String) {
    val baseSize = MaterialTheme.typography.labelMedium.fontSize
    var fontSize by remember(text, baseSize) { mutableStateOf(baseSize) }
    var ready by remember(text, baseSize) { mutableStateOf(false) }
    Text(
        text = text,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize.value > 9f) {
                fontSize = (fontSize.value * 0.9f).coerceAtLeast(9f).sp
            } else {
                ready = true
            }
        },
        modifier = Modifier.drawWithContent { if (ready) drawContent() },
    )
}
