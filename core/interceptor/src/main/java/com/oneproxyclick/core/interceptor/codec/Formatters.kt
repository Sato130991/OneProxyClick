package com.oneproxyclick.core.interceptor.codec

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Pretty printing used by the history detail and Repeater response viewers. */
object Formatters {

    fun pretty(text: String, contentType: String?): String {
        val ct = contentType.orEmpty().lowercase()
        val trimmed = text.trimStart()
        return when {
            ct.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[") ->
                prettyJson(text) ?: text
            ct.contains("html") || ct.contains("xml") || trimmed.startsWith("<") -> prettyMarkup(text)
            ct.contains("x-www-form-urlencoded") -> prettyForm(text)
            else -> text
        }
    }

    fun prettyJson(text: String): String? = try {
        when (val value = JSONTokener(text.trim()).nextValue()) {
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** Lightweight tag-per-line indenter for HTML/XML (not a validating parser). */
    fun prettyMarkup(text: String): String {
        val tokens = Regex("(<[^>]+>)|([^<]+)").findAll(text).map { it.value }.toList()
        val sb = StringBuilder()
        var depth = 0
        val voidTags = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
            "param", "source", "track", "wbr", "!doctype",
        )
        for (token in tokens) {
            if (token.startsWith("<")) {
                val name = token.removePrefix("</").removePrefix("<").takeWhile { !it.isWhitespace() && it != '>' && it != '/' }.lowercase()
                val closing = token.startsWith("</")
                val selfClosing = token.endsWith("/>") || name in voidTags || token.startsWith("<!") || token.startsWith("<?")
                if (closing) depth = (depth - 1).coerceAtLeast(0)
                sb.append("  ".repeat(depth)).append(token).append('\n')
                if (!closing && !selfClosing) depth++
            } else {
                val content = token.trim()
                if (content.isNotEmpty()) sb.append("  ".repeat(depth)).append(content).append('\n')
            }
        }
        return sb.toString().ifEmpty { text }
    }

    private fun prettyForm(text: String): String = text.split('&').joinToString("\n") { pair ->
        val k = pair.substringBefore('=')
        val v = pair.substringAfter('=', "")
        val dk = runCatching { java.net.URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
        val dv = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        "$dk = $dv"
    }

    /** Classic 16-bytes-per-line hex dump with ASCII gutter. */
    fun hexDump(bytes: ByteArray, limit: Int = 64 * 1024): String {
        if (bytes.isEmpty()) return "<empty>"
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder(n * 4)
        var offset = 0
        while (offset < n) {
            sb.append("%08x  ".format(offset))
            for (i in 0 until 16) {
                if (offset + i < n) sb.append("%02x ".format(bytes[offset + i].toInt() and 0xFF)) else sb.append("   ")
                if (i == 7) sb.append(' ')
            }
            sb.append(" |")
            for (i in 0 until 16) {
                if (offset + i >= n) break
                val c = bytes[offset + i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            offset += 16
        }
        if (bytes.size > limit) sb.append("… ${bytes.size - limit} more bytes not shown\n")
        return sb.toString()
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }
}
