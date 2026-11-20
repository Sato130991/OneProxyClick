package com.oneproxyclick.core.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured HTTP exchange. Bodies are stored exactly as received (possibly compressed).
 * Bodies larger than [HistoryRepository.INLINE_BODY_LIMIT] are written to a file under
 * `filesDir/bodies/` and only the file name is kept here (SQLite CursorWindow is 2 MB per row).
 */
@Entity(
    tableName = "http_transactions",
    indices = [Index("timestamp"), Index("host"), Index("statusCode"), Index("method")],
)
data class HttpTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: String,
    val protocol: String,
    /** 0 when no response was received (error, drop, timeout). */
    val statusCode: Int,
    val reason: String,
    val durationMs: Long,
    val requestSize: Long,
    val responseSize: Long,
    val requestHeaders: String,
    val responseHeaders: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val requestBody: ByteArray?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val responseBody: ByteArray?,
    val requestBodyFile: String?,
    val responseBodyFile: String?,
    val requestContentType: String?,
    val responseContentType: String?,
    val extension: String,
    val error: String?,
    val edited: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is HttpTransactionEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** Lightweight projection for the history list (no headers / bodies). */
data class HttpTransactionSummary(
    val id: Long,
    val timestamp: Long,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: String,
    val statusCode: Int,
    val durationMs: Long,
    val responseSize: Long,
    val responseContentType: String?,
    val error: String?,
    val edited: Boolean,
)
