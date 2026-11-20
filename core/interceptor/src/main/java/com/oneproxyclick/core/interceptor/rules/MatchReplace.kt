package com.oneproxyclick.core.interceptor.rules

import android.content.Context
import com.oneproxyclick.core.interceptor.model.HttpHeader
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import com.oneproxyclick.core.interceptor.model.firstValue
import com.oneproxyclick.core.interceptor.model.withHeader
import com.oneproxyclick.core.interceptor.model.without
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class RuleTarget(val label: String) {
    REQUEST_HEADER("Request header"),
    REQUEST_BODY("Request body"),
    RESPONSE_HEADER("Response header"),
    RESPONSE_BODY("Response body"),
}

/**
 * Burp-style Match & Replace rule.
 *
 * Header rules match against each `Name: value` line. An empty [pattern] adds [replacement] as a
 * new header line; a replacement that produces an empty line removes the header.
 * Body rules run on uncompressed textual bodies only.
 */
data class MatchReplaceRule(
    val id: Long,
    val enabled: Boolean = true,
    val target: RuleTarget,
    val pattern: String,
    val replacement: String,
    val comment: String = "",
) {
    @Transient private var compiled: Pattern? = null
    @Transient private var compileFailed = false

    fun regex(): Pattern? {
        if (compiled == null && !compileFailed) {
            try {
                compiled = Pattern.compile(pattern, Pattern.MULTILINE)
            } catch (_: PatternSyntaxException) {
                compileFailed = true
            }
        }
        return compiled
    }

    val isValid: Boolean get() = pattern.isEmpty() || regex() != null
}

object RuleEngine {

    /** Returns the same instance when no rule changed anything. */
    fun applyToRequest(req: HttpRequestData, rules: List<MatchReplaceRule>): HttpRequestData {
        val active = rules.filter { it.enabled && (it.target == RuleTarget.REQUEST_HEADER || it.target == RuleTarget.REQUEST_BODY) }
        if (active.isEmpty()) return req
        var headers = req.headers
        var body = req.body
        var changed = false
        for (rule in active) {
            when (rule.target) {
                RuleTarget.REQUEST_HEADER -> applyHeaderRule(headers, rule)?.let { headers = it; changed = true }
                RuleTarget.REQUEST_BODY -> applyBodyRule(headers, body, rule)?.let {
                    body = it
                    headers = headers.withHeader("Content-Length", it.size.toString())
                    changed = true
                }
                else -> Unit
            }
        }
        return if (changed) req.copy(headers = headers, body = body) else req
    }

    fun applyToResponse(resp: HttpResponseData, rules: List<MatchReplaceRule>): HttpResponseData {
        val active = rules.filter { it.enabled && (it.target == RuleTarget.RESPONSE_HEADER || it.target == RuleTarget.RESPONSE_BODY) }
        if (active.isEmpty()) return resp
        var headers = resp.headers
        var body = resp.body
        var changed = false
        for (rule in active) {
            when (rule.target) {
                RuleTarget.RESPONSE_HEADER -> applyHeaderRule(headers, rule)?.let { headers = it; changed = true }
                RuleTarget.RESPONSE_BODY -> applyBodyRule(headers, body, rule)?.let {
                    body = it
                    headers = headers.without("Transfer-Encoding").withHeader("Content-Length", it.size.toString())
                    changed = true
                }
                else -> Unit
            }
        }
        return if (changed) resp.copy(headers = headers, body = body) else resp
    }

    private fun applyHeaderRule(headers: List<HttpHeader>, rule: MatchReplaceRule): List<HttpHeader>? {
        if (rule.pattern.isEmpty()) {
            val line = rule.replacement
            val idx = line.indexOf(':')
            if (idx <= 0) return null
            return headers + HttpHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
        val regex = rule.regex() ?: return null
        var changed = false
        val result = headers.mapNotNull { h ->
            val line = "${h.name}: ${h.value}"
            val matcher = regex.matcher(line)
            if (!matcher.find()) return@mapNotNull h
            changed = true
            val replaced = safeReplace(regex, line, rule.replacement) ?: return@mapNotNull h
            val idx = replaced.indexOf(':')
            when {
                replaced.isBlank() -> null
                idx <= 0 -> h
                else -> HttpHeader(replaced.substring(0, idx).trim(), replaced.substring(idx + 1).trim())
            }
        }
        return if (changed) result else null
    }

    private fun applyBodyRule(headers: List<HttpHeader>, body: ByteArray, rule: MatchReplaceRule): ByteArray? {
        if (body.isEmpty() || rule.pattern.isEmpty()) return null
        val encoding = headers.firstValue("Content-Encoding")
        if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) return null
        val regex = rule.regex() ?: return null
        val text = String(body, Charsets.UTF_8)
        if (!regex.matcher(text).find()) return null
        val replaced = safeReplace(regex, text, rule.replacement) ?: return null
        return replaced.toByteArray(Charsets.UTF_8)
    }

    private fun safeReplace(regex: Pattern, input: String, replacement: String): String? = try {
        regex.matcher(input).replaceAll(replacement)
    } catch (_: Exception) {
        // invalid group reference etc.: treat replacement literally
        runCatching { regex.matcher(input).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement)) }.getOrNull()
    }
}

/** Persists rules as JSON in SharedPreferences. */
class MatchReplaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("match_replace_rules", Context.MODE_PRIVATE)

    fun load(): List<MatchReplaceRule> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MatchReplaceRule(
                id = o.getLong("id"),
                enabled = o.optBoolean("enabled", true),
                target = RuleTarget.valueOf(o.getString("target")),
                pattern = o.optString("pattern"),
                replacement = o.optString("replacement"),
                comment = o.optString("comment"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(rules: List<MatchReplaceRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("enabled", r.enabled)
                    .put("target", r.target.name)
                    .put("pattern", r.pattern)
                    .put("replacement", r.replacement)
                    .put("comment", r.comment),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "rules"
    }
}
