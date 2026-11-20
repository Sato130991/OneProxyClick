package com.oneproxyclick.feature.repeater

import com.oneproxyclick.core.interceptor.model.HttpHeader
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Independent HTTP client for the Repeater. It never uses a proxy ([Proxy.NO_PROXY]) and this app
 * is excluded from the VPN, so repeated requests cannot loop back into OneProxyClick.
 */
class RepeaterClient {

    private val strict: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val insecure: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        strict.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    class Result(val response: HttpResponseData, val durationMs: Long)

    suspend fun execute(request: HttpRequestData, ignoreTlsErrors: Boolean): Result = withContext(Dispatchers.IO) {
        val method = request.method.uppercase()
        val contentType = request.header("Content-Type")?.toMediaTypeOrNull()
        val body = when {
            request.body.isNotEmpty() && method !in NO_BODY -> request.body.toRequestBody(contentType)
            method in REQUIRES_BODY -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        val headers = Headers.Builder()
        request.headers.forEach { h ->
            if (h.name.lowercase() !in SKIP_HEADERS) headers.addUnsafeNonAscii(h.name, h.value)
        }
        val okRequest = Request.Builder()
            .url(request.url.toHttpUrl())
            .headers(headers.build())
            .method(method, body)
            .build()

        val client = if (ignoreTlsErrors) insecure else strict
        val start = System.nanoTime()
        client.newCall(okRequest).execute().use { resp ->
            val bytes = resp.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0 || out.size() > MAX_BODY) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            Result(
                HttpResponseData(
                    protocol = resp.protocol.toString().uppercase(),
                    statusCode = resp.code,
                    reason = resp.message,
                    headers = resp.headers.map { HttpHeader(it.first, it.second) },
                    body = bytes,
                ),
                elapsed,
            )
        }
    }

    private companion object {
        const val MAX_BODY = 32 * 1024 * 1024
        val NO_BODY = setOf("GET", "HEAD")
        val REQUIRES_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
        /** Framing headers are computed by OkHttp. */
        val SKIP_HEADERS = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "proxy-connection")
    }
}
