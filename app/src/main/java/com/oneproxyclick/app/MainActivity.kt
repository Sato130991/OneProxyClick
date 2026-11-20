package com.oneproxyclick.app

import com.oneproxyclick.core.common.LanguageManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import com.oneproxyclick.core.common.tr
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.oneproxyclick.app.bridge.ProxyController
import com.oneproxyclick.app.ui.AppRoot
import com.oneproxyclick.app.ui.NavRequest
import com.oneproxyclick.app.ui.PermissionStatus
import com.oneproxyclick.app.ui.theme.OneProxyTheme
import com.oneproxyclick.core.vpn.VpnController
import com.oneproxyclick.feature.overlay.OverlayContract

class MainActivity : ComponentActivity() {

    private val permissions = mutableStateOf(PermissionStatus(vpn = false, overlay = false, notifications = false))
    private val navRequest = mutableStateOf<NavRequest?>(null)
    private var startTunnelAfterConsent = false

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refreshPermissions()
        if (result.resultCode == Activity.RESULT_OK && startTunnelAfterConsent) VpnController.start(this)
        startTunnelAfterConsent = false
    }

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPermissions()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refreshPermissions()
        handleIntent(intent)
        setContent {
            // Runtime language switch: localized Resources are provided to the whole tree, so every
            // stringResource() recomposes immediately — no Activity recreation, VPN untouched.
            val language by LanguageManager.language.collectAsState()
            val baseConfiguration = LocalConfiguration.current
            val localizedContext = remember(language, baseConfiguration) {
                LanguageManager.wrap(this@MainActivity, baseConfiguration, language)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
            ) {
            OneProxyTheme {
                AppRoot(
                    permissions = permissions.value,
                    navRequest = navRequest.value,
                    onNavRequestConsumed = { navRequest.value = null },
                    onRequestVpn = { startAfter -> requestVpnConsent(startAfter) },
                    onRequestOverlay = ::requestOverlayPermission,
                    onRequestNotifications = ::requestNotificationPermission,
                )
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(OverlayContract.EXTRA_REQUEST_VPN_PERMISSION, false)) {
            intent.removeExtra(OverlayContract.EXTRA_REQUEST_VPN_PERMISSION)
            requestVpnConsent(startAfter = true)
        }
        if (intent.getBooleanExtra(ProxyController.EXTRA_UI_START_PROXY, false)) {
            // Trampoline from `opc on` when a background FGS start was refused: start from the
            // foreground, then return the user to the terminal.
            intent.removeExtra(ProxyController.EXTRA_UI_START_PROXY)
            if (VpnController.isPermissionGranted(this)) {
                VpnController.start(this)
                moveTaskToBack(true)
            } else {
                requestVpnConsent(startAfter = true)
            }
        }
        val tab = intent.getStringExtra(OverlayContract.EXTRA_OPEN_TAB) ?: return
        val txId = intent.getLongExtra(OverlayContract.EXTRA_TRANSACTION_ID, -1L).takeIf { it > 0 }
        navRequest.value = NavRequest(tab, txId)
        intent.removeExtra(OverlayContract.EXTRA_OPEN_TAB)
        intent.removeExtra(OverlayContract.EXTRA_TRANSACTION_ID)
    }

    private fun refreshPermissions() {
        permissions.value = PermissionStatus(
            vpn = VpnService.prepare(this) == null,
            overlay = Settings.canDrawOverlays(this),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    /** Step 1: system VPN consent dialog. */
    private fun requestVpnConsent(startAfter: Boolean) {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            refreshPermissions()
            if (startAfter) VpnController.start(this)
            return
        }
        startTunnelAfterConsent = startAfter
        runCatching { vpnConsent.launch(prepare) }.onFailure {
            Toast.makeText(this, tr(R.string.main_vpn_consent_error, it.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
    }

    /** Step 2: "Display over other apps" for the floating button. */
    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { overlayPermission.launch(intent) }.onFailure {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    /** Step 3: notifications for the foreground services (Android 13+). */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshPermissions()
        }
    }
}
