package com.rainy.token.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

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

    @Update
    suspend fun updateAll(records: List<UsageRecordEntity>): Int

    @Query("DELETE FROM usage_records WHERE workspaceId = :scope AND id IN (:ids)")
    suspend fun deleteExportRows(scope: String, ids: List<String>): Int

    @Transaction
    suspend fun applyDailyExport(
        scope: String,
        fromTs: Long,
        toTs: Long,
        incoming: List<UsageRecordEntity>
    ): UsageExportChanges {
        val cached = getByWorkspaceAndTime(scope, fromTs, toTs)
        val changes = diffDailyExport(cached, incoming)
        val inserted = if (changes.inserts.isEmpty()) 0 else insertAll(changes.inserts).count { it != -1L }
        val updated = if (changes.updates.isEmpty()) 0 else updateAll(changes.updates)
        val deleted = changes.deletes.chunked(500).sumOf { deleteExportRows(scope, it) }
        return UsageExportChanges(inserted, updated, deleted)
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
/** Stable identities match submissions even while their request/token/cost before midnight changes. */
internal fun diffDailyExport(cached: List<UsageRecordEntity>, incoming: List<UsageRecordEntity>): DailyExportDiff {
    val cachedById = cached.associateBy { it.id }
    val incomingIds = incoming.mapTo(HashSet()) { it.id }
    val inserts = mutableListOf<UsageRecordEntity>()
    val updates = mutableListOf<UsageRecordEntity>()
    for (record in incoming) {
        val previous = cachedById[record.id]
        if (previous == null) inserts += record else if (previous != record) updates += record
    }
    return DailyExportDiff(inserts, updates, cached.filter { it.id !in incomingIds }.map { it.id })
}

internal data class DailyExportDiff(
    val inserts: List<UsageRecordEntity>,
    val updates: List<UsageRecordEntity>,
    val deletes: List<String>
)

data class UsageExportChanges(val inserted: Int, val updated: Int, val deleted: Int) {
    val unchanged: Boolean get() = inserted == 0 && updated == 0 && deleted == 0
}
