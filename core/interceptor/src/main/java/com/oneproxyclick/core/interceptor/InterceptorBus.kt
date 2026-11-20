package com.oneproxyclick.core.interceptor

import com.oneproxyclick.core.interceptor.codec.RawHttp
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import com.oneproxyclick.core.interceptor.rules.MatchReplaceRule
import com.oneproxyclick.core.interceptor.rules.RuleEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class InterceptPhase { REQUEST, RESPONSE }

sealed interface InterceptedDecision {
    /** Forward; `null` edited message means "send exactly what was held". */
    data class Forward(
        val editedRequest: HttpRequestData? = null,
        val editedResponse: HttpResponseData? = null,
    ) : InterceptedDecision

    data object Drop : InterceptedDecision
}

/** A message held in the intercept queue waiting for a user decision. */
class PendingIntercept internal constructor(
    val id: Long,
    val phase: InterceptPhase,
    val request: HttpRequestData,
    val response: HttpResponseData?,
    val createdAt: Long = System.currentTimeMillis(),
    internal val decision: CompletableDeferred<InterceptedDecision> = CompletableDeferred(),
) {
    /** `METHOD host/path` one-liner for the overlay HUD. */
    val summary: String
        get() = buildString {
            if (phase == InterceptPhase.RESPONSE) append("← ${response?.statusCode} ")
            append(request.method).append(' ').append(request.host).append(request.path)
        }

    /** Raw editable text (decoded body). */
    val originalRaw: String by lazy {
        if (phase == InterceptPhase.RESPONSE && response != null) RawHttp.responseToRaw(response)
        else RawHttp.requestToRaw(request)
    }
}

sealed interface RequestOutcome {
    data class Proceed(val request: HttpRequestData, val edited: Boolean) : RequestOutcome
    data object Dropped : RequestOutcome
}

sealed interface ResponseOutcome {
    data class Proceed(val response: HttpResponseData, val edited: Boolean) : ResponseOutcome
    data object Dropped : ResponseOutcome
}

/**
 * Process-wide reactive intercept pipeline shared by the proxy engine, the Compose UI and the
 * floating overlay.
 *
 * Pass-through (intercept OFF) costs one rule scan per message; with intercept ON each matching
 * message suspends on a [CompletableDeferred] until [forward], [drop] or [forwardAll] is called.
 */
object InterceptorBus {

    private val ids = AtomicLong(0)

    private val _interceptEnabled = MutableStateFlow(false)
    val interceptEnabled: StateFlow<Boolean> = _interceptEnabled.asStateFlow()

    private val _interceptResponses = MutableStateFlow(false)
    val interceptResponses: StateFlow<Boolean> = _interceptResponses.asStateFlow()

    private val _skipStatic = MutableStateFlow(true)
    val skipStaticResources: StateFlow<Boolean> = _skipStatic.asStateFlow()

    /** Optional regex; only hosts matching it are held. Blank = everything. */
    private val _hostFilter = MutableStateFlow("")
    val hostFilter: StateFlow<String> = _hostFilter.asStateFlow()
    @Volatile private var hostRegex: Regex? = null

    private val _queue = MutableStateFlow<List<PendingIntercept>>(emptyList())
    val queue: StateFlow<List<PendingIntercept>> = _queue.asStateFlow()

    private val _rules = MutableStateFlow<List<MatchReplaceRule>>(emptyList())
    val rules: StateFlow<List<MatchReplaceRule>> = _rules.asStateFlow()

    // ---- configuration -----------------------------------------------------------------

    /** Turning intercept off releases the whole queue (Burp behaviour). */
    fun setInterceptEnabled(enabled: Boolean) {
        _interceptEnabled.value = enabled
        if (!enabled) forwardAll()
    }

    fun toggleIntercept() = setInterceptEnabled(!_interceptEnabled.value)

    fun setInterceptResponses(enabled: Boolean) {
        _interceptResponses.value = enabled
    }

    fun setSkipStaticResources(enabled: Boolean) {
        _skipStatic.value = enabled
    }

    fun setHostFilter(filter: String) {
        _hostFilter.value = filter
        hostRegex = filter.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
    }

    fun setRules(rules: List<MatchReplaceRule>) {
        _rules.value = rules
    }

    // ---- user decisions ------------------------------------------------------------------

