package com.rainy.token.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Room Entity for usage records.
 *
 * Mirrors the fields of [UsageRecord] exactly.
 * Indexes on workspaceId + timeCreated for fast time-range queries.
 */
@Entity(
    tableName = "usage_records",
    primaryKeys = ["workspaceId", "id"],
    indices = [
        Index(value = ["workspaceId", "timeCreated", "id"]),
        Index(value = ["workspaceId", "model", "timeCreated"])
    ]
)
data class UsageRecordEntity(
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
    val cost: Double?,
    val keyId: String,
    val sessionId: String,
    val enrichmentPlan: String = "",
    @ColumnInfo(defaultValue = "1") val requestCount: Long = 1,
    val userType: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val durationMs: Long? = null,
    val timeToFirstTokenMs: Long? = null,
    val outcome: String? = null,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val country: String? = null,
    val city: String? = null,
    val reasoningEffort: String? = null,
    val requestedModel: String? = null,
    val cacheWriteTokensUnspecified: Long? = null
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
        enrichmentPlan = enrichmentPlan,
        requestCount = requestCount,
        userType = userType,
        userId = userId,
        userName = userName,
        durationMs = durationMs,
        timeToFirstTokenMs = timeToFirstTokenMs,
        outcome = outcome,
        statusCode = statusCode,
        errorCode = errorCode,
        country = country,
        city = city,
        reasoningEffort = reasoningEffort,
        requestedModel = requestedModel,
        cacheWriteTokensUnspecified = cacheWriteTokensUnspecified
    )

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
    enrichmentPlan = enrichmentPlan,
    requestCount = requestCount,
    userType = userType,
    userId = userId,
    userName = userName,
    durationMs = durationMs,
    timeToFirstTokenMs = timeToFirstTokenMs,
    outcome = outcome,
    statusCode = statusCode,
    errorCode = errorCode,
    country = country,
    city = city,
    reasoningEffort = reasoningEffort,
    requestedModel = requestedModel,
    cacheWriteTokensUnspecified = cacheWriteTokensUnspecified
)
