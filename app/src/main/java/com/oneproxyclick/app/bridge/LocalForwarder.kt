package com.oneproxyclick.app.bridge

import com.oneproxyclick.core.interceptor.codec.RawHttp
import com.oneproxyclick.core.interceptor.model.CapturedExchange
import com.oneproxyclick.core.storage.export.TransactionJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * "Reenviar a puerto local": fire-and-forget copy of every captured transaction (as the same JSON
 * used by the exports) to a receiver script in KaliTerm, e.g. `opc listen 9090`.
 *
 * - HTTP mode: `POST http://host:port/` with `application/json` (OkHttp, no proxy).
 * - TCP mode: one JSON line per connection (`nc -lk 9090` friendly).
 *
 * Capture never waits for it: [offer] enqueues into a bounded channel (oldest dropped when the
 * receiver is slow or down) drained by one coroutine on Dispatchers.IO.
 */
class LocalForwarder {

    data class Config(
        val enabled: Boolean = false,
        val host: String = "127.0.0.1",
        val port: Int = 9090,
        val tcp: Boolean = false,
        /** The proxy's own port; forwarding there would loop. */
        val proxyPort: Int = -1,
    )

    @Volatile var config = Config()

    private val _sent = MutableStateFlow(0L)
    val sent: StateFlow<Long> = _sent.asStateFlow()
    private val _failed = MutableStateFlow(0L)
    val failed: StateFlow<Long> = _failed.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY) // never route through OneProxyClick itself
        .connectTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val queue = Channel<Pair<Long, CapturedExchange>>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for ((id, exchange) in queue) {
                val c = config
                if (!c.enabled) continue
                try {
                    check(!(isLoopback(c.host) && c.port == c.proxyPort)) {
                        "Target ${c.host}:${c.port} is the proxy itself (loop)"
                    }
                    val json = TransactionJson.from(id, exchange).toString()
                    if (c.tcp) sendTcp(c, json) else sendHttp(c, json)
                    _sent.value += 1
                    _lastError.value = null
                } catch (e: Exception) {
                    _failed.value += 1
                    _lastError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun offer(id: Long, exchange: CapturedExchange) {
        if (config.enabled) queue.trySend(id to exchange)
    }

    private fun sendHttp(c: Config, json: String) {
        val host = if (c.host.contains(':')) "[${c.host}]" else c.host
        val request = Request.Builder()
            .url("http://$host:${c.port}/")
            .header("X-OneProxyClick", "1")
            .post(json.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().close()
    }

    private fun sendTcp(c: Config, json: String) {
        Socket(Proxy.NO_PROXY).use { s ->
            s.connect(InetSocketAddress(c.host, c.port), 2000)
            s.soTimeout = 3000
            s.getOutputStream().apply {
                write((json + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun isLoopback(host: String) =
            host == "127.0.0.1" || host.equals("localhost", true) || host == "::1" || host == "0.0.0.0"

        /** Parses "host:port"; null if invalid. */
        fun parseTarget(target: String): Pair<String, Int>? {
            val (host, port) = RawHttp.splitAuthority(target.trim(), -1)
            return if (host.isBlank() || port !in 1..65535) null else host to port
        }
    }
}