    /**
     * Releases item [id]. If [editedRaw] differs from the held text it is parsed and sent
     * instead. Returns an error message if the edited text cannot be parsed (item stays held).
     */
    fun forward(id: Long, editedRaw: String? = null): String? {
        val item = _queue.value.firstOrNull { it.id == id } ?: return null
        val decision = if (editedRaw == null || editedRaw == item.originalRaw) {
            InterceptedDecision.Forward()
        } else try {
            when (item.phase) {
                InterceptPhase.REQUEST -> InterceptedDecision.Forward(
                    editedRequest = RawHttp.parseRequest(editedRaw, item.request.scheme, item.request.host, item.request.port),
                )
                InterceptPhase.RESPONSE -> InterceptedDecision.Forward(editedResponse = RawHttp.parseResponse(editedRaw))
            }
        } catch (e: Exception) {
            return e.message ?: "Cannot parse edited message"
        }
        complete(item, decision)
        return null
    }

    fun drop(id: Long) {
        _queue.value.firstOrNull { it.id == id }?.let { complete(it, InterceptedDecision.Drop) }
    }

    fun forwardFirst() = _queue.value.firstOrNull()?.let { forward(it.id) }

    fun dropFirst() = _queue.value.firstOrNull()?.let { drop(it.id) }

    fun forwardAll() {
        val items = _queue.value
        items.forEach { it.decision.complete(InterceptedDecision.Forward()) }
        _queue.update { list -> list.filterNot { item -> items.any { it.id == item.id } } }
    }

    // ---- engine side ---------------------------------------------------------------------

    suspend fun processRequest(request: HttpRequestData): RequestOutcome {
        val ruled = RuleEngine.applyToRequest(request, _rules.value)
        val ruleEdited = ruled !== request
        if (!shouldIntercept(ruled)) return RequestOutcome.Proceed(ruled, ruleEdited)
        val item = PendingIntercept(ids.incrementAndGet(), InterceptPhase.REQUEST, ruled, null)
        return when (val decision = hold(item)) {
            InterceptedDecision.Drop -> RequestOutcome.Dropped
            is InterceptedDecision.Forward -> decision.editedRequest
                ?.let { RequestOutcome.Proceed(it, true) }
                ?: RequestOutcome.Proceed(ruled, ruleEdited)
        }
    }

    suspend fun processResponse(request: HttpRequestData, response: HttpResponseData): ResponseOutcome {
        val ruled = RuleEngine.applyToResponse(response, _rules.value)
        val ruleEdited = ruled !== response
        if (!_interceptResponses.value || !shouldIntercept(request)) return ResponseOutcome.Proceed(ruled, ruleEdited)
        val item = PendingIntercept(ids.incrementAndGet(), InterceptPhase.RESPONSE, request, ruled)
        return when (val decision = hold(item)) {
            InterceptedDecision.Drop -> ResponseOutcome.Dropped
            is InterceptedDecision.Forward -> decision.editedResponse
                ?.let { ResponseOutcome.Proceed(it, true) }
                ?: ResponseOutcome.Proceed(ruled, ruleEdited)
        }
    }

    private suspend fun hold(item: PendingIntercept): InterceptedDecision {
        _queue.update { it + item }
        // Re-check after enqueueing: intercept may have been switched off concurrently.
        if (!_interceptEnabled.value) item.decision.complete(InterceptedDecision.Forward())
        return try {
            item.decision.await()
        } finally {
            _queue.update { list -> list.filterNot { it.id == item.id } }
        }
    }

    private fun complete(item: PendingIntercept, decision: InterceptedDecision) {
        item.decision.complete(decision)
        _queue.update { list -> list.filterNot { it.id == item.id } }
    }

    private fun shouldIntercept(request: HttpRequestData): Boolean {
        if (!_interceptEnabled.value) return false
        if (_skipStatic.value && StaticResources.isStatic(request.path)) return false
        val regex = hostRegex
        return regex == null || regex.containsMatchIn(request.host)
    }
}

/** File extensions treated as static resources (history filter and intercept skip). */
object StaticResources {
    val EXTENSIONS: List<String> = listOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif",
        "css", "woff", "woff2", "ttf", "otf", "eot", "mp4", "webm", "mp3", "m4a",
    )

    fun extensionOf(path: String): String {
        val last = path.substringBefore('?').substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        return if (dot < 0 || dot == last.length - 1) "" else last.substring(dot + 1).lowercase().take(8)
    }

    fun isStatic(path: String): Boolean = extensionOf(path) in EXTENSIONS
}

/** Lightweight live capture signals (e.g. overlay "open last captured"). */
object CaptureEvents {
    private val _lastTransactionId = MutableStateFlow<Long?>(null)
    val lastTransactionId: StateFlow<Long?> = _lastTransactionId.asStateFlow()

    private val _capturedCount = MutableStateFlow(0L)
    val capturedCount: StateFlow<Long> = _capturedCount.asStateFlow()

    fun onStored(id: Long) {
        _lastTransactionId.value = id
        _capturedCount.update { it + 1 }
    }
}
