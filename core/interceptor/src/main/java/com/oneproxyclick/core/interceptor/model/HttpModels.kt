package com.oneproxyclick.core.interceptor.model

data class HttpHeader(val name: String, val value: String) {
    override fun toString(): String = "$name: $value"
}

fun List<HttpHeader>.firstValue(name: String): String? =
    firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

fun List<HttpHeader>.without(vararg names: String): List<HttpHeader> =
    filterNot { h -> names.any { it.equals(h.name, ignoreCase = true) } }

/** Sets [name] to a single [value], keeping the position of its first occurrence. */
fun List<HttpHeader>.withHeader(name: String, value: String): List<HttpHeader> {
    val index = indexOfFirst { it.name.equals(name, ignoreCase = true) }
    if (index < 0) return this + HttpHeader(name, value)
    val result = ArrayList<HttpHeader>(size)
    forEachIndexed { i, h ->
        when {
            i == index -> result += HttpHeader(h.name, value)
            h.name.equals(name, ignoreCase = true) -> Unit
            else -> result += h
        }
    }
    return result
}

/** Serialises headers as `Name: value` lines (used by storage and the UI). */
fun List<HttpHeader>.toHeaderBlock(): String = joinToString("\n") { "${it.name}: ${it.value}" }

fun parseHeaderBlock(block: String?): List<HttpHeader> =
    block.orEmpty().lineSequence().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else HttpHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
    }.toList()

/**
 * An HTTP/1.x request as seen by the proxy. [body] holds the bytes exactly as they travel on the
 * wire (still compressed if `Content-Encoding` is set).
 */
data class HttpRequestData(
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    /** Origin-form request target: path plus optional `?query`. */
    val target: String,
    val protocol: String = "HTTP/1.1",
    val headers: List<HttpHeader>,
    val body: ByteArray = ByteArray(0),
) {
    val path: String get() = target.substringBefore('?').ifEmpty { "/" }
    val query: String get() = target.substringAfter('?', "")
    val isTls: Boolean get() = scheme.equals("https", true) || scheme.equals("wss", true)

    val authority: String
        get() {
            val defaultPort = if (isTls) 443 else 80
            val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return if (port == defaultPort) h else "$h:$port"
        }

    val url: String get() = "$scheme://$authority$target"

    fun header(name: String): String? = headers.firstValue(name)

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class HttpResponseData(
    val protocol: String = "HTTP/1.1",
    val statusCode: Int,
    val reason: String,
    val headers: List<HttpHeader>,
    val body: ByteArray = ByteArray(0),
) {
    fun header(name: String): String? = headers.firstValue(name)

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** One complete request/response round trip captured by the proxy engine. */
data class CapturedExchange(
    val startedAt: Long,
    val durationMs: Long,
    val request: HttpRequestData,
    val response: HttpResponseData?,
    val error: String? = null,
    /** True if the request or the response was modified (intercept edit or Match & Replace). */
    val edited: Boolean = false,
)

/** Sink for captured traffic (implemented by the storage layer, wired in :app). */
fun interface ExchangeRecorder {
    fun record(exchange: CapturedExchange)
}
