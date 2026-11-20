package com.oneproxyclick.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TunnelState { STOPPED, STARTING, RUNNING, ERROR }

/**
 * Lets the app start/stop the proxy engine together with the tunnel without this module
 * depending on the engine.
 */
interface TunnelLifecycleHook {
    /** Called on a background thread before the TUN is established; returns the proxy port. */
    fun onTunnelStarting(context: Context): Int

    fun onTunnelStopped(context: Context)
}

object VpnController {
    private val _state = MutableStateFlow(TunnelState.STOPPED)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _activePort = MutableStateFlow<Int?>(null)
    val activePort: StateFlow<Int?> = _activePort.asStateFlow()

    @Volatile var hook: TunnelLifecycleHook? = null

    /** True once the user accepted the system VPN dialog ([VpnService.prepare] returns null). */
    fun isPermissionGranted(context: Context): Boolean = VpnService.prepare(context) == null

    val isActive: Boolean
        get() = _state.value == TunnelState.RUNNING || _state.value == TunnelState.STARTING

    /** @return false when the VPN consent is missing (call [VpnService.prepare] from an Activity). */
    fun start(context: Context): Boolean {
        if (!isPermissionGranted(context)) return false
        if (isActive) return true
        _lastError.value = null
        val intent = Intent(context, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    fun stop(context: Context) {
        if (_state.value == TunnelState.STOPPED) return
        runCatching {
            context.startService(Intent(context, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
        }
    }

    /** @return false if the tunnel could not be started because consent is missing. */
    fun toggle(context: Context): Boolean =
        if (isActive) {
            stop(context)
            true
        } else {
            start(context)
        }

    internal fun update(state: TunnelState, error: String? = null, port: Int? = null) {
        _state.value = state
        if (error != null) _lastError.value = error
        _activePort.value = if (state == TunnelState.RUNNING) port else null
    }
}
