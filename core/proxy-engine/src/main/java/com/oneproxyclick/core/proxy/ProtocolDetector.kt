package com.oneproxyclick.core.proxy

import com.oneproxyclick.core.interceptor.model.CapturedExchange
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.SniHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.util.Mapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress

internal data class TunnelTarget(val host: String, val port: Int, val tls: Boolean)

/**
 * Installed right after a CONNECT is accepted. Peeks the first byte of the tunnel:
 * `0x16` (TLS handshake) → MITM with a dynamic leaf certificate; anything else → plain HTTP
 * inside the tunnel. Pass-through hosts (or MITM disabled) are relayed blindly.
 */
internal class ProtocolDetector(
    private val engine: ProxyEngine,
    private val host: String,
    private val port: Int,
) : ByteToMessageDecoder() {

    private var decided = false

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (decided || input.readableBytes() < 1) return
        decided = true
        val isTls = input.getUnsignedByte(input.readerIndex()).toInt() == 0x16
        val p = ctx.pipeline()
        if (engine.shouldPassthrough(host) || (isTls && !engine.config.mitmEnabled)) {
            startBlindRelay(ctx)
            return
        }
        var last = ctx.name()
        if (isTls) {
            val mapping: Mapping<String?, SslContext> = Mapping { sni -> engine.tls.serverContext(sni ?: host) }
            p.addAfter(last, "sni", SniHandler(mapping)); last = "sni"
            p.addAfter(last, "tlsWatcher", HandshakeWatcher(engine, host, port)); last = "tlsWatcher"
        }
        p.addAfter(last, ProxyFrontendHandler.CODEC, HttpServerCodec(16_384, 65_536, 8192))
        p.addAfter(ProxyFrontendHandler.CODEC, ProxyFrontendHandler.AGGREGATOR, HttpObjectAggregator(ProxyConfig.MAX_MESSAGE_BYTES))
        p.addAfter(
            ProxyFrontendHandler.AGGREGATOR,
            ProxyFrontendHandler.FRONTEND,
            ProxyFrontendHandler(engine, TunnelTarget(host, port, isTls)),
        )
        // Removing a ByteToMessageDecoder forwards the bytes it holds to the next handler.
        p.remove(this)
    }

    private fun startBlindRelay(ctx: ChannelHandlerContext) {
        val client = ctx.channel()
        client.config().isAutoRead = false
        val started = System.currentTimeMillis()
        engine.scope.launch {
            try {
                val address = withContext(Dispatchers.IO) { InetAddress.getByName(host) }
                val future = Bootstrap()
                    .group(client.eventLoop())
                    .channel(NioSocketChannel::class.java)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15_000)
                    .handler(RelayHandler(client))
                    .connect(InetSocketAddress(address, port))
                future.awaitDone()
                val upstream = future.channel()
                client.closeFuture().addListener(ChannelFutureListener { upstream.close() })
                client.eventLoop().execute {
                    val pipeline = client.pipeline()
                    pipeline.addLast("relay", RelayHandler(upstream))
                    if (pipeline.get(ProtocolDetector::class.java) != null) pipeline.remove(this@ProtocolDetector)
                    client.config().isAutoRead = true
                }
                engine.record(
                    CapturedExchange(started, System.currentTimeMillis() - started, tunnelRequest(), null, "Tunnel (not decrypted)"),
                )
            } catch (e: Exception) {
                engine.record(
                    CapturedExchange(started, System.currentTimeMillis() - started, tunnelRequest(), null, "Tunnel failed: ${e.message ?: e.javaClass.simpleName}"),
                )
                client.close()
            }
        }
    }

    private fun tunnelRequest() = HttpRequestData(
        method = "CONNECT", scheme = "https", host = host, port = port,
        target = "/", headers = emptyList(),
    )
}

/** Records failed client handshakes (typical for apps that don't trust the CA or pin certs). */
internal class HandshakeWatcher(
    private val engine: ProxyEngine,
    private val host: String,
    private val port: Int,
) : ChannelInboundHandlerAdapter() {

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is SslHandshakeCompletionEvent) {
            if (evt.isSuccess) {
                ctx.pipeline().remove(this)
            } else {
                val cause = evt.cause()
                engine.record(
                    CapturedExchange(
                        startedAt = System.currentTimeMillis(),
                        durationMs = 0,
                        request = HttpRequestData("CONNECT", "https", host, port, "/", headers = emptyList()),
                        response = null,
                        error = "Client TLS handshake failed (CA not trusted by the app or certificate pinning): " +
                            (cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"),
                    ),
                )
                ctx.close()
                return
            }
        }
        ctx.fireUserEventTriggered(evt)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }
}
