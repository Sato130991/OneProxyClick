package com.oneproxyclick.core.interceptor.codec

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Display-side body decoding. The proxy always forwards the original bytes; these helpers only
 * produce a readable copy for the UI, history search and the editors.
 */
object ContentCodec {

    /** Hard cap for decoded output to avoid decompression bombs on a phone. */
    private const val MAX_DECODED = 64 * 1024 * 1024

    /**
     * Decodes [body] according to a `Content-Encoding` header value. Multiple codings
     * (`gzip, br`) are undone in reverse order. Returns `null` if decoding fails or the coding is
     * unknown; returns [body] itself for identity / no coding.
     */
    fun decode(body: ByteArray, contentEncoding: String?): ByteArray? {
        val codings = contentEncoding.orEmpty().split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "identity" }
        if (codings.isEmpty() || body.isEmpty()) return body
        var data = body
        for (coding in codings.asReversed()) {
            data = try {
                when (coding) {
                    "gzip", "x-gzip" -> readAll(GZIPInputStream(ByteArrayInputStream(data)))
                    "deflate" -> inflate(data)
                    "br" -> readAll(BrotliInputStream(ByteArrayInputStream(data)))
                    else -> return null
                }
            } catch (_: Exception) {
                return null
            }
        }
        return data
    }

    /** Decodes if possible, otherwise returns the raw bytes. */
    fun decodeOrRaw(body: ByteArray, contentEncoding: String?): ByteArray =
        decode(body, contentEncoding) ?: body

    fun isDecodable(contentEncoding: String?): Boolean {
        val codings = contentEncoding.orEmpty().split(',').map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "identity" }
        return codings.isNotEmpty() && codings.all { it in setOf("gzip", "x-gzip", "deflate", "br") }
    }

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray = readAll(GZIPInputStream(ByteArrayInputStream(data)))

    /** `deflate` is officially zlib-wrapped, but many servers send raw DEFLATE: try both. */
    private fun inflate(data: ByteArray): ByteArray = try {
        readAll(InflaterInputStream(ByteArrayInputStream(data), Inflater(false)))
    } catch (_: Exception) {
        readAll(InflaterInputStream(ByteArrayInputStream(data), Inflater(true)))
    }

    private fun readAll(input: InputStream): ByteArray = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_DECODED) throw IllegalStateException("Decoded body exceeds limit")
        }
        out.toByteArray()
    }

    /** Heuristic: content type says text, or the first KB contains no control bytes. */
    fun isProbablyText(contentType: String?, bytes: ByteArray): Boolean {
        val ct = contentType.orEmpty().lowercase()
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") ||
            ct.contains("javascript") || ct.contains("x-www-form-urlencoded") || ct.contains("html") ||
            ct.contains("graphql") || ct.contains("csv") || ct.contains("yaml")
        ) return true
        if (ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") ||
            ct.contains("octet-stream") || ct.contains("protobuf") || ct.contains("font") ||
            ct.contains("zip") || ct.contains("pdf")
        ) return false
        if (bytes.isEmpty()) return true
        val sample = minOf(bytes.size, 1024)
        var suspicious = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x09 || (b in 0x0E..0x1F)) suspicious++
        }
        return suspicious * 20 < sample
    }

    /** Text view of a body; binary content is summarised instead of mangled. */
    fun bodyAsText(body: ByteArray, contentType: String?, contentEncoding: String?, limit: Int = 512 * 1024): String {
        val decoded = decodeOrRaw(body, contentEncoding)
        if (decoded.isEmpty()) return ""
        if (!isProbablyText(contentType, decoded)) {
            return "<binary body: ${decoded.size} bytes, type=${contentType ?: "unknown"} — see Hex view>"
        }
        val slice = if (decoded.size > limit) decoded.copyOf(limit) else decoded
        val text = String(slice, Charsets.UTF_8)
        return if (decoded.size > limit) "$text\n\n<… truncated, ${decoded.size - limit} more bytes>" else text
    }
}
