package com.oneproxyclick.app.bridge

import android.content.Context
import android.content.Intent
import com.oneproxyclick.app.OneProxyClickApp
import com.oneproxyclick.core.interceptor.InterceptorBus
import com.oneproxyclick.core.vpn.TunnelState
import com.oneproxyclick.core.vpn.VpnController
import com.oneproxyclick.feature.overlay.OverlayContract
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Remote-control operations shared by the local HTTP API ([ControlApi]).
 * Every call returns an [Outcome] whose [Outcome.httpStatus] maps directly onto the API response.
 */
object ProxyController {

    /** MainActivity: start the tunnel from the foreground, then move back behind the terminal. */
    const val EXTRA_UI_START_PROXY = "com.oneproxyclick.extra.UI_START_PROXY"

    data class Outcome(val httpStatus: Int, val code: String, val message: String) {
        val ok: Boolean get() = httpStatus in 200..299
    }

    private fun ok(message: String) = Outcome(200, "ok", message)

    fun status(): JSONObject = JSONObject().apply {
        put("tunnel", VpnController.state.value.name.lowercase())
        put("running", VpnController.state.value == TunnelState.RUNNING)
        put("proxy_port", VpnController.activePort.value ?: JSONObject.NULL)
        put("intercept", InterceptorBus.interceptEnabled.value)
        put("intercept_responses", InterceptorBus.interceptResponses.value)
        put("host_filter", InterceptorBus.hostFilter.value)
        put("held", InterceptorBus.queue.value.size)
        put("last_error", VpnController.lastError.value ?: JSONObject.NULL)
    }

    suspend fun start(app: OneProxyClickApp, requestedPort: Int?): Outcome {
        val graph = app.graph
        if (requestedPort != null) {
            if (requestedPort !in 1024..65535) return Outcome(400, "bad_port", "port must be 1024..65535")
            val controlPort = graph.engineSettings.settings.first().controlPort
            if (requestedPort == controlPort) {
                return Outcome(400, "bad_port", "port $requestedPort is used by the control API")
            }
            graph.engineSettings.update { it.copy(port = requestedPort) }
            // Running on another port: restart so the VPN advertises the new one.
            val active = VpnController.activePort.value
            if (VpnController.isActive && active != null && active != requestedPort) {
                VpnController.stop(app)
                awaitState(TunnelState.STOPPED, TunnelState.ERROR)
            }
        }
        if (!VpnController.isPermissionGranted(app)) {
            launchUi(app, requestVpn = true)
            return Outcome(409, "needs_vpn_consent", "VPN consent missing: accept the dialog in OneProxyClick, then retry")
        }
        if (VpnController.state.value == TunnelState.RUNNING) return ok("already running")
        try {
            VpnController.start(app)
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground-service start from the background
            // (e.g. no visible overlay on Android 15): fall back to the activity trampoline.
            launchUi(app, startProxy = true)
            val state = awaitState(TunnelState.RUNNING, TunnelState.ERROR)
            return if (state == TunnelState.RUNNING) ok("running (started via UI)")
            else Outcome(409, "needs_ui", "background start refused (${e.javaClass.simpleName}); opened the app to start it")
        }
        val state = awaitState(TunnelState.RUNNING, TunnelState.ERROR)
        return if (state == TunnelState.RUNNING) ok("running")
        else Outcome(500, "start_failed", "failed: ${VpnController.lastError.value ?: state.name.lowercase()}")
    }

    suspend fun stop(app: OneProxyClickApp): Outcome {
        // Release anything held so the overlay/LED returns to a clean state.
        val released = InterceptorBus.queue.value.size
        InterceptorBus.forwardAll()
        VpnController.stop(app)
        awaitState(TunnelState.STOPPED, TunnelState.ERROR)
        return ok("stopped" + if (released > 0) " (released $released held)" else "")
    }

    fun intercept(mode: String): Outcome {
        val enabled = when (mode.lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            "toggle", "" -> !InterceptorBus.interceptEnabled.value
            else -> return Outcome(400, "bad_mode", "mode must be on|off|toggle")
        }
        InterceptorBus.setInterceptEnabled(enabled)
        return ok("intercept=" + if (enabled) "on" else "off")
    }

    fun filter(regex: String): Outcome {
        if (regex.isNotBlank()) {
            runCatching { Regex(regex) }.onFailure {
                return Outcome(400, "bad_regex", "invalid regex: ${it.message?.lineSequence()?.firstOrNull()}")
            }
        }
        InterceptorBus.setHostFilter(regex)
        return ok("host_filter=" + regex.ifBlank { "<all>" })
    }

    private suspend fun awaitState(vararg targets: TunnelState): TunnelState = try {
        withTimeout(8_000) { VpnController.state.first { it in targets } }
    } catch (_: TimeoutCancellationException) {
        VpnController.state.value
    }

    private fun launchUi(context: Context, requestVpn: Boolean = false, startProxy: Boolean = false) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (requestVpn) launch.putExtra(OverlayContract.EXTRA_REQUEST_VPN_PERMISSION, true)
        if (startProxy) launch.putExtra(EXTRA_UI_START_PROXY, true)
        runCatching { context.startActivity(launch) }
    }
}
