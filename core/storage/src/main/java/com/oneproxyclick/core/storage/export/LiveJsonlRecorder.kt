package com.oneproxyclick.core.storage.export

import com.oneproxyclick.core.interceptor.model.CapturedExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.time.LocalDate

/**
 * "Auto-JSON GET/POST". For every completed GET/POST it writes one compact JSON object per line to:
 *  - `Download/OneProxyClick_Exports/live_capture_YYYY-MM-DD.jsonl` (daily archive)
 *  - `Download/OneProxyClick_Exports/live_stream.jsonl` (continuous stream for `tail -f | jq`)
 *
 * The stream file is kept open and each line is written + flushed immediately, so terminal readers
 * see it without buffering delay. [offer] only enqueues (non-blocking, never on a Netty event loop);
 * one IO coroutine serialises all writes so lines never interleave. If the disk can't keep up, the
 * oldest pending entries are dropped instead of growing memory without bound.
 */
class LiveJsonlRecorder(private val writer: ExportWriter) {

    @Volatile var enabled: Boolean = false

    private val _written = MutableStateFlow(0L)
    val written: StateFlow<Long> = _written.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val queue = Channel<Pair<Long, CapturedExchange>>(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Only touched by the consumer coroutine. */
    private var stream: OutputStream? = null
    private var streamOpenedAt = 0L

    init {
        scope.launch {
            for ((id, exchange) in queue) {
                try {
                    val line = TransactionJson.from(id, exchange).toString()
                    writer.appendLine(fileNameFor(LocalDate.now()), line)
                    writeStream(line)
                    _written.value += 1
                    _lastError.value = null
                } catch (e: Exception) {
                    _lastError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    /** Called after a transaction is stored (id = history row id). */
    fun offer(id: Long, exchange: CapturedExchange) {
        if (!enabled) return
        if (exchange.response == null) return // only completed requests
        val method = exchange.request.method.uppercase()
        if (method != "GET" && method != "POST") return
        queue.trySend(id to exchange)
    }

    private fun writeStream(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        // A deleted file stays writable through an open fd (writes would silently vanish), so
        // re-resolve the file periodically; findOrCreate recreates it if the user removed it.
        val now = System.currentTimeMillis()
        if (stream != null && now - streamOpenedAt > REOPEN_INTERVAL_MS) {
            runCatching { stream?.close() }
            stream = null
        }
        try {
            val out = stream ?: writer.openAppendStream(STREAM_FILE).also {
                stream = it
                streamOpenedAt = now
            }
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            // File deleted/rotated by the user: reopen once.
            runCatching { stream?.close() }
            stream = writer.openAppendStream(STREAM_FILE).also {
                it.write(bytes)
                it.flush()
            }
            streamOpenedAt = now
        }
    }

    companion object {
        const val STREAM_FILE = "live_stream.jsonl"
        private const val REOPEN_INTERVAL_MS = 15_000L
        fun fileNameFor(date: LocalDate) = "live_capture_$date.jsonl"
    }
}
