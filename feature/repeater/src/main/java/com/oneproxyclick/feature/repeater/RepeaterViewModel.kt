package com.oneproxyclick.feature.repeater

import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.core.common.pro.Pro
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneproxyclick.core.interceptor.codec.RawHttp
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Initial content of a Repeater tab ("Send to Repeater"). */
data class RepeaterSeed(val raw: String, val https: Boolean, val host: String, val port: Int) {
    companion object {
        fun from(request: HttpRequestData) = RepeaterSeed(
            raw = RawHttp.requestToRaw(request),
            https = request.isTls,
            host = request.host,
            port = request.port,
        )
    }
}

data class RepeaterEntry(
    val requestRaw: String,
    val url: String,
    val response: HttpResponseData?,
    val durationMs: Long,
    val error: String?,
    val sentAt: Long = System.currentTimeMillis(),
    /** Parsed request actually sent (used by "Save JSON"). */
    val request: HttpRequestData? = null,
)

data class RepeaterTab(
    val id: Int,
    val title: String,
    val raw: String,
    val https: Boolean,
    val host: String,
    val port: String,
    val sending: Boolean = false,
    val history: List<RepeaterEntry> = emptyList(),
    val historyIndex: Int = -1,
    val parseError: String? = null,
) {
    val current: RepeaterEntry? get() = history.getOrNull(historyIndex)
}

data class RepeaterUiState(val tabs: List<RepeaterTab>, val selectedIndex: Int) {
    /** Always valid: the list is never empty and the index is clamped. */
    val selectedTab: RepeaterTab get() = tabs[selectedIndex.coerceIn(0, tabs.lastIndex)]
    val canClose: Boolean get() = tabs.size > 1
}

class RepeaterViewModel : ViewModel() {

    private val client = RepeaterClient()
    private var nextId = 1

    /**
     * Tabs and selection live in ONE state object so the UI can never observe a selected index
     * that doesn't belong to the tab list it is rendering (the cause of the old "+" crash).
     */
    private val _state = MutableStateFlow(RepeaterUiState(listOf(newTab(null)), 0))
    val state: StateFlow<RepeaterUiState> = _state.asStateFlow()

    private val _ignoreTlsErrors = MutableStateFlow(true)
    val ignoreTlsErrors: StateFlow<Boolean> = _ignoreTlsErrors.asStateFlow()

    fun setIgnoreTlsErrors(value: Boolean) {
        _ignoreTlsErrors.value = value
    }

    /**
     * Opens a new tab ("Tab N") and focuses it. A seed replaces an untouched default tab.
     *
     * Free tier (no MULTI_REPEATER_TABS): only one tab exists. A seed ("Send to Repeater") then
     * replaces the active tab's request; an empty "+" returns false so the UI can show the upsell.
     */
    fun addTab(seed: RepeaterSeed? = null): Boolean {
        if (!Pro.isUnlocked(ProFeature.MULTI_REPEATER_TABS) && _state.value.tabs.size >= FREE_TAB_LIMIT) {
            if (seed == null) return false
            _state.update { s ->
                val i = s.selectedIndex.coerceIn(0, s.tabs.lastIndex)
                val replaced = s.tabs[i].copy(
                    raw = seed.raw, https = seed.https, host = seed.host, port = seed.port.toString(),
                    sending = false, history = emptyList(), historyIndex = -1, parseError = null,
                )
                s.copy(tabs = s.tabs.toMutableList().also { it[i] = replaced }, selectedIndex = i)
            }
            return true
        }
        _state.update { s ->
            val tab = newTab(seed)
            val pristine = s.tabs.singleOrNull()?.takeIf { it.history.isEmpty() && it.raw == DEFAULT_RAW }
            if (pristine != null && seed != null) {
                RepeaterUiState(listOf(tab), 0)
            } else {
                val tabs = s.tabs + tab
                RepeaterUiState(tabs, tabs.lastIndex)
            }
        }
        return true
    }

