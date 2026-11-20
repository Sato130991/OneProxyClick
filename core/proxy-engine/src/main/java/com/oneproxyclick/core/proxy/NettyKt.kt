package com.oneproxyclick.core.proxy

import com.oneproxyclick.core.interceptor.model.HttpHeader
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpMessage
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Suspends until a Netty future completes; throws its cause on failure. */
internal suspend fun <V> Future<V>.awaitDone() {
    if (isDone) {
        if (isSuccess) return
        throw cause() ?: IOException("Netty operation failed")
    }
    suspendCancellableCoroutine { cont ->
        addListener(GenericFutureListener<Future<V>> { f ->
            if (f.isSuccess) cont.resume(Unit)
            else cont.resumeWithException(f.cause() ?: IOException("Netty operation failed"))
        })
        cont.invokeOnCancellation { cancel(false) }
    }
}

internal fun Channel.closeOnFlush() {
    if (isActive) writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
}

private val HOP_BY_HOP_REQUEST = setOf(
    "proxy-connection", "proxy-authorization", "proxy-authenticate", "keep-alive",
    "transfer-encoding", "content-length", "expect", "te", "trailer",
)

internal fun FullHttpMessage.headerList(): List<HttpHeader> =
    headers().map { HttpHeader(it.key, it.value) }

internal fun FullHttpMessage.bodyBytes(): ByteArray = ByteBufUtil.getBytes(content())

internal fun isUpgradeRequest(req: HttpRequestData): Boolean =
    req.header("Upgrade") != null && req.header("Connection")?.contains("upgrade", ignoreCase = true) == true

internal fun HttpRequestData.toNetty(): DefaultFullHttpRequest {
    val r = DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.valueOf(method),
        target.ifEmpty { "/" },
        Unpooled.wrappedBuffer(body),
    )
    val h = r.headers()
    headers.forEach { if (it.name.lowercase() !in HOP_BY_HOP_REQUEST) h.add(it.name, it.value) }
    if (!h.contains(HttpHeaderNames.HOST)) h.set(HttpHeaderNames.HOST, authority)
    if (body.isNotEmpty() || method in setOf("POST", "PUT", "PATCH")) {
        h.set(HttpHeaderNames.CONTENT_LENGTH, body.size)
    }
    return r
}

internal fun FullHttpResponse.toData(): HttpResponseData = HttpResponseData(
    protocol = protocolVersion().text(),
    statusCode = status().code(),
    reason = status().reasonPhrase(),
    headers = headerList(),
    body = bodyBytes(),
)

/**
 * Builds the response sent back to the client. Bodies are always delivered with an exact
 * Content-Length (the proxy aggregates messages), except for body-less responses whose framing
 * headers are kept as the server sent them.
 */
internal fun HttpResponseData.toNetty(requestMethod: String, keepAlive: Boolean): DefaultFullHttpResponse {
    val reasonPhrase = reason.ifEmpty { HttpResponseStatus.valueOf(statusCode).reasonPhrase() }
    val r = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus(statusCode, reasonPhrase),
        Unpooled.wrappedBuffer(body),
    )
    val bodyless = requestMethod.equals("HEAD", true) || statusCode in 100..199 || statusCode == 204 || statusCode == 304
    val switching = statusCode == 101
    val h = r.headers()
    headers.forEach {
        val n = it.name.lowercase()
        val drop = n == "transfer-encoding" || n == "keep-alive" ||
            (n == "content-length" && !bodyless) ||
            (n == "connection" && !switching)
        if (!drop) h.add(it.name, it.value)
    }
    if (!bodyless) h.set(HttpHeaderNames.CONTENT_LENGTH, body.size)
    if (!switching) HttpUtil.setKeepAlive(r, keepAlive)
    return r
}

internal fun simpleResponse(status: HttpResponseStatus, text: String, contentType: String = "text/plain; charset=utf-8"): DefaultFullHttpResponse {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes)).apply {
        headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
        headers().set("X-OneProxyClick", "1")
    }
}
