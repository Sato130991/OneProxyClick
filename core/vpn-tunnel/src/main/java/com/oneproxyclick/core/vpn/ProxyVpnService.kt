package com.oneproxyclick.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.oneproxyclick.core.common.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.Inet4Address

/**
 * "Proxy-only" VPN: it publishes `127.0.0.1:<port>` as the HTTP proxy of the VPN network
 * ([VpnService.Builder.setHttpProxy], API 29+) so proxy-aware apps send HTTP(S) through the
 * local engine. The TUN interface (10.0.0.2/32) only routes an unused /32, so no packet
 * forwarding is required and non-HTTP traffic keeps flowing over the underlying network.
 *
 * Anti-loop: this app and terminal apps (KaliTerm, Termux) are always excluded with
 * [VpnService.Builder.addDisallowedApplication].
 */
class ProxyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var drainThread: Thread? = null
    private var establishJob: Job? = null
    @Volatile private var proxyStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Satisfy the startForeground contract if the service was just (re)created by this intent.
            promoteToForeground(NotificationText.Stopping)
            shutdown()
            return START_NOT_STICKY
        }
        promoteToForeground(NotificationText.Starting)
        if (tun == null && establishJob?.isActive != true) {
            establishJob = scope.launch { establish() }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Language switch while the tunnel runs: refresh the notification, keep the VPN up.
        scope.launch(Dispatchers.Main) {
            LanguageManager.language.drop(1).collect {
                if (tun != null || establishJob?.isActive == true) promoteToForeground(notificationText)
            }
        }
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        closeTunnel()
        if (proxyStarted) {
            proxyStarted = false
            runCatching { VpnController.hook?.onTunnelStopped(applicationContext) }
        }
        if (VpnController.state.value != TunnelState.ERROR) VpnController.update(TunnelState.STOPPED)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun establish() {
        VpnController.update(TunnelState.STARTING)
        try {
            val hook = VpnController.hook ?: error(l10n().getString(R.string.vpn_error_no_engine))
            val port = hook.onTunnelStarting(applicationContext)
            proxyStarted = true
            val settings = TunnelSettingsStore(this).settings.first()

            val builder = Builder()
                .setSession(l10n().getString(R.string.vpn_session_name))
                .setMtu(1500)
                .addAddress(TUN_ADDRESS, 32)
                .addRoute(DUMMY_ROUTE, 32)
                .setHttpProxy(ProxyInfo.buildDirectProxy(PROXY_HOST, port))
                .setBlocking(true)
                .setMetered(false)
            underlyingDnsServers().forEach { builder.addDnsServer(it) }
            launchIntent()?.let { builder.setConfigureIntent(it) }
            applySplitTunnel(builder, settings)

            val pfd = builder.establish() ?: error(l10n().getString(R.string.vpn_error_consent_revoked))
            tun = pfd
            startDrain(pfd)
            VpnController.update(TunnelState.RUNNING, port = port)
            withContext(Dispatchers.Main) {
                promoteToForeground(NotificationText.Running("$PROXY_HOST:$port"))
            }
        } catch (e: Exception) {
            VpnController.update(TunnelState.ERROR, e.message ?: e.javaClass.simpleName)
            withContext(Dispatchers.Main) { shutdown(keepErrorState = true) }
        }
    }

    private fun applySplitTunnel(builder: Builder, settings: TunnelSettings) {
        val excludedAlways = InstalledApps.alwaysExcluded(this)
        if (settings.mode == SplitMode.INCLUDE) {
            var added = 0
            settings.packages.filterNot { it in excludedAlways }.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                    added++
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            if (added > 0) return
            // Nothing valid selected: fall back to exclude mode so the app itself is never captured.
        }
        (excludedAlways + settings.packages).forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
    }

    /** DNS servers of the current underlying network (this app is outside the VPN). */
    private fun underlyingDnsServers(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val servers = runCatching {
            cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        return servers.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
    }

    /** Reads (and discards) whatever reaches the TUN so the kernel queue never fills up. */
    private fun startDrain(pfd: ParcelFileDescriptor) {
        drainThread = Thread({
            val buffer = ByteArray(32 * 1024)
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    while (!Thread.currentThread().isInterrupted) {
                        if (input.read(buffer) < 0) break
                    }
                }
            } catch (_: Exception) {
            }
        }, "opc-tun-drain").apply {
            isDaemon = true
            start()
        }
    }

    private fun closeTunnel() {
        drainThread?.interrupt()
        drainThread = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun shutdown(keepErrorState: Boolean = false) {
        establishJob?.cancel()
        closeTunnel()
        if (proxyStarted) {
            proxyStarted = false
            runCatching { VpnController.hook?.onTunnelStopped(applicationContext) }
        }
        if (!keepErrorState) VpnController.update(TunnelState.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- notification --------------------------------------------------------------------

    /** What the notification says; re-rendered in the current language when it changes. */
    private sealed interface NotificationText {
        data object Starting : NotificationText
        data object Stopping : NotificationText
        data class Running(val address: String) : NotificationText
    }

    @Volatile private var notificationText: NotificationText = NotificationText.Starting

    /** Strings in the in-app language (not the system one). */
    private fun l10n() = LanguageManager.localized(this)

    private fun render(text: NotificationText): String = when (text) {
        NotificationText.Starting -> l10n().getString(R.string.vpn_notification_starting)
        NotificationText.Stopping -> l10n().getString(R.string.vpn_notification_stopping)
        is NotificationText.Running -> l10n().getString(R.string.vpn_notification_running, text.address)
    }

    private fun promoteToForeground(text: NotificationText) {
        notificationText = text
        promoteToForeground(render(text))
    }

    private fun promoteToForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        run {
            // (Re)creating a channel with the same id only updates its name -> follows the language.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, l10n().getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(l10n().getString(R.string.vpn_session_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(launchIntent())
            .addAction(0, l10n().getString(R.string.vpn_action_stop), stopIntent)
            .build()
    }

    private fun launchIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_START = "com.oneproxyclick.vpn.START"
        const val ACTION_STOP = "com.oneproxyclick.vpn.STOP"
        private const val CHANNEL_ID = "proxy_tunnel"
        private const val NOTIFICATION_ID = 4201
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val DUMMY_ROUTE = "10.0.0.1"
        const val PROXY_HOST = "127.0.0.1"
    }
}
