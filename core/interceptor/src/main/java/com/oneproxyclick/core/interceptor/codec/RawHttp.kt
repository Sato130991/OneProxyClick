package com.oneproxyclick.core.interceptor.codec

import com.oneproxyclick.core.interceptor.model.HttpHeader
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import com.oneproxyclick.core.interceptor.model.firstValue
import com.oneproxyclick.core.interceptor.model.without

/**
 * Conversion between structured messages and the raw text shown in the Intercept and Repeater
 * editors.
 *
 * Editable text uses the *decoded* body (compression removed, `Content-Encoding` dropped) so the
 * user edits readable content; when the user forwards without touching the text the caller must
 * keep the original message instead of re-parsing it.
 */
object RawHttp {

    class ParseException(message: String) : IllegalArgumentException(message)

    fun requestToRaw(req: HttpRequestData, decodeBody: Boolean = true): String {
        val (headers, body) = editableParts(req.headers, req.body, decodeBody)
        return buildString {
            append(req.method).append(' ').append(req.target).append(' ').append(req.protocol).append('\n')
            headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
            append('\n')
            append(bodyText(body))
        }
    }

    fun responseToRaw(resp: HttpResponseData, decodeBody: Boolean = true): String {
        val (headers, body) = editableParts(resp.headers, resp.body, decodeBody)
        return buildString {
            append(resp.protocol).append(' ').append(resp.statusCode)
            if (resp.reason.isNotEmpty()) append(' ').append(resp.reason)
            append('\n')
            headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
            append('\n')
            append(bodyText(body))
        }
    }

    /**
     * Parses an edited request. The connection target (scheme/host/port) is taken from
     * [defaultScheme]/[defaultHost]/[defaultPort] unless the request line uses absolute-form, or
     * [hostFromHeader] is set and a `Host` header exists (Repeater behaviour).
     */
    fun parseRequest(
        raw: String,
        defaultScheme: String,
        defaultHost: String,
        defaultPort: Int,
        hostFromHeader: Boolean = false,
    ): HttpRequestData {
        val (head, body) = splitHeadBody(raw)
        val lines = head.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw ParseException("Empty request")
        val parts = lines.first().trim().split(Regex("\\s+"))
        if (parts.size < 2) throw ParseException("Invalid request line: ${lines.first()}")
        val method = parts[0].uppercase()
        var target = parts[1]
        val protocol = parts.getOrNull(2) ?: "HTTP/1.1"
        val headers = parseHeaders(lines.drop(1))

        var scheme = defaultScheme
        var host = defaultHost
        var port = defaultPort
        val lower = target.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            scheme = lower.substringBefore("://")
            val rest = target.substringAfter("://")
            val authority = rest.substringBefore('/').substringBefore('?')
            target = rest.substring(authority.length).ifEmpty { "/" }
            if (target.startsWith("?")) target = "/$target"
            val (h, p) = splitAuthority(authority, if (scheme == "https") 443 else 80)
            host = h
            port = p
        } else if (hostFromHeader) {
            headers.firstValue("Host")?.let { hostHeader ->
                val (h, p) = splitAuthority(hostHeader, if (scheme == "https") 443 else 80)
                host = h
                port = p
            }
        }
        if (host.isBlank()) throw ParseException("No target host")
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        return HttpRequestData(
            method = method,
            scheme = scheme,
            host = host,
            port = port,
            target = target,
            protocol = protocol,
            headers = fixFraming(headers, bodyBytes, method),
            body = bodyBytes,
        )
    }

    fun parseResponse(raw: String): HttpResponseData {
        val (head, body) = splitHeadBody(raw)
        val lines = head.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw ParseException("Empty response")
        val statusLine = lines.first().trim()
        val parts = statusLine.split(Regex("\\s+"), limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: throw ParseException("Invalid status line: $statusLine")
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        return HttpResponseData(
            protocol = parts[0],
            statusCode = code,
            reason = parts.getOrNull(2).orEmpty(),
            headers = fixFraming(parseHeaders(lines.drop(1)), bodyBytes, null),
            body = bodyBytes,
        )
    }

    fun splitAuthority(authority: String, defaultPort: Int): Pair<String, Int> {
        val a = authority.trim()
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            val host = a.substring(1, if (end > 0) end else a.length)
            val port = if (end > 0 && a.length > end + 2 && a[end + 1] == ':') a.substring(end + 2).toIntOrNull() else null
            return host to (port ?: defaultPort)
        }
        val colon = a.lastIndexOf(':')
        if (colon > 0 && a.indexOf(':') == colon) {
            val port = a.substring(colon + 1).toIntOrNull()
            if (port != null) return a.substring(0, colon) to port
        }
        return a to defaultPort
    }

    // ------------------------------------------------------------------------------------

    private fun editableParts(headers: List<HttpHeader>, body: ByteArray, decode: Boolean): Pair<List<HttpHeader>, ByteArray> {
        val encoding = headers.firstValue("Content-Encoding")
        if (!decode || !ContentCodec.isDecodable(encoding)) return headers to body
        val decoded = ContentCodec.decode(body, encoding) ?: return headers to body
        val newHeaders = headers.without("Content-Encoding").map {
            if (it.name.equals("Content-Length", true)) HttpHeader(it.name, decoded.size.toString()) else it
        }
        return newHeaders to decoded
    }

    private fun bodyText(body: ByteArray): String = String(body, Charsets.UTF_8)

    private fun splitHeadBody(raw: String): Pair<String, String> {
        val normalized = raw.replace("\r\n", "\n")
        val idx = normalized.indexOf("\n\n")
        return if (idx < 0) normalized to "" else normalized.substring(0, idx) to normalized.substring(idx + 2)
    }

    private fun parseHeaders(lines: List<String>): List<HttpHeader> = lines.mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else HttpHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
    }

    /** Body is always sent fully buffered: drop chunked framing and recompute Content-Length. */
    private fun fixFraming(headers: List<HttpHeader>, body: ByteArray, method: String?): List<HttpHeader> {
        val hadLength = headers.any { it.name.equals("Content-Length", true) }
        val cleaned = headers.without("Transfer-Encoding", "Content-Length")
        val needsLength = body.isNotEmpty() || hadLength ||
            (method != null && method in setOf("POST", "PUT", "PATCH"))
        return if (needsLength) cleaned + HttpHeader("Content-Length", body.size.toString()) else cleaned
    }
}
