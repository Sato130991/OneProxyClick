package com.oneproxyclick.feature.decoder

import androidx.annotation.StringRes
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class DecoderOp(@StringRes val labelRes: Int) {
    BASE64_DECODE(R.string.dec_op_base64_decode),
    BASE64_ENCODE(R.string.dec_op_base64_encode),
    BASE64URL_DECODE(R.string.dec_op_base64url_decode),
    BASE64URL_ENCODE(R.string.dec_op_base64url_encode),
    URL_DECODE(R.string.dec_op_url_decode),
    URL_ENCODE(R.string.dec_op_url_encode),
    HEX_DECODE(R.string.dec_op_hex_decode),
    HEX_ENCODE(R.string.dec_op_hex_encode),
    HTML_DECODE(R.string.dec_op_html_decode),
    HTML_ENCODE(R.string.dec_op_html_encode),
    GZIP_DECOMPRESS(R.string.dec_op_gzip_decompress),
    GZIP_COMPRESS(R.string.dec_op_gzip_compress),
    JWT_DECODE(R.string.dec_op_jwt_decode),
}

/** Localizable decoder failure (message resolved by the UI). */
class DecoderError(@StringRes val messageRes: Int) : IllegalArgumentException()

/** Pure, offline transformations; each step maps bytes to bytes so steps can be chained. */
object DecoderOps {

    fun apply(op: DecoderOp, input: ByteArray): ByteArray = when (op) {
        DecoderOp.BASE64_DECODE -> Base64.getMimeDecoder().decode(padBase64(text(input)))
        DecoderOp.BASE64_ENCODE -> Base64.getEncoder().encode(input)
        DecoderOp.BASE64URL_DECODE -> Base64.getUrlDecoder().decode(padBase64(text(input).replace('+', '-').replace('/', '_')))
        DecoderOp.BASE64URL_ENCODE -> Base64.getUrlEncoder().withoutPadding().encode(input)
        DecoderOp.URL_DECODE -> URLDecoder.decode(text(input), "UTF-8").toByteArray()
        DecoderOp.URL_ENCODE -> URLEncoder.encode(text(input), "UTF-8").replace("+", "%20").toByteArray()
        DecoderOp.HEX_DECODE -> hexDecode(text(input))
        DecoderOp.HEX_ENCODE -> input.joinToString("") { "%02x".format(it) }.toByteArray()
        DecoderOp.HTML_DECODE -> htmlDecode(text(input)).toByteArray()
        DecoderOp.HTML_ENCODE -> htmlEncode(text(input)).toByteArray()
        DecoderOp.GZIP_DECOMPRESS -> GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
        DecoderOp.GZIP_COMPRESS -> ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(input) } }.toByteArray()
        DecoderOp.JWT_DECODE -> jwtDecode(text(input)).toByteArray()
    }

    /** UTF-8 text when printable, otherwise a hex preview. */
    fun display(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val s = String(bytes, Charsets.UTF_8)
        val bad = s.count { it == '�' || (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') }
        return if (bad * 10 > s.length.coerceAtLeast(1)) {
            "‹binary ${bytes.size} bytes› " + bytes.take(512).joinToString(" ") { "%02x".format(it) } +
                if (bytes.size > 512) " …" else ""
        } else s
    }

    fun isBinary(bytes: ByteArray): Boolean = display(bytes).startsWith("‹binary")

    private fun text(b: ByteArray) = String(b, Charsets.UTF_8).trim()

    private fun padBase64(s: String): String {
        val clean = s.filterNot { it.isWhitespace() }
        val rem = clean.length % 4
        return if (rem == 0) clean else clean + "=".repeat(4 - rem)
    }

    private fun hexDecode(s: String): ByteArray {
        val clean = s.removePrefix("0x").filter { it.isLetterOrDigit() }
        if (clean.length % 2 != 0) throw DecoderError(R.string.dec_err_hex_odd)
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun htmlEncode(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private val NAMED_ENTITIES = mapOf(
        "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "euro" to "€", "laquo" to "«", "raquo" to "»",
    )

    private fun htmlDecode(s: String): String =
        Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED_ENTITIES[body] ?: m.value
            }
        }

    private fun jwtDecode(token: String): String {
        val parts = token.removePrefix("Bearer ").trim().split('.')
        if (parts.size < 2) throw DecoderError(R.string.dec_err_not_jwt)
        fun part(i: Int): String {
            val json = String(Base64.getUrlDecoder().decode(padBase64(parts[i])), Charsets.UTF_8)
            return runCatching { JSONObject(json).toString(2) }.getOrDefault(json)
        }
        val payload = part(1)
        val claims = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(padBase64(parts[1])))) }.getOrNull()
        val times = buildString {
            listOf("iat", "nbf", "exp").forEach { k ->
                claims?.optLong(k, 0L)?.takeIf { it > 0 }?.let {
                    append("$k: ").append(java.util.Date(it * 1000)).append('\n')
                }
            }
        }
        return buildString {
            append("== Header ==\n").append(part(0)).append("\n\n")
            append("== Payload ==\n").append(payload).append("\n\n")
            if (times.isNotEmpty()) append("== Times ==\n").append(times).append('\n')
            append("== Signature ==\n").append(parts.getOrElse(2) { "" }.ifEmpty { "(none / alg=none)" })
        }
    }
}
