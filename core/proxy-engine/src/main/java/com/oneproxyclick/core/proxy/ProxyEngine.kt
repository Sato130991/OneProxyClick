package com.oneproxyclick.core.proxy

import com.oneproxyclick.core.ca.CertificateAuthority
import com.oneproxyclick.core.interceptor.model.CapturedExchange
import com.oneproxyclick.core.interceptor.model.ExchangeRecorder
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Embedded HTTP/HTTPS MITM proxy (Netty NIO) listening on [ProxyConfig.bindHost]:[ProxyConfig.port].
 *
 * Pipeline per client connection:
 * `HttpServerCodec → HttpObjectAggregator → ProxyFrontendHandler`; after `CONNECT` the
 * codec is swapped for `ProtocolDetector`, which installs `SniHandler(SslContext per host)` +
 * a new HTTP pipeline (or a blind relay).
 */
class ProxyEngine(
    private val ca: CertificateAuthority,
    private val recorder: ExchangeRecorder,
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile var config: ProxyConfig = ProxyConfig()
        private set

    internal val tls = TlsContexts(ca)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var serverChannel: Channel? = null

    val isRunning: Boolean get() = serverChannel?.isActive == true

    /** Starts (or restarts on a new port) the server. Blocking; call off the main thread. */
    @Synchronized
    fun start(newConfig: ProxyConfig): Int {
        if (isRunning && newConfig.port == config.port && newConfig.bindHost == config.bindHost) {
            config = newConfig
            return newConfig.port
        }
        stopInternal()
        config = newConfig
        try {
            ca.ensureInitialized()
            val boss = NioEventLoopGroup(1)
            val workers = NioEventLoopGroup(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            bossGroup = boss
            workerGroup = workers
            val bootstrap = ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel::class.java)
                .option(ChannelOption.SO_BACKLOG, 512)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline()
                            .addLast(ProxyFrontendHandler.CODEC, HttpServerCodec(16_384, 65_536, 8192))
                            .addLast(ProxyFrontendHandler.AGGREGATOR, HttpObjectAggregator(ProxyConfig.MAX_MESSAGE_BYTES))
                            .addLast(ProxyFrontendHandler.FRONTEND, ProxyFrontendHandler(this@ProxyEngine, null))
                    }
                })
            serverChannel = bootstrap.bind(newConfig.bindHost, newConfig.port).sync().channel()
            _state.value = EngineState.Running(newConfig.bindHost, newConfig.port)
            return newConfig.port
        } catch (e: Exception) {
            stopInternal()
            val message = "Cannot listen on ${newConfig.bindHost}:${newConfig.port}: ${e.message ?: e.javaClass.simpleName}"
            _state.value = EngineState.Failed(message)
            throw IllegalStateException(message, e)
        }
    }

    /** Applies settings that don't need a re-bind (MITM, verification, pass-through list). */
    fun updateConfig(newConfig: ProxyConfig) {
        if (newConfig.port != config.port || newConfig.bindHost != config.bindHost) {
            if (isRunning) start(newConfig) else config = newConfig
        } else {
            config = newConfig
        }
    }

    @Synchronized
    fun stop() {
        stopInternal()
        _state.value = EngineState.Stopped
    }

    /** Forget cached leaf contexts (e.g. after regenerating the CA). */
    fun clearTlsCache() = tls.clear()

    private fun stopInternal() {
        runCatching { serverChannel?.close()?.syncUninterruptibly() }
        serverChannel = null
        bossGroup?.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        workerGroup?.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        bossGroup = null
        workerGroup = null
    }

    internal fun record(exchange: CapturedExchange) = recorder.record(exchange)

    internal fun caPem(): String = ca.caPem()

    internal fun shouldPassthrough(host: String): Boolean {
        val h = host.lowercase()
        return config.passthroughHosts.any { raw ->
            val p = raw.trim().lowercase().removePrefix("*.")
            p.isNotEmpty() && (h == p || h.endsWith(".$p"))
        }
    }
}
