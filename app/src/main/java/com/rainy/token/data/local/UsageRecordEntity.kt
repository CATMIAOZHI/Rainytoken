package com.rainy.token.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room Entity for usage records.
 *
 * Mirrors the fields of [UsageRecord] exactly.
 * Indexes on workspaceId + timeCreated for fast time-range queries.
 */
@Entity(
    tableName = "usage_records",
    indices = [
        Index(value = ["workspaceId", "timeCreated"]),
        Index(value = ["workspaceId", "model"]),
        Index(value = ["id"], unique = true)
    ]
)
data class UsageRecordEntity(
    @PrimaryKey
    val id: String,
    val workspaceId: String,
    val timeCreated: Long,
    val timeUpdated: Long,
    val model: String,
    val provider: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWrite5mTokens: Long = 0,
    val cacheWrite1hTokens: Long = 0,
    val cost: Long,
    val keyId: String,
    val sessionId: String,
    val enrichmentPlan: String = ""
) {
    /** Convert Entity → domain model */
    fun toDomain(): UsageRecord = UsageRecord(
        id = id,
        workspaceId = workspaceId,
        timeCreated = timeCreated,
        timeUpdated = timeUpdated,
        model = model,
        provider = provider,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWrite5mTokens = cacheWrite5mTokens,
        cacheWrite1hTokens = cacheWrite1hTokens,
        cost = cost,
        keyId = keyId,
        sessionId = sessionId,
        enrichmentPlan = enrichmentPlan
    )

    val totalTokens: Long get() = inputTokens + outputTokens + reasoningTokens
}

/** Convert domain model → Entity */
fun UsageRecord.toEntity(): UsageRecordEntity = UsageRecordEntity(
    id = id,
    workspaceId = workspaceId,
    timeCreated = timeCreated,
    timeUpdated = timeUpdated,
    model = model,
    provider = provider,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    reasoningTokens = reasoningTokens,
    cacheReadTokens = cacheReadTokens,
    cacheWrite5mTokens = cacheWrite5mTokens,
    cacheWrite1hTokens = cacheWrite1hTokens,
    cost = cost,
    keyId = keyId,
    sessionId = sessionId,
    enrichmentPlan = enrichmentPlan
)