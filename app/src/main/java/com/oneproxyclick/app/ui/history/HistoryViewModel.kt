package com.oneproxyclick.app.ui.history

import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.core.common.pro.Pro
import com.oneproxyclick.core.common.tr
import com.oneproxyclick.app.R
import android.app.Application
import android.content.Context
import com.oneproxyclick.app.bridge.KaliTermBridge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oneproxyclick.app.appGraph
import com.oneproxyclick.core.storage.HistoryFilter
import com.oneproxyclick.core.storage.HttpTransactionSummary
import com.oneproxyclick.core.storage.StatusFilter
import com.oneproxyclick.core.storage.TransactionDetail
import com.oneproxyclick.core.storage.export.ExportWriter
import com.oneproxyclick.core.storage.export.LiveJsonlRecorder
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = app.appGraph.history

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    val items: StateFlow<List<HttpTransactionSummary>> = _filter
        .flatMapLatest { repository.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _detail = MutableStateFlow<TransactionDetail?>(null)
    val detail: StateFlow<TransactionDetail?> = _detail.asStateFlow()

    fun setSearch(text: String) = _filter.update { it.copy(search = text) }
    fun setStatus(status: StatusFilter) = _filter.update { it.copy(status = status) }
    fun setMethod(method: String) = _filter.update { it.copy(method = if (it.method == method) "" else method) }
    fun setHideStatic(hide: Boolean) = _filter.update { it.copy(hideStatic = hide) }

    fun load(id: Long?) {
        if (id == null) {
            _detail.value = null
            return
        }
        if (_detail.value?.entity?.id == id) return
        viewModelScope.launch { _detail.value = repository.detail(id) }
    }

    private val graph = app.appGraph

    /** One-shot messages for the Snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    val autoJsonLive: StateFlow<Boolean> = graph.engineSettings.settings
        .map { it.autoJsonLive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAutoJsonLive(enabled: Boolean) {
        if (enabled && !Pro.isUnlocked(ProFeature.LIVE_JSON)) return
        viewModelScope.launch {
            graph.engineSettings.update { it.copy(autoJsonLive = enabled) }
            _messages.emit(
                if (enabled) tr(R.string.msg_auto_on, "${ExportWriter.PUBLIC_DIR}/${LiveJsonlRecorder.fileNameFor(LocalDate.now())}")
                else tr(R.string.msg_auto_off),
            )
        }
    }

    /** "Guardar JSON" for the open transaction. */
    fun exportDetail(detail: TransactionDetail) = runExport {
        val r = graph.exporter.exportSingle(detail)
        tr(R.string.msg_saved, r.saved.displayPath)
    }

    /** "Exportar JSON": every transaction matching the active filters. */
    fun exportFiltered() = runExport {
        val r = graph.exporter.exportFiltered(_filter.value)
        tr(R.string.msg_exported, r.count, r.saved.displayPath)
    }

    /** Exports last_sent.json and hands the curl command to KaliTerm (or the share sheet). */
    fun sendToKaliTerm(context: Context, detail: TransactionDetail) = runExport {
        val outcome = KaliTermBridge.send(context, detail)
        tr(R.string.msg_sent_kaliterm, tr(outcome.delivery.labelRes), outcome.jsonPath)
    }

    /** Snackbar text in the current in-app language. */
    private fun tr(id: Int, vararg args: Any): String = getApplication<Application>().tr(id, *args)

    private fun runExport(block: suspend () -> String) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: Exception) {
                tr(R.string.msg_export_error, e.message ?: e.javaClass.simpleName)
            } finally {
                _exporting.value = false
            }
            _messages.emit(message)
        }
    }

    fun clear() {
        viewModelScope.launch {
            repository.clear()
            _detail.value = null
        }
    }
}
