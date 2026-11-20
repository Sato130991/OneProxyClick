package com.oneproxyclick.core.interceptor.codec

import android.util.Base64
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.firstValue

/** Builds a bash-ready `curl` command that reproduces a captured request. */
object CurlCommand {

    private val SKIP = setOf(
        "content-length", "transfer-encoding", "connection", "keep-alive",
        "proxy-connection", "proxy-authorization", "te", "expect",
    )

    fun build(req: HttpRequestData): String {
        val parts = mutableListOf("curl", "-sS", "-i")
        if (req.method != "GET" || req.body.isNotEmpty()) parts += listOf("-X", req.method)
        parts += quote(req.url)
        val acceptsCompressed = req.header("Accept-Encoding")?.let { ae ->
            listOf("gzip", "deflate", "br").any { ae.contains(it, ignoreCase = true) }
        } == true
        if (acceptsCompressed) parts += "--compressed"
        req.headers.forEach { h ->
            val n = h.name.lowercase()
            if (n in SKIP) return@forEach
            if (n == "accept-encoding" && acceptsCompressed) return@forEach
            parts += listOf("-H", quote("${h.name}: ${h.value}"))
        }
        if (req.body.isNotEmpty()) {
            val contentType = req.headers.firstValue("Content-Type")
            val encoded = req.headers.firstValue("Content-Encoding")
            val textual = encoded.isNullOrBlank() && ContentCodec.isProbablyText(contentType, req.body)
            if (textual) {
                parts += listOf("--data-raw", quote(String(req.body, Charsets.UTF_8)))
            } else {
                // Exact binary body via process substitution (bash).
                val b64 = Base64.encodeToString(req.body, Base64.NO_WRAP)
                parts += listOf("--data-binary", "@<(printf '%s' '$b64' | base64 -d)")
            }
        }
        return parts.joinToString(" ")
    }

    /** POSIX single-quote escaping. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
