package com.oneproxyclick.app

import kotlinx.coroutines.flow.combine
import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.core.common.pro.Pro
import com.oneproxyclick.app.pro.ProFeatures
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import com.oneproxyclick.app.bridge.LocalControlServer
import com.oneproxyclick.app.bridge.ControlApi
import com.oneproxyclick.core.common.LanguageManager
import android.app.Application
import android.content.Context
import com.oneproxyclick.app.bridge.LocalForwarder
import com.oneproxyclick.core.ca.CertificateAuthority
import com.oneproxyclick.core.interceptor.InterceptorBus
import com.oneproxyclick.core.interceptor.rules.MatchReplaceRule
import com.oneproxyclick.core.interceptor.rules.MatchReplaceStore
import com.oneproxyclick.core.proxy.ProxyEngine
import com.oneproxyclick.core.storage.HistoryRepository
import com.oneproxyclick.core.storage.export.ExportWriter
import com.oneproxyclick.core.storage.export.HistoryExporter
import com.oneproxyclick.core.storage.export.LiveJsonlRecorder
import com.oneproxyclick.core.vpn.TunnelLifecycleHook
import com.oneproxyclick.core.vpn.TunnelSettingsStore
import com.oneproxyclick.core.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Manual dependency graph (no DI framework needed for a handful of singletons). */
class AppGraph(context: Context) {
    val ca: CertificateAuthority = CertificateAuthority.getInstance(context)
    val history: HistoryRepository = HistoryRepository.get(context)
    val engine: ProxyEngine = ProxyEngine(ca, history)
    val engineSettings = EngineSettingsStore(context)
    val tunnelSettings = TunnelSettingsStore(context)
    private val rulesStore = MatchReplaceStore(context)

    /** JSON exports to Download/OneProxyClick_Exports/ (MediaStore, no storage permission). */
    val exportWriter = ExportWriter(context)
    val exporter = HistoryExporter(history, exportWriter)
    val liveJsonl = LiveJsonlRecorder(exportWriter)

    /** KaliTerm Bridge: "Reenviar a puerto local". */
    val forwarder = LocalForwarder()

    /** Local control API on 127.0.0.1 (replaces `am broadcast`). Handler is set by the Application. */
    lateinit var controlServer: LocalControlServer
        internal set

    /** "listening" or a human-readable error, shown in Settings. */
    val controlServerState = MutableStateFlow<ControlServerState>(ControlServerState.Stopped)

    init {
        // Runs on the storage IO coroutine after each insert; both sinks only enqueue.
        history.onStored = { id, exchange ->
            liveJsonl.offer(id, exchange)
            forwarder.offer(id, exchange)
        }
    }

    /**
     * Saved Match & Replace rules. What the engine actually applies is decided in the Application:
     * these rules when MATCH_REPLACE is unlocked, nothing otherwise (rules are kept, not deleted).
     */
    val storedRules = MutableStateFlow(rulesStore.load())

    fun saveRules(rules: List<MatchReplaceRule>) {
        storedRules.value = rules
        rulesStore.save(rules)
    }
}

sealed interface ControlServerState {
    data object Stopped : ControlServerState
    data class Listening(val port: Int) : ControlServerState
    data class Error(val port: Int, val message: String) : ControlServerState
    /** play flavor without Pro: the API is not started at all. */
    data object Locked : ControlServerState
}

class OneProxyClickApp : Application() {

    lateinit var graph: AppGraph
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // In-app language (saved choice, or Spanish when the system is Spanish) before any UI/service.
        LanguageManager.init(this)
        // Feature gate (foss: everything unlocked; play: Google Play Billing) before anything reads it.
        Pro.install(ProFeatures.create(this))
        Pro.manager.connect()
        graph = AppGraph(this)

        // Match & Replace: apply stored rules only while the feature is unlocked.
        appScope.launch {
            combine(graph.storedRules, Pro.manager.isPro) { rules, _ ->
                if (Pro.isUnlocked(ProFeature.MATCH_REPLACE)) rules else emptyList()
            }.collect { InterceptorBus.setRules(it) }
        }

        // Root CA is generated on first launch (RSA keygen takes ~0.5 s on a phone).
        appScope.launch(Dispatchers.IO) { runCatching { graph.ca.ensureInitialized() } }

        VpnController.hook = object : TunnelLifecycleHook {
            override fun onTunnelStarting(context: Context): Int {
                val settings = runBlocking { graph.engineSettings.settings.first() }
                return graph.engine.start(settings.toProxyConfig())
            }

            override fun onTunnelStopped(context: Context) {
                graph.engine.stop()
            }
        }

        // Local control API: (re)bind whenever its port (or the proxy port it must not clash with) changes.
        graph.controlServer = LocalControlServer(ControlApi(this)::handle)
        appScope.launch(Dispatchers.IO) {
            combine(graph.engineSettings.settings, Pro.manager.isPro) { s, _ ->
                Triple(s.controlPort, s.port, Pro.isUnlocked(ProFeature.KALITERM_BRIDGE))
            }
                .distinctUntilChanged()
                .collect { (controlPort, proxyPort, unlocked) ->
                    graph.controlServerState.value = if (!unlocked) {
                        graph.controlServer.stop()
                        ControlServerState.Locked
                    } else if (controlPort == proxyPort) {
                        graph.controlServer.stop()
                        ControlServerState.Error(controlPort, "same port as the proxy ($proxyPort)")
                    } else try {
                        graph.controlServer.start(controlPort)
                        ControlServerState.Listening(controlPort)
                    } catch (e: Exception) {
                        ControlServerState.Error(controlPort, e.message ?: e.javaClass.simpleName)
                    }
                }
        }

        // Auto-JSON GET/POST toggle (persisted).
        appScope.launch {
            combine(graph.engineSettings.settings, Pro.manager.isPro) { s, _ -> s }.collect { s ->
                // Enforced here (not only in the UI): locked features never write or send anything.
                graph.liveJsonl.enabled = s.autoJsonLive && Pro.isUnlocked(ProFeature.LIVE_JSON)
                val target = LocalForwarder.parseTarget(s.forwardTarget)
                graph.forwarder.config = LocalForwarder.Config(
                    enabled = s.forwardEnabled && target != null && Pro.isUnlocked(ProFeature.KALITERM_BRIDGE),
                    host = target?.first ?: "127.0.0.1",
                    port = target?.second ?: 9090,
                    tcp = s.forwardTcp,
                    proxyPort = s.port,
                )
            }
        }

        // Live-apply MITM / verification / pass-through changes. A port change is applied on the
        // next tunnel start, because the running VPN already advertises the current port.
        appScope.launch {
            graph.engineSettings.settings.drop(1).collect { s ->
                val engine = graph.engine
                val config = s.toProxyConfig()
                runCatching {
                    engine.updateConfig(if (engine.isRunning) config.copy(port = engine.config.port) else config)
                }
            }
        }
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as OneProxyClickApp).graph
