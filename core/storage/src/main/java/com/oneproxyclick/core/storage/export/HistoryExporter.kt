package com.oneproxyclick.core.storage.export

import com.oneproxyclick.core.interceptor.model.HttpRequestData
import com.oneproxyclick.core.interceptor.model.HttpResponseData
import com.oneproxyclick.core.storage.HistoryFilter
import com.oneproxyclick.core.storage.HistoryRepository
import com.oneproxyclick.core.storage.TransactionDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One-shot exports: a single transaction or the currently filtered history. */
class HistoryExporter(
    private val repository: HistoryRepository,
    private val writer: ExportWriter,
) {
    class Result(val saved: ExportWriter.Saved, val count: Int)

    /** `<METHOD>_<HOST>_<TIMESTAMP>.json`, pretty-printed. */
    suspend fun exportSingle(detail: TransactionDetail): Result = withContext(Dispatchers.IO) {
        val e = detail.entity
        val name = "${safe(e.method)}_${safe(e.host)}_${fileStamp(e.timestamp)}.json"
        val json = TransactionJson.from(detail).toString(2)
        val saved = writer.writeNew(name, ExportWriter.MIME_JSON) { it.write(json.toByteArray(Charsets.UTF_8)) }
        Result(saved, 1)
    }

    /**
     * Repeater "Save JSON": same structured format as the Proxy export, plus `"source":"repeater"`.
     * File: `REPEATER_<METHOD>_<HOST>_<TIMESTAMP>.json`.
     */
    suspend fun exportRepeater(
        request: HttpRequestData,
        response: HttpResponseData?,
        startedAt: Long,
        durationMs: Long,
        error: String?,
    ): Result = withContext(Dispatchers.IO) {
        val name = "REPEATER_${safe(request.method)}_${safe(request.host)}_${fileStamp(startedAt)}.json"
        val json = TransactionJson.build(0, startedAt, durationMs, request, response, error, edited = false)
            .put("source", "repeater")
            .toString(2)
        val saved = writer.writeNew(name, ExportWriter.MIME_JSON) { it.write(json.toByteArray(Charsets.UTF_8)) }
        Result(saved, 1)
    }

    /** Overwrites a fixed-name file (e.g. `last_sent.json` for "Send to KaliTerm"). */
    suspend fun exportReplacing(detail: TransactionDetail, fileName: String): Result = withContext(Dispatchers.IO) {
        val json = TransactionJson.from(detail).toString(2)
        val saved = writer.writeOrReplace(fileName, ExportWriter.MIME_JSON) { it.write(json.toByteArray(Charsets.UTF_8)) }
        Result(saved, 1)
    }

    /**
     * Exports every transaction matching [filter] (the rows visible in the Proxy tab) into
     * `history_<TIMESTAMP>.json`. Rows are streamed one by one so large histories don't need to
     * fit in memory at once.
     */
    suspend fun exportFiltered(filter: HistoryFilter): Result = withContext(Dispatchers.IO) {
        val rows = repository.observe(filter).first()
        val now = System.currentTimeMillis()
        val header = JSONObject().apply {
            put("exported_at", TransactionJson.isoTimestamp(now))
            put("app", "OneProxyClick")
            put(
                "filter",
                JSONObject().apply {
                    put("search", filter.search)
                    put("status", filter.status.label)
                    put("method", filter.method.ifEmpty { "ALL" })
                    put("hide_static", filter.hideStatic)
                },
            )
            put("count", rows.size)
        }.toString()
        var written = 0
        val saved = writer.writeNew("history_${fileStamp(now)}.json", ExportWriter.MIME_JSON) { out ->
            val w = out.bufferedWriter(Charsets.UTF_8)
            // {header..., "transactions": [ ... ]}
            w.write(header.removeSuffix("}"))
            w.write(",\"transactions\":[\n")
            // History list is newest-first; export chronologically.
            for (row in rows.asReversed()) {
                val detail = runBlocking { repository.detail(row.id) } ?: continue
                if (written > 0) w.write(",\n")
                w.write(TransactionJson.from(detail).toString())
                written++
            }
            w.write("\n]}\n")
            w.flush()
        }
        Result(saved, written)
    }

    companion object {
        private val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

        fun fileStamp(epochMs: Long): String = synchronized(STAMP) { STAMP.format(Date(epochMs)) }

        fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "unknown" }
    }
}
