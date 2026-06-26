package com.rainy.token.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 用量记录本地缓存（DataStore，非 Room）。
 *
 * 所有记录序列化为 JSON 列表存入 DataStore。
 * 约 3700 条记录，每条约 300 字节 → ~1.1MB，DataStore 可承受。
 */
class UsageCache(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private val cacheKey = stringPreferencesKey("usage_cache_v1")

    // ── 内存缓存：避免每次都反序列化整个 JSON ──
    @Volatile private var cachedAll: List<UsageRecord>? = null
    @Volatile private var cachedIds: Set<String>? = null

    /** 读取全量（优先内存缓存） */
    private suspend fun loadAll(): List<UsageRecord> {
        cachedAll?.let { return it }
        val records = readFromStore()
        cachedAll = records
        cachedIds = records.map { it.id }.toSet()
        return records
    }

    private suspend fun readFromStore(): List<UsageRecord> {
        val raw = dataStore.data.map { it[cacheKey] }.first() ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(UsageRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    /** 写入后清空内存缓存 */
    private fun invalidateCache() {
        cachedAll = null
        cachedIds = null
    }

    suspend fun getAll(): List<UsageRecord> = loadAll()

    fun getAllFlow(): Flow<List<UsageRecord>> =
        dataStore.data.map { prefs ->
            val raw = prefs[cacheKey] ?: return@map emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(UsageRecord.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    suspend fun insertAll(newRecords: List<UsageRecord>) {
        val current = loadAll().toMutableList()
        val existingIds = cachedIds ?: current.map { it.id }.toSet()
        val toAdd = newRecords.filter { it.id !in existingIds }
        if (toAdd.isEmpty()) return
        current.addAll(toAdd)
        persist(current)
    }

    suspend fun getLatest(): UsageRecord? = loadAll().maxByOrNull { it.timeCreated }

    suspend fun getAllIds(): Set<String> {
        cachedIds?.let { return it }
        return loadAll().map { it.id }.toSet()
    }

    /** 按 workspaceId 删除所有记录。用于修复旧数据格式问题后重新全量同步。 */
    suspend fun deleteByWorkspaceId(workspaceId: String) {
        val current = loadAll().toMutableList()
        val removed = current.removeAll { it.workspaceId == workspaceId }
        if (removed) persist(current)
    }

    suspend fun count(): Int = loadAll().size

    /** 按 workspaceId 统计记录数。 */
    suspend fun count(workspaceId: String): Int = loadAll().count { it.workspaceId == workspaceId }

    /** 获取过滤后的原始记录列表（供图表等聚合使用） */
    suspend fun getRecords(
        workspaceId: String,
        fromTs: Long? = null,
        toTs: Long? = null
    ): List<UsageRecord> {
        return filterByTime(loadAll().filter { it.workspaceId == workspaceId }, fromTs, toTs)
    }

    /** 获取所有不同模型名称 */
    suspend fun getDistinctModels(workspaceId: String): List<String> {
        return loadAll()
            .filter { it.workspaceId == workspaceId }
            .map { it.model }
            .distinct()
            .sorted()
    }

    /** 按时间范围过滤记录 */
    private fun filterByTime(records: List<UsageRecord>, fromTs: Long?, toTs: Long?): List<UsageRecord> {
        var result = records
        if (fromTs != null) result = result.filter { it.timeCreated >= fromTs }
        if (toTs != null) result = result.filter { it.timeCreated <= toTs }
        return result
    }

    suspend fun getStatsByModel(
        workspaceId: String,
        fromTs: Long? = null,
        toTs: Long? = null
    ): List<ModelStats> {
        return filterByTime(loadAll().filter { it.workspaceId == workspaceId }, fromTs, toTs)
            .groupBy { it.model }
            .map { (model, records) ->
                ModelStats(
                    model = model,
                    totalTokens = records.sumOf { it.totalTokens },
                    totalCost = records.sumOf { it.cost },
                    count = records.size
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
        return filterByTime(loadAll().filter { it.workspaceId == workspaceId }, fromTs, toTs)
            .groupBy { it.timeCreated / 86_400_000L * 86_400_000L }
            .map { (dayTs, records) ->
                DailyStats(
                    dayTs = dayTs,
                    totalTokens = records.sumOf { it.totalTokens },
                    totalCost = records.sumOf { it.cost },
                    count = records.size
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
        val records = filterByTime(loadAll().filter { it.workspaceId == workspaceId }, fromTs, toTs)
        if (records.isEmpty()) return null
        return OverviewStats(
            totalTokens = records.sumOf { it.inputTokens + it.cacheReadTokens + it.outputTokens },
            totalCost = records.sumOf { it.cost },
            totalCount = records.size,
            modelCount = records.map { it.model }.distinct().size,
            inputTokens = records.sumOf { it.inputTokens },
            outputTokens = records.sumOf { it.outputTokens },
            reasoningTokens = records.sumOf { it.reasoningTokens },
            cacheReadTokens = records.sumOf { it.cacheReadTokens },
            cacheWrite5mTokens = records.sumOf { it.cacheWrite5mTokens },
            cacheWrite1hTokens = records.sumOf { it.cacheWrite1hTokens }
        )
    }

    private suspend fun persist(list: List<UsageRecord>) {
        val raw = json.encodeToString(ListSerializer(UsageRecord.serializer()), list)
        dataStore.edit { it[cacheKey] = raw }
        invalidateCache()
    }
}

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
    val cost: Long,
    val keyId: String,
    val sessionId: String,
    val enrichmentPlan: String = ""
) {
    val costUsd: Double get() = cost / 100_000_000.0
    val totalTokens: Long get() = inputTokens + outputTokens + reasoningTokens
}

data class ModelStats(
    val model: String,
    val totalTokens: Long,
    val totalCost: Long,
    val count: Int
)

data class DailyStats(
    val dayTs: Long,
    val totalTokens: Long,
    val totalCost: Long,
    val count: Int
)

data class OverviewStats(
    val totalTokens: Long,
    val totalCost: Long,
    val totalCount: Int,
    val modelCount: Int,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWrite5mTokens: Long = 0,
    val cacheWrite1hTokens: Long = 0
) {
    /** 缓存写入总量（5分钟 + 1小时） */
    val cacheWriteTokens: Long get() = cacheWrite5mTokens + cacheWrite1hTokens
}

/** DataStore 委托 */
val Context.usageCacheDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "usage_cache"
)