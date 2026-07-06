package com.rainy.token.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    @Query("SELECT id FROM usage_records")
    suspend fun getAllIds(): List<String>

    @Query("SELECT id FROM usage_records WHERE workspaceId = :workspaceId")
    suspend fun getIdsByWorkspace(workspaceId: String): List<String>

    @Query("SELECT MAX(timeCreated) FROM usage_records WHERE workspaceId = :workspaceId")
    suspend fun getMaxTimeCreated(workspaceId: String): Long?
}