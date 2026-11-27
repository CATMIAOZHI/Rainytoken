package com.rainy.token.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 用量记录本地缓存（Room 实现）。
 *
 * 接口与旧 DataStore 版本完全一致，内部改为 Room DAO 查询。
 * 首次访问时自动从旧 DataStore JSON 迁移数据到 Room。
 */
class UsageCache(
    private val context: Context,
    private val dao: UsageDao,
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private val migrationMutex = Mutex()
    @Volatile private var migrated = false

    /** 确保旧数据已迁移到 Room（仅执行一次） */
    private suspend fun ensureMigrated() {
        if (migrated) return
        migrationMutex.withLock {
            if (migrated) return
            migrateDataStoreToRoom(context, dataStore, dao, json)
            migrated = true
        }
    }

    suspend fun getAll(): List<UsageRecord> {
        ensureMigrated()
        return dao.getAll().map { it.toDomain() }
    }

    fun getAllFlow(): Flow<List<UsageRecord>> = flow {
        ensureMigrated()
        emit(dao.getAll().map { it.toDomain() })
    }

    /**
     * 批量插入，返回**真正写入**的条数。
     *
     * DAO 使用 OnConflictStrategy.IGNORE：同一 workspaceId 下已存在的 id 会被忽略且返回 -1L，可据此精确计数。
     * 旧实现用全表 count() 差值统计插入数，会被并发的其它服务同步污染。
     */
    suspend fun insertAll(newRecords: List<UsageRecord>): Int {
        ensureMigrated()
        if (newRecords.isEmpty()) return 0
        val entities = newRecords.map { it.toEntity() }
        return dao.insertAll(entities).count { it != -1L }
    }

    suspend fun insertRequestLogs(records: List<UsageRecord>): Int = insertAll(records)

    suspend fun latestTimestamp(scope: String): Long? {
        ensureMigrated()
        return dao.getMaxTimeCreated(scope)
    }

    /** Counts rows, not requests represented by those rows. Bounds are inclusive. */
    suspend fun countRecords(scope: String, fromTs: Long?, toTs: Long?, models: Set<String>): Int {
        ensureMigrated()
        val from = fromTs ?: Long.MIN_VALUE
        val to = toTs ?: Long.MAX_VALUE
        return if (models.isEmpty()) dao.countRecords(scope, from, to)
        else dao.countRecordsByModels(scope, from, to, models.toList())
    }

    /** A deterministic page even when multiple requests share the same millisecond. */
    suspend fun getRecordPage(
        scope: String,
        fromTs: Long?,
        toTs: Long?,
        models: Set<String>,
        limit: Int,
        offset: Int
    ): List<UsageRecord> {
        require(limit > 0 && offset >= 0)
        ensureMigrated()
        val from = fromTs ?: Long.MIN_VALUE
        val to = toTs ?: Long.MAX_VALUE
        val rows = if (models.isEmpty()) dao.getRecordPage(scope, from, to, limit, offset)
        else dao.getRecordPageByModels(scope, from, to, models.toList(), limit, offset)
        return rows.map { it.toDomain() }
    }

    suspend fun getGoRecordPageSnapshot(
        scope: String,
        fromTs: Long?,
        toTs: Long?,
        models: Set<String>,
        limit: Int,
        requestedPage: Int,
        previousScopeCount: Int?
    ): GoRecordPageSnapshot<UsageRecord> {
        require(limit > 0)
        ensureMigrated()
        val page = dao.getGoRecordPageSnapshot(
            scope, fromTs ?: Long.MIN_VALUE, toTs ?: Long.MAX_VALUE,
            models.toList(), limit, requestedPage, previousScopeCount
        )
        return GoRecordPageSnapshot(
            page.scopeCount, page.filteredCount, page.page,
            page.records.map { it.toDomain() }, page.models
        )
    }
    suspend fun getLatest(): UsageRecord? {
        ensureMigrated()
        return dao.getLatest()?.toDomain()
    }


    /** 按 workspaceId 获取已有记录 ID 集合（增量同步用） */
    suspend fun getIdsByWorkspace(workspaceId: String): Set<String> {
        ensureMigrated()
        return dao.getIdsByWorkspace(workspaceId).toSet()
    }

    /** 按 workspaceId 删除所有记录。用于修复旧数据格式问题后重新全量同步。 */
    suspend fun deleteByWorkspaceId(workspaceId: String) {
        ensureMigrated()
        dao.deleteByWorkspaceId(workspaceId)
    }

    suspend fun count(): Int {
        ensureMigrated()
        return dao.count()
    }

    /** 按 workspaceId 统计记录数。 */
    suspend fun count(workspaceId: String): Int {
        ensureMigrated()
        return dao.countByWorkspace(workspaceId)
    }

    /** 获取过滤后的原始记录列表（供图表等聚合使用） */
    suspend fun getRecords(
        workspaceId: String,
        fromTs: Long? = null,
        toTs: Long? = null
    ): List<UsageRecord> {
        ensureMigrated()
        val entities = when {
            fromTs != null && toTs != null -> dao.getByWorkspaceAndTime(workspaceId, fromTs, toTs)
            fromTs != null -> dao.getByWorkspaceFrom(workspaceId, fromTs)
            toTs != null -> dao.getByWorkspaceTo(workspaceId, toTs)
            else -> dao.getByWorkspace(workspaceId)
        }
        return entities.map { it.toDomain() }
    }

    /** 获取所有不同模型名称 */
    suspend fun getDistinctModels(workspaceId: String): List<String> {
        ensureMigrated()
        return dao.getDistinctModels(workspaceId)
    }

    suspend fun getStatsByModel(
        workspaceId: String,
        fromTs: Long? = null,
        toTs: Long? = null
    ): List<ModelStats> {
        val records = getRecords(workspaceId, fromTs, toTs)
        return records
            .groupBy { it.model }
            .map { (model, recs) ->
                ModelStats(
                    model = model,
                    totalTokens = recs.sumOf { it.totalTokens },
                    totalCost = recs.sumOf { it.cost ?: 0.0 },
                    count = recs.sumOf { it.requestCount },
                    pricedRequests = recs.sumOf { it.pricedRequests }
                )
            }
            .sortedByDescending { it.totalTokens }
    }

    suspend fun getStatsByDay(
        workspaceId: String,
        limit: Int = 30,
        fromTs: Long? = null,
        toTs: Long? = null
    ): List<DailyStats> {
        val records = getRecords(workspaceId, fromTs, toTs)
        return records
            .groupBy { it.timeCreated / 86_400_000L * 86_400_000L }
            .map { (dayTs, recs) ->
                DailyStats(
                    dayTs = dayTs,
                    totalTokens = recs.sumOf { it.totalTokens },
                    totalCost = recs.sumOf { it.cost ?: 0.0 },
                    count = recs.sumOf { it.requestCount },
                    pricedRequests = recs.sumOf { it.pricedRequests }
                )
            }
            .sortedByDescending { it.dayTs }
            .take(limit)
    }

    suspend fun getOverview(
        workspaceId: String,
        fromTs: Long? = null,
        toTs: Long? = null
    ): OverviewStats? {
        val records = getRecords(workspaceId, fromTs, toTs)
        if (records.isEmpty()) return null
        return OverviewStats(
            totalTokens = if (workspaceId.startsWith(OPEN_CODE_LOGS_SCOPE_PREFIX)) {
                records.sumOf { it.totalTokens }
            } else {
                records.sumOf { it.inputTokens + it.cacheReadTokens + it.outputTokens }
            },
            totalCost = records.sumOf { it.cost ?: 0.0 },
            totalCount = records.sumOf { it.requestCount },
            modelCount = records.map { it.model }.distinct().size,
            inputTokens = records.sumOf { it.inputTokens },
            outputTokens = records.sumOf { it.outputTokens },
            reasoningTokens = records.sumOf { it.reasoningTokens },
            cacheReadTokens = records.sumOf { it.cacheReadTokens },
            cacheWrite5mTokens = records.sumOf { it.cacheWrite5mTokens },
            cacheWrite1hTokens = records.sumOf { it.cacheWrite1hTokens },
            cacheWriteTokensUnspecified = records.sumOf { it.cacheWriteTokensUnspecified ?: 0L },
            pricedRequests = records.sumOf { it.pricedRequests }
        )
    }
}

