package com.oneproxyclick.core.proxy

import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoop
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException

/** Receives aggregated responses for the request currently in flight. */
internal class UpstreamResponseHandler : ChannelInboundHandlerAdapter() {
    @Volatile var pending: CompletableDeferred<FullHttpResponse>? = null

    /** Raw bytes the server sent right after a 101 response (before relay mode was installed). */
    private val earlyBytes = ArrayList<ByteBuf>()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is FullHttpResponse -> {
                if (msg.status().code() == 101) ctx.channel().config().isAutoRead = false
                val p = pending
                pending = null
                if (p == null || !p.complete(msg)) msg.release()
            }
            is ByteBuf -> earlyBytes += msg
            else -> ReferenceCountUtil.release(msg)
        }
    }

    /** Must be called on the channel's event loop. */
    fun drainEarlyBytes(): List<ByteBuf> = ArrayList(earlyBytes).also { earlyBytes.clear() }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        pending?.completeExceptionally(ClosedChannelException())
        pending = null
        earlyBytes.forEach { it.release() }
        earlyBytes.clear()
        ctx.fireChannelInactive()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        pending?.completeExceptionally(cause)
        pending = null
        ctx.close()
    }
}

/** One keep-alive connection from the proxy to an origin server. */
internal class UpstreamConnection private constructor(
    val key: String,
    val channel: Channel,
    val handler: UpstreamResponseHandler,
) {
    val isActive: Boolean get() = channel.isActive

    suspend fun execute(request: HttpRequestData, timeoutMs: Long): HttpResponseData {
        val deferred = CompletableDeferred<FullHttpResponse>()
        handler.pending = deferred
        channel.writeAndFlush(request.toNetty()).awaitDone()
        val full = try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            channel.close()
            throw IOException("Upstream timeout after ${timeoutMs / 1000}s")
        }
        return try {
            full.toData()
        } finally {
            full.release()
        }
    }

    fun close() {
        channel.close()
    }

    companion object {
        fun keyOf(req: HttpRequestData) = "${req.scheme}://${req.host}:${req.port}"

        suspend fun open(engine: ProxyEngine, loop: EventLoop, request: HttpRequestData): UpstreamConnection {
            val host = request.host
            val port = request.port
            val tls = request.isTls
            val address = withContext(Dispatchers.IO) { InetAddress.getByName(host) }
            val verify = engine.config.verifyUpstreamTls
            val handler = UpstreamResponseHandler()
            val bootstrap = Bootstrap()
                .group(loop)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15_000)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()
                        if (tls) {
                            val context = if (verify) engine.tls.verifyingClient else engine.tls.insecureClient
                            val ssl = context.newHandler(ch.alloc(), host, port)
                            if (verify) {
                                val params = ssl.engine().sslParameters
                                params.endpointIdentificationAlgorithm = "HTTPS"
                                ssl.engine().sslParameters = params
                            }
                            ssl.setHandshakeTimeoutMillis(15_000)
                            p.addLast("ssl", ssl)
                        }
                        p.addLast("codec", HttpClientCodec(8192, 65536, 8192))
                        p.addLast("aggregator", HttpObjectAggregator(ProxyConfig.MAX_MESSAGE_BYTES))
                        p.addLast("handler", handler)
                    }
                })
            val future = bootstrap.connect(InetSocketAddress(address, port))
            future.awaitDone()
            val channel = future.channel()
            if (tls) {
                try {
                    channel.pipeline().get(SslHandler::class.java).handshakeFuture().awaitDone()
                } catch (e: Exception) {
                    channel.close()
                    throw e
                }
            }
            return UpstreamConnection(keyOf(request), channel, handler)
        }
    }
}

/** Byte-level bidirectional relay (WebSocket after upgrade, blind TLS tunnels). */
internal class RelayHandler(private val peer: Channel) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (peer.isActive) {
            peer.writeAndFlush(msg).addListener(ChannelFutureListener { f -> if (!f.isSuccess) ctx.channel().close() })
        } else {
            ReferenceCountUtil.release(msg)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        peer.closeOnFlush()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.channel().closeOnFlush()
    }
}
