package com.oneproxyclick.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oneproxyclick.core.proxy.ProxyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class EngineSettings(
    val port: Int = ProxyConfig.DEFAULT_PORT,
    val mitmEnabled: Boolean = true,
    val verifyUpstreamTls: Boolean = true,
    /** One host per line; sub-domains included. */
    val passthroughHosts: String = "",
    /** Auto-JSON GET/POST: append completed GET/POST as JSON Lines under Download/OneProxyClick_Exports */
    val autoJsonLive: Boolean = false,
    /** KaliTerm Bridge: send a copy of each captured JSON to a local receiver. */
    val forwardEnabled: Boolean = false,
    /** host:port of the local receiver, e.g. 127.0.0.1:9090 */
    val forwardTarget: String = DEFAULT_FORWARD_TARGET,
    /** false = HTTP POST (application/json), true = raw TCP JSON Lines. */
    val forwardTcp: Boolean = false,
    /** Local control API requests must carry `Authorization: Bearer <token>`. */
    val requireToken: Boolean = true,
    /** Port of the local control API (bound to 127.0.0.1 only). */
    val controlPort: Int = DEFAULT_CONTROL_PORT,
) {
    fun toProxyConfig() = ProxyConfig(
        port = port,
        mitmEnabled = mitmEnabled,
        verifyUpstreamTls = verifyUpstreamTls,
        passthroughHosts = passthroughHosts.lines().map { it.trim() }.filter { it.isNotEmpty() },
    )

    companion object {
        const val DEFAULT_FORWARD_TARGET = "127.0.0.1:9090"
        const val DEFAULT_CONTROL_PORT = 8081
    }
}

private val Context.engineDataStore: DataStore<Preferences> by preferencesDataStore(name = "engine_settings")

class EngineSettingsStore(context: Context) {
    private val store = context.applicationContext.engineDataStore

    val settings: Flow<EngineSettings> = store.data.map(::read)

    suspend fun update(transform: (EngineSettings) -> EngineSettings) {
        store.edit { p ->
            val next = transform(read(p))
            p[PORT] = next.port.coerceIn(1024, 65535)
            p[MITM] = next.mitmEnabled
            p[VERIFY] = next.verifyUpstreamTls
            p[PASSTHROUGH] = next.passthroughHosts
            p[AUTO_JSON] = next.autoJsonLive
            p[FWD_ENABLED] = next.forwardEnabled
            p[FWD_TARGET] = next.forwardTarget.trim()
            p[FWD_TCP] = next.forwardTcp
            p[REQUIRE_TOKEN] = next.requireToken
            p[CONTROL_PORT] = next.controlPort.coerceIn(1024, 65535)
        }
    }

    private fun read(p: Preferences) = EngineSettings(
        port = p[PORT] ?: ProxyConfig.DEFAULT_PORT,
        mitmEnabled = p[MITM] ?: true,
        verifyUpstreamTls = p[VERIFY] ?: true,
        passthroughHosts = p[PASSTHROUGH] ?: "",
        autoJsonLive = p[AUTO_JSON] ?: false,
        forwardEnabled = p[FWD_ENABLED] ?: false,
        forwardTarget = p[FWD_TARGET] ?: EngineSettings.DEFAULT_FORWARD_TARGET,
        forwardTcp = p[FWD_TCP] ?: false,
        requireToken = p[REQUIRE_TOKEN] ?: true,
        controlPort = p[CONTROL_PORT] ?: EngineSettings.DEFAULT_CONTROL_PORT,
    )

    private companion object {
        val PORT = intPreferencesKey("port")
        val MITM = booleanPreferencesKey("mitm")
        val VERIFY = booleanPreferencesKey("verify_upstream")
        val PASSTHROUGH = stringPreferencesKey("passthrough_hosts")
        val AUTO_JSON = booleanPreferencesKey("auto_json_live")
        val FWD_ENABLED = booleanPreferencesKey("forward_enabled")
        val FWD_TARGET = stringPreferencesKey("forward_target")
        val FWD_TCP = booleanPreferencesKey("forward_tcp")
        val REQUIRE_TOKEN = booleanPreferencesKey("require_token")
        val CONTROL_PORT = intPreferencesKey("control_port")
    }
}
