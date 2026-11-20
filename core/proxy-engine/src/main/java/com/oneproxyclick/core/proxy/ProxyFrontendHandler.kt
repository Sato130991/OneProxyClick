package com.oneproxyclick.core.proxy

import com.oneproxyclick.core.interceptor.InterceptorBus
import com.oneproxyclick.core.interceptor.RequestOutcome
import com.oneproxyclick.core.interceptor.ResponseOutcome
import com.oneproxyclick.core.interceptor.codec.RawHttp
import com.oneproxyclick.core.interceptor.model.CapturedExchange
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.channels.ClosedChannelException
import javax.net.ssl.SSLException

/**
 * Client-facing handler (one instance per client connection / tunnel).
 *
 * Requests are processed strictly one at a time: auto-read is disabled while an exchange runs
 * (intercept may hold it for minutes) and re-enabled once the response is flushed.
 */
internal class ProxyFrontendHandler(
    private val engine: ProxyEngine,
    /** null for the outer proxy connection, set inside a CONNECT tunnel. */
    private val tunnel: TunnelTarget?,
) : ChannelInboundHandlerAdapter() {

    @Volatile private var job: Job? = null
    @Volatile private var upstream: UpstreamConnection? = null

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is FullHttpRequest) {
            ReferenceCountUtil.release(msg)
            return
        }
        try {
            if (!msg.decoderResult().isSuccess) {
                ctx.writeAndFlush(simpleResponse(HttpResponseStatus.BAD_REQUEST, "Malformed request"))
                    .addListener(ChannelFutureListener.CLOSE)
                return
            }
            if (tunnel == null && msg.method() == HttpMethod.CONNECT) {
                handleConnect(ctx, msg.uri())
                return
            }
            val request = toRequestData(msg)
            if (request == null) {
                serveLocal(ctx, msg)
                return
            }
            val keepAlive = HttpUtil.isKeepAlive(msg)
            ctx.channel().config().isAutoRead = false
            job = engine.scope.launch { exchange(ctx, request, keepAlive) }
        } finally {
            msg.release()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        job?.cancel()
        upstream?.close()
        upstream = null
        ctx.fireChannelInactive()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }

    // ---- CONNECT -------------------------------------------------------------------------

    private fun handleConnect(ctx: ChannelHandlerContext, authority: String) {
        val (host, port) = RawHttp.splitAuthority(authority, 443)
        if (host.isBlank()) {
            ctx.writeAndFlush(simpleResponse(HttpResponseStatus.BAD_REQUEST, "Bad CONNECT target"))
                .addListener(ChannelFutureListener.CLOSE)
            return
        }
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus(200, "Connection Established"),
            Unpooled.EMPTY_BUFFER,
        )
        ctx.writeAndFlush(response).addListener(ChannelFutureListener { f ->
            if (!f.isSuccess) {
                ctx.close()
                return@ChannelFutureListener
            }
            val p = ctx.pipeline()
            p.addAfter(ctx.name(), "detector", ProtocolDetector(engine, host, port))
            p.remove(AGGREGATOR)
            p.remove(CODEC)
            p.remove(this)
        })
    }

    // ---- request conversion -----------------------------------------------------------------

    /** Returns null when the request is addressed to the proxy itself (served locally). */
    private fun toRequestData(msg: FullHttpRequest): HttpRequestData? {
        val headers = msg.headerList()
        val body = msg.bodyBytes()
        val uri = msg.uri()
        val method = msg.method().name()
        val protocol = msg.protocolVersion().text()
        val lower = uri.lowercase()

        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ws://") || lower.startsWith("wss://")) {
            val scheme = lower.substringBefore("://").let { if (it == "ws") "http" else if (it == "wss") "https" else it }
            val rest = uri.substringAfter("://")
            val authority = rest.substringBefore('/').substringBefore('?')
            var target = rest.substring(authority.length).ifEmpty { "/" }
            if (target.startsWith("?")) target = "/$target"
            val (host, port) = RawHttp.splitAuthority(authority, if (scheme == "https") 443 else 80)
            return HttpRequestData(method, scheme, host, port, target, protocol, headers, body)
        }

        if (tunnel != null) {
            return HttpRequestData(
                method = method,
                scheme = if (tunnel.tls) "https" else "http",
                host = tunnel.host,
                port = tunnel.port,
                target = uri.ifEmpty { "/" },
                protocol = protocol,
                headers = headers,
                body = body,
            )
        }

        // Origin-form on the outer connection: either the proxy itself or a transparent client.
        val hostHeader = msg.headers().get(HttpHeaderNames.HOST) ?: return null
        val (host, port) = RawHttp.splitAuthority(hostHeader, 80)
        if (isSelf(host, port)) return null
        return HttpRequestData(method, "http", host, port, uri.ifEmpty { "/" }, protocol, headers, body)
    }

    private fun isSelf(host: String, port: Int): Boolean {
        val h = host.lowercase()
        val local = h == "127.0.0.1" || h == "localhost" || h == "::1" || h == engine.config.bindHost ||
            h == "oneproxyclick" || h == "proxy.oneproxyclick"
        return local && (port == engine.config.port || h.contains("oneproxyclick"))
    }

    /** Tiny built-in page (like mitm.it) to download the CA from a browser. */
    private fun serveLocal(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        val response = if (msg.uri().startsWith("/cert")) {
            val pem = engine.caPem().toByteArray(Charsets.US_ASCII)
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(pem)).apply {
                headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-x509-ca-cert")
                headers().set("Content-Disposition", "attachment; filename=\"OneProxyClick_CA.crt\"")
                headers().set(HttpHeaderNames.CONTENT_LENGTH, pem.size)
            }
        } else {
            simpleResponse(
                HttpResponseStatus.OK,
                """
                <!doctype html><html><head><meta name="viewport" content="width=device-width"><title>OneProxyClick</title></head>
                <body style="font-family:monospace;background:#0e1116;color:#e6edf3;padding:16px">
                <h2>OneProxyClick proxy is running</h2>
                <p><a style="color:#ff8c1a" href="/cert">Download OneProxyClick CA certificate</a></p>
                <p>Install it under Settings &rsaquo; Security &rsaquo; Encryption &amp; credentials &rsaquo; Install a certificate &rsaquo; CA certificate.</p>
                </body></html>
                """.trimIndent(),
                "text/html; charset=utf-8",
            )
        }
        HttpUtil.setKeepAlive(response, false)
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    // ---- exchange ------------------------------------------------------------------------

    private suspend fun exchange(ctx: ChannelHandlerContext, original: HttpRequestData, keepAlive: Boolean) {
        val started = System.currentTimeMillis()
        var request = original
        var edited = false
        try {
            when (val outcome = InterceptorBus.processRequest(original)) {
                RequestOutcome.Dropped -> {
                    engine.record(CapturedExchange(started, elapsed(started), original, null, "Dropped by user"))
                    ctx.close()
                    return
                }
                is RequestOutcome.Proceed -> {
                    request = outcome.request
                    edited = outcome.edited
                }
            }

            val (connection, response) = send(ctx, request)
            val upstreamDone = System.currentTimeMillis()

            var finalResponse = response
            when (val outcome = InterceptorBus.processResponse(request, response)) {
                ResponseOutcome.Dropped -> {
                    engine.record(CapturedExchange(started, elapsed(started), request, response, "Response dropped by user", edited))
                    ctx.close()
                    return
                }
                is ResponseOutcome.Proceed -> {
                    finalResponse = outcome.response
                    edited = edited || outcome.edited
                }
            }
            val record = CapturedExchange(started, upstreamDone - started, request, finalResponse, null, edited)

            if (finalResponse.statusCode == 101 && isUpgradeRequest(request)) {
                upstream = null
                switchToRelay(ctx, connection, finalResponse, request.method)
                engine.record(record)
                return
            }

            val serverWantsClose = finalResponse.header("Connection")?.contains("close", true) == true
            if (serverWantsClose) {
                connection.close()
                if (upstream === connection) upstream = null
            }
            val future = ctx.writeAndFlush(finalResponse.toNetty(request.method, keepAlive))
            engine.record(record)
            if (keepAlive) future.addListener(ChannelFutureListener { f -> if (f.isSuccess) resumeReading(ctx) else ctx.close() })
            else future.addListener(ChannelFutureListener.CLOSE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = describe(e)
            engine.record(CapturedExchange(started, elapsed(started), request, null, message, edited))
            if (ctx.channel().isActive) {
                val resp = simpleResponse(HttpResponseStatus.BAD_GATEWAY, "OneProxyClick: upstream error\n$message\n")
                HttpUtil.setKeepAlive(resp, keepAlive)
                val f = ctx.writeAndFlush(resp)
                if (keepAlive) f.addListener(ChannelFutureListener { resumeReading(ctx) }) else f.addListener(ChannelFutureListener.CLOSE)
            }
        }
    }

    /** Sends on a cached keep-alive connection; retries once on a fresh one if the cached one was stale. */
    private suspend fun send(ctx: ChannelHandlerContext, request: HttpRequestData): Pair<UpstreamConnection, HttpResponseData> {
        val key = UpstreamConnection.keyOf(request)
        val cached = upstream?.takeIf { it.key == key && it.isActive }
        if (cached != null) {
            try {
                return cached to cached.execute(request, engine.config.upstreamTimeoutMs)
            } catch (e: IOException) {
                cached.close()
                if (e.message?.startsWith("Upstream timeout") == true) throw e
            }
        } else {
            upstream?.close()
        }
        val fresh = UpstreamConnection.open(engine, ctx.channel().eventLoop(), request)
        upstream = fresh
        return fresh to fresh.execute(request, engine.config.upstreamTimeoutMs)
    }

    private fun switchToRelay(ctx: ChannelHandlerContext, connection: UpstreamConnection, response: HttpResponseData, method: String) {
        val client = ctx.channel()
        ctx.writeAndFlush(response.toNetty(method, true)).addListener(ChannelFutureListener { f ->
            if (!f.isSuccess) {
                client.close()
                connection.close()
                return@ChannelFutureListener
            }
            val cp = client.pipeline()
            cp.addLast("relay", RelayHandler(connection.channel))
            cp.remove(this)
            cp.remove(AGGREGATOR)
            cp.remove(CODEC)
            val upChannel = connection.channel
            upChannel.eventLoop().execute {
                val up = upChannel.pipeline()
                up.addLast("relay", RelayHandler(client))
                connection.handler.drainEarlyBytes().forEach { client.write(it) }
                client.flush()
                up.remove("handler")
                up.remove("aggregator")
                up.remove("codec")
                client.closeFuture().addListener(ChannelFutureListener { upChannel.close() })
                upChannel.config().isAutoRead = true
                client.config().isAutoRead = true
            }
        })
    }

    private fun resumeReading(ctx: ChannelHandlerContext) {
        val ch = ctx.channel()
        if (!ch.isActive) return
        ch.config().isAutoRead = true
        ch.read()
    }

    private fun elapsed(start: Long) = System.currentTimeMillis() - start

    private fun describe(e: Throwable): String = when (e) {
        is SSLException -> if (engine.config.verifyUpstreamTls)
            "Upstream TLS error (disable 'Verify upstream TLS' for self-signed servers): ${e.message}"
        else "Upstream TLS error: ${e.message}"
        is ClosedChannelException -> "Upstream closed the connection"
        is java.net.UnknownHostException -> "DNS lookup failed: ${e.message}"
        is java.net.ConnectException -> "Connection refused/failed: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
    }

    companion object {
        const val CODEC = "httpCodec"
        const val AGGREGATOR = "aggregator"
        const val FRONTEND = "frontend"
    }
}
