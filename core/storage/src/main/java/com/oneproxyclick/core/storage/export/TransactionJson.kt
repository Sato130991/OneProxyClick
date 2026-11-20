package com.oneproxyclick.core.storage.export

import android.util.Base64
import com.oneproxyclick.core.interceptor.codec.ContentCodec
import com.oneproxyclick.core.interceptor.model.CapturedExchange
import com.oneproxyclick.core.interceptor.model.HttpHeader
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import com.oneproxyclick.core.interceptor.model.firstValue
import com.oneproxyclick.core.storage.TransactionDetail
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Structured JSON representation of one HTTP transaction (org.json, available on every Android).
 *
 * ```
 * { id, timestamp_iso, duration_ms, method, scheme, host, port, path, query, full_url, edited, error,
 *   request:  { headers:{…}, content_type, content_encoding, body_size, body_text, body_base64, body_json? },
 *   response: { status_code, reason, headers:{…}, content_type, content_encoding, body_size,
 *               body_text, body_base64, body_json? } | null }
 * ```
 * - `headers`: object; a header that appears several times (e.g. Set-Cookie) becomes an array.
 * - `body_base64`: exact wire bytes (may be compressed).
 * - `body_text`: decompressed UTF-8 text, or null for binary payloads.
 * - `body_json`: present only when the decoded body is valid JSON (object or array).
 */
object TransactionJson {

    fun from(detail: TransactionDetail): JSONObject = build(
        id = detail.entity.id,
        startedAt = detail.entity.timestamp,
        durationMs = detail.entity.durationMs,
        request = detail.request,
        response = detail.response,
        error = detail.entity.error,
        edited = detail.entity.edited,
    )

    fun from(id: Long, exchange: CapturedExchange): JSONObject = build(
        id = id,
        startedAt = exchange.startedAt,
        durationMs = exchange.durationMs,
        request = exchange.request,
        response = exchange.response,
        error = exchange.error,
        edited = exchange.edited,
    )

    fun build(
        id: Long,
        startedAt: Long,
        durationMs: Long,
        request: HttpRequestData,
        response: HttpResponseData?,
        error: String?,
        edited: Boolean,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp_iso", isoTimestamp(startedAt))
        put("timestamp_ms", startedAt)
        put("duration_ms", durationMs)
        put("method", request.method)
        put("scheme", request.scheme)
        put("host", request.host)
        put("port", request.port)
        put("path", request.path)
        put("query", request.query)
        put("full_url", request.url)
        put("protocol", request.protocol)
        put("edited", edited)
        put("error", error ?: JSONObject.NULL)
        put("request", messageJson(request.headers, request.body).apply {
            put("method", request.method)
        })
        put(
            "response",
            response?.let {
                messageJson(it.headers, it.body).apply {
                    put("status_code", it.statusCode)
                    put("reason", it.reason)
                }
            } ?: JSONObject.NULL,
        )
    }

    fun isoTimestamp(epochMs: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).toString()

    private fun messageJson(headers: List<HttpHeader>, body: ByteArray): JSONObject {
        val contentType = headers.firstValue("Content-Type")
        val encoding = headers.firstValue("Content-Encoding")
        val decoded = ContentCodec.decodeOrRaw(body, encoding)
        val text = if (decoded.isNotEmpty() && ContentCodec.isProbablyText(contentType, decoded)) {
            String(decoded, Charsets.UTF_8)
        } else if (decoded.isEmpty()) "" else null

        return JSONObject().apply {
            put("headers", headersJson(headers))
            put("content_type", contentType ?: JSONObject.NULL)
            put("content_encoding", encoding ?: JSONObject.NULL)
            put("body_size", body.size)
            put("body_text", text ?: JSONObject.NULL)
            put("body_base64", Base64.encodeToString(body, Base64.NO_WRAP))
            text?.let { parseJson(it) }?.let { put("body_json", it) }
        }
    }

    private fun headersJson(headers: List<HttpHeader>): JSONObject {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        headers.forEach { grouped.getOrPut(it.name) { mutableListOf() } += it.value }
        return JSONObject().apply {
            grouped.forEach { (name, values) ->
                put(name, if (values.size == 1) values[0] else JSONArray(values))
            }
        }
    }

    /** Returns a JSONObject / JSONArray when [text] is valid JSON, otherwise null. */
    private fun parseJson(text: String): Any? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return null
        return try {
            val tokener = JSONTokener(trimmed)
            val value = tokener.nextValue()
            if (tokener.more()) null // trailing garbage -> not a single JSON document
            else value.takeIf { it is JSONObject || it is JSONArray }
        } catch (_: Exception) {
            null
        }
    }
}
