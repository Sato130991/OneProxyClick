package com.oneproxyclick.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HttpTransactionDao {

    @Insert
    suspend fun insert(entity: HttpTransactionEntity): Long

    /**
     * Live filtered history.
     * @param search substring over host, path, query and header blocks ('' = no filter)
     * @param statusClass 0 = all, 2..5 = 2xx..5xx, -1 = no response / error
     * @param method '' = all
     * @param hideStatic 1 to exclude rows whose extension is in [staticExtensions]
     */
    @Query(
        """
        SELECT id, timestamp, method, scheme, host, port, path, query, statusCode, durationMs,
               responseSize, responseContentType, error, edited
        FROM http_transactions
        WHERE (:search = '' OR host LIKE '%' || :search || '%' OR path LIKE '%' || :search || '%'
               OR query LIKE '%' || :search || '%' OR requestHeaders LIKE '%' || :search || '%'
               OR responseHeaders LIKE '%' || :search || '%')
          AND (:statusClass = 0
               OR (:statusClass = -1 AND statusCode = 0)
               OR (statusCode >= :statusClass * 100 AND statusCode < :statusClass * 100 + 100))
          AND (:method = '' OR method = :method)
          AND (:hideStatic = 0 OR extension NOT IN (:staticExtensions))
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    fun observeFiltered(
        search: String,
        statusClass: Int,
        method: String,
        hideStatic: Int,
        staticExtensions: List<String>,
        limit: Int,
    ): Flow<List<HttpTransactionSummary>>

    @Query("SELECT * FROM http_transactions WHERE id = :id")
    suspend fun getById(id: Long): HttpTransactionEntity?

    @Query("SELECT COUNT(*) FROM http_transactions")
    fun observeCount(): Flow<Int>

    @Query("SELECT requestBodyFile FROM http_transactions WHERE requestBodyFile IS NOT NULL UNION SELECT responseBodyFile FROM http_transactions WHERE responseBodyFile IS NOT NULL")
    suspend fun bodyFiles(): List<String>

    @Query("DELETE FROM http_transactions")
    suspend fun deleteAll()

    @Query("DELETE FROM http_transactions WHERE id NOT IN (SELECT id FROM http_transactions ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
