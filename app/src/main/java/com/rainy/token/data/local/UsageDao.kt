package com.rainy.token.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface UsageDao {

    @Query("SELECT * FROM usage_records")
    suspend fun getAll(): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE workspaceId = :workspaceId ORDER BY timeCreated DESC")
    suspend fun getByWorkspace(workspaceId: String): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE workspaceId = :workspaceId AND timeCreated >= :fromTs AND timeCreated <= :toTs ORDER BY timeCreated DESC")
    suspend fun getByWorkspaceAndTime(workspaceId: String, fromTs: Long, toTs: Long): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE workspaceId = :workspaceId AND timeCreated >= :fromTs ORDER BY timeCreated DESC")
    suspend fun getByWorkspaceFrom(workspaceId: String, fromTs: Long): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE workspaceId = :workspaceId AND timeCreated <= :toTs ORDER BY timeCreated DESC")
    suspend fun getByWorkspaceTo(workspaceId: String, toTs: Long): List<UsageRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<UsageRecordEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM usage_records WHERE workspaceId = :scope AND timeCreated BETWEEN :fromTs AND :toTs")
    suspend fun countRecords(scope: String, fromTs: Long, toTs: Long): Int

    @Query("""
        SELECT COUNT(*) FROM usage_records
        WHERE workspaceId = :scope AND timeCreated BETWEEN :fromTs AND :toTs AND model IN (:models)
    """)
    suspend fun countRecordsByModels(scope: String, fromTs: Long, toTs: Long, models: List<String>): Int

    @Query("""
        SELECT * FROM usage_records
        WHERE workspaceId = :scope AND timeCreated BETWEEN :fromTs AND :toTs
        ORDER BY timeCreated DESC, id DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun getRecordPage(
        scope: String, fromTs: Long, toTs: Long, limit: Int, offset: Int
    ): List<UsageRecordEntity>

    @Query("""
        SELECT * FROM usage_records
        WHERE workspaceId = :scope AND timeCreated BETWEEN :fromTs AND :toTs AND model IN (:models)
        ORDER BY timeCreated DESC, id DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun getRecordPageByModels(
        scope: String, fromTs: Long, toTs: Long, models: List<String>, limit: Int, offset: Int
    ): List<UsageRecordEntity>

    /** Go is append-only. A changed scoped count invalidates any previous OFFSET. */
    @Transaction
    suspend fun getGoRecordPageSnapshot(
        scope: String,
        fromTs: Long,
        toTs: Long,
        models: List<String>,
        limit: Int,
        requestedPage: Int,
        previousScopeCount: Int?
    ): GoRecordPageSnapshot<UsageRecordEntity> {
        val scopeCount = countByWorkspace(scope)
        val filteredCount = if (models.isEmpty()) countRecords(scope, fromTs, toTs)
            else countRecordsByModels(scope, fromTs, toTs, models)
        val pages = ((filteredCount.toLong() + limit - 1) / limit).toInt()
        val page = if (previousScopeCount == scopeCount) requestedPage.coerceIn(1, pages.coerceAtLeast(1)) else 1
        val offset = (page - 1) * limit
        val records = if (models.isEmpty()) getRecordPage(scope, fromTs, toTs, limit, offset)
            else getRecordPageByModels(scope, fromTs, toTs, models, limit, offset)
        return GoRecordPageSnapshot(scopeCount, filteredCount, page, records, getDistinctModels(scope))
    }
    @Query("DELETE FROM usage_records WHERE workspaceId = :workspaceId")
    suspend fun deleteByWorkspaceId(workspaceId: String): Int

    @Query("SELECT COUNT(*) FROM usage_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM usage_records WHERE workspaceId = :workspaceId")
    suspend fun countByWorkspace(workspaceId: String): Int

    @Query("SELECT DISTINCT model FROM usage_records WHERE workspaceId = :workspaceId ORDER BY model ASC")
    suspend fun getDistinctModels(workspaceId: String): List<String>

    @Query("SELECT * FROM usage_records ORDER BY timeCreated DESC LIMIT 1")
    suspend fun getLatest(): UsageRecordEntity?


    @Query("SELECT id FROM usage_records WHERE workspaceId = :workspaceId")
    suspend fun getIdsByWorkspace(workspaceId: String): List<String>

    @Query("SELECT MAX(timeCreated) FROM usage_records WHERE workspaceId = :workspaceId")
    suspend fun getMaxTimeCreated(workspaceId: String): Long?
}

/** Row counts and contents read from the same transaction. */
data class GoRecordPageSnapshot<T>(
    val scopeCount: Int,
    val filteredCount: Int,
    val page: Int,
    val records: List<T>,
    val models: List<String>
)