    /**
     * Closes only tab [id]. The last remaining tab is never removed (the UI disables the button);
     * if called anyway it is reset to a blank request instead of leaving an empty list.
     */
    fun closeTab(id: Int) {
        _state.update { s ->
            val index = s.tabs.indexOfFirst { it.id == id }
            when {
                index < 0 -> s
                s.tabs.size == 1 -> {
                    val blank = s.tabs[0].copy(
                        raw = DEFAULT_RAW, https = true, host = "example.com", port = "443",
                        sending = false, history = emptyList(), historyIndex = -1, parseError = null,
                    )
                    RepeaterUiState(listOf(blank), 0)
                }
                else -> {
                    val tabs = s.tabs.toMutableList().apply { removeAt(index) }
                    val selected = when {
                        s.selectedIndex > index -> s.selectedIndex - 1
                        s.selectedIndex == index -> index.coerceAtMost(tabs.lastIndex)
                        else -> s.selectedIndex
                    }
                    RepeaterUiState(tabs, selected.coerceIn(0, tabs.lastIndex))
                }
            }
        }
    }

    fun select(id: Int) {
        _state.update { s ->
            val index = s.tabs.indexOfFirst { it.id == id }
            if (index < 0) s else s.copy(selectedIndex = index)
        }
    }

    fun updateRaw(id: Int, raw: String) = mutate(id) { it.copy(raw = raw, parseError = null) }
    fun updateHost(id: Int, host: String) = mutate(id) { it.copy(host = host.trim()) }
    fun updatePort(id: Int, port: String) = mutate(id) { it.copy(port = port.filter(Char::isDigit).take(5)) }
    fun updateHttps(id: Int, https: Boolean) = mutate(id) {
        val p = it.port.toIntOrNull()
        val newPort = when {
            https && (p == null || p == 80) -> "443"
            !https && (p == null || p == 443) -> "80"
            else -> it.port
        }
        it.copy(https = https, port = newPort)
    }

    fun historyBack(id: Int) = mutate(id) { tab ->
        if (tab.historyIndex <= 0) tab else tab.copy(historyIndex = tab.historyIndex - 1, raw = tab.history[tab.historyIndex - 1].requestRaw)
    }

    fun historyForward(id: Int) = mutate(id) { tab ->
        if (tab.historyIndex >= tab.history.lastIndex) tab
        else tab.copy(historyIndex = tab.historyIndex + 1, raw = tab.history[tab.historyIndex + 1].requestRaw)
    }

    fun send(id: Int) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        if (tab.sending) return
        val request = try {
            val port = tab.port.toIntOrNull() ?: if (tab.https) 443 else 80
            RawHttp.parseRequest(tab.raw, if (tab.https) "https" else "http", tab.host, port)
        } catch (e: Exception) {
            mutate(id) { it.copy(parseError = e.message.orEmpty()) } // "" -> generic localized text
            return
        }
        mutate(id) { it.copy(sending = true, parseError = null) }
        viewModelScope.launch {
            val sentAt = System.currentTimeMillis()
            val entry = try {
                val result = client.execute(request, _ignoreTlsErrors.value)
                RepeaterEntry(tab.raw, request.url, result.response, result.durationMs, null, sentAt, request)
            } catch (e: Exception) {
                RepeaterEntry(tab.raw, request.url, null, 0, "${e.javaClass.simpleName}: ${e.message}", sentAt, request)
            }
            mutate(id) {
                val history = (it.history + entry).takeLast(MAX_HISTORY)
                it.copy(sending = false, history = history, historyIndex = history.lastIndex)
            }
        }
    }

    private fun mutate(id: Int, block: (RepeaterTab) -> RepeaterTab) {
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.id == id) block(it) else it }) }
    }

    private fun newTab(seed: RepeaterSeed?): RepeaterTab {
        val id = nextId++
        return RepeaterTab(
            id = id,
            title = "Tab $id",
            raw = seed?.raw ?: DEFAULT_RAW,
            https = seed?.https ?: true,
            host = seed?.host ?: "example.com",
            port = (seed?.port ?: 443).toString(),
        )
    }

    companion object {
        private const val MAX_HISTORY = 100
        const val FREE_TAB_LIMIT = 1
        private const val DEFAULT_RAW = "GET / HTTP/1.1\nHost: example.com\nUser-Agent: OneProxyClick-Repeater\nAccept: */*\n\n"
    }
}
