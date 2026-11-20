package com.oneproxyclick.core.storage

import android.content.Context
import com.oneproxyclick.core.interceptor.CaptureEvents
import com.oneproxyclick.core.interceptor.StaticResources
import com.oneproxyclick.core.interceptor.model.CapturedExchange
import com.oneproxyclick.core.interceptor.model.ExchangeRecorder
import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import com.oneproxyclick.core.interceptor.model.firstValue
import com.oneproxyclick.core.interceptor.model.parseHeaderBlock
import com.oneproxyclick.core.interceptor.model.toHeaderBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class StatusFilter(val label: String, val code: Int) {
    ALL("All", 0), S2XX("2xx", 2), S3XX("3xx", 3), S4XX("4xx", 4), S5XX("5xx", 5), ERROR("Err", -1)
}

data class HistoryFilter(
    val search: String = "",
    val status: StatusFilter = StatusFilter.ALL,
    /** Empty = all methods. */
    val method: String = "",
    val hideStatic: Boolean = false,
    val limit: Int = 2000,
)

/** Full transaction with bodies resolved (from DB blob or overflow file). */
class TransactionDetail(
    val entity: HttpTransactionEntity,
    val request: HttpRequestData,
    val response: HttpResponseData?,
)

/**
 * Persistent HTTP history. Implements [ExchangeRecorder] so the proxy engine can hand it
 * exchanges without knowing about Room; writes are serialised on a single consumer coroutine so
 * capturing never blocks a Netty event loop.
 */
class HistoryRepository private constructor(context: Context) : ExchangeRecorder {

    private val dao = OneProxyDatabase.get(context).transactions()
    private val bodyDir = File(context.applicationContext.filesDir, "bodies").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<CapturedExchange>(capacity = 4096)

    /** Invoked on the storage IO coroutine after each insert (used by the live JSONL export). */
    @Volatile var onStored: ((Long, CapturedExchange) -> Unit)? = null

    init {
        scope.launch {
            var inserted = 0
            for (exchange in queue) {
                runCatching {
                    val id = dao.insert(toEntity(exchange))
                    CaptureEvents.onStored(id)
                    onStored?.invoke(id, exchange)
                    if (++inserted % 500 == 0) {
                        dao.trimTo(MAX_ROWS)
                        val referenced = dao.bodyFiles().toHashSet()
                        bodyDir.listFiles()?.filter { it.name !in referenced }?.forEach { it.delete() }
                    }
                }
            }
        }
    }

    override fun record(exchange: CapturedExchange) {
        queue.trySend(exchange)
    }

    fun observe(filter: HistoryFilter): Flow<List<HttpTransactionSummary>> =
        dao.observeFiltered(
            search = filter.search.trim(),
            statusClass = filter.status.code,
            method = filter.method,
            hideStatic = if (filter.hideStatic) 1 else 0,
            staticExtensions = StaticResources.EXTENSIONS,
            limit = filter.limit,
        )

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun detail(id: Long): TransactionDetail? = withContext(Dispatchers.IO) {
        val e = dao.getById(id) ?: return@withContext null
        val reqBody = e.requestBody ?: e.requestBodyFile?.let { readBodyFile(it) } ?: ByteArray(0)
        val respBody = e.responseBody ?: e.responseBodyFile?.let { readBodyFile(it) } ?: ByteArray(0)
        val target = if (e.query.isEmpty()) e.path else "${e.path}?${e.query}"
        val request = HttpRequestData(
            method = e.method,
            scheme = e.scheme,
            host = e.host,
            port = e.port,
            target = target,
            protocol = e.protocol,
            headers = parseHeaderBlock(e.requestHeaders),
            body = reqBody,
        )
        val response = if (e.statusCode > 0) HttpResponseData(
            statusCode = e.statusCode,
            reason = e.reason,
            headers = parseHeaderBlock(e.responseHeaders),
            body = respBody,
        ) else null
        TransactionDetail(e, request, response)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        bodyDir.listFiles()?.forEach { it.delete() }
    }

    private fun toEntity(x: CapturedExchange): HttpTransactionEntity {
        val req = x.request
        val resp = x.response
        val (reqInline, reqFile) = splitBody(req.body)
        val (respInline, respFile) = splitBody(resp?.body)
        return HttpTransactionEntity(
            timestamp = x.startedAt,
            method = req.method,
            scheme = req.scheme,
            host = req.host,
            port = req.port,
            path = req.path,
            query = req.query,
            protocol = req.protocol,
            statusCode = resp?.statusCode ?: 0,
            reason = resp?.reason.orEmpty(),
            durationMs = x.durationMs,
            requestSize = req.body.size.toLong(),
            responseSize = resp?.body?.size?.toLong() ?: 0L,
            requestHeaders = req.headers.toHeaderBlock(),
            responseHeaders = resp?.headers?.toHeaderBlock().orEmpty(),
            requestBody = reqInline,
            responseBody = respInline,
            requestBodyFile = reqFile,
            responseBodyFile = respFile,
            requestContentType = req.headers.firstValue("Content-Type"),
            responseContentType = resp?.headers?.firstValue("Content-Type"),
            extension = StaticResources.extensionOf(req.path),
            error = x.error,
            edited = x.edited,
        )
    }

    /** Small bodies inline; large ones to a file (truncated at [MAX_BODY_FILE]). */
    private fun splitBody(body: ByteArray?): Pair<ByteArray?, String?> {
        if (body == null || body.isEmpty()) return null to null
        if (body.size <= INLINE_BODY_LIMIT) return body to null
        val name = UUID.randomUUID().toString() + ".bin"
        val bytes = if (body.size > MAX_BODY_FILE) body.copyOf(MAX_BODY_FILE) else body
        return runCatching {
            File(bodyDir, name).writeBytes(bytes)
            null to name
        }.getOrElse { body.copyOf(INLINE_BODY_LIMIT) to null }
    }

    private fun readBodyFile(name: String): ByteArray? =
        runCatching { File(bodyDir, name).readBytes() }.getOrNull()

    companion object {
        /** Keeps each row well below the 2 MB SQLite CursorWindow. */
        const val INLINE_BODY_LIMIT = 512 * 1024
        /** Payloads above this (e.g. massive binaries) are truncated in the history copy. */
        const val MAX_BODY_FILE = 32 * 1024 * 1024
        const val MAX_ROWS = 20_000

        @Volatile private var instance: HistoryRepository? = null

        fun get(context: Context): HistoryRepository =
            instance ?: synchronized(this) {
                instance ?: HistoryRepository(context.applicationContext).also { instance = it }
            }
    }
}