internal const val OPEN_CODE_LOGS_SCOPE_PREFIX = "opencode-logs:"

@Serializable
data class UsageRecord(
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
    /** Cost in USD; null when the service does not report it. */
    val cost: Double?,
    val keyId: String,
    val sessionId: String,
    val enrichmentPlan: String = "",
    // Legacy providers store one request per row; CSV exports store one daily aggregate per identity.
    val requestCount: Long = 1,
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
    val pricedRequests: Long get() = if (cost == null) 0L else requestCount

    // OpenCode reports cache reads separately; preserve the previous Go total, including CSV rows.
    val totalTokens: Long get() = inputTokens + outputTokens + reasoningTokens +
        (if (workspaceId.startsWith(OPEN_CODE_LOGS_SCOPE_PREFIX) || userType != null) cacheReadTokens else 0L)
}

data class ModelStats(
    val model: String,
    val totalTokens: Long,
    val totalCost: Double,
    val count: Long,
    val pricedRequests: Long = count
)

data class DailyStats(
    val dayTs: Long,
    val totalTokens: Long,
    val totalCost: Double,
    val count: Long,
    val pricedRequests: Long = count
)

data class OverviewStats(
    val totalTokens: Long,
    val totalCost: Double,
    val totalCount: Long,
    val modelCount: Int,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWrite5mTokens: Long = 0,
    val cacheWrite1hTokens: Long = 0,
    val cacheWriteTokensUnspecified: Long = 0,
    val pricedRequests: Long = totalCount
) {
    /** 缓存写入总量（包含未标注 TTL 的写入） */
    val cacheWriteTokens: Long get() = cacheWrite5mTokens + cacheWrite1hTokens + cacheWriteTokensUnspecified
}

/** DataStore 委托 — 保留用于数据迁移，迁移完成后旧 key 会被清除 */
val Context.usageCacheDataStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(
    name = "usage_cache"
)