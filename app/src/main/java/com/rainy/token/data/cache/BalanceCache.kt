package com.rainy.token.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 余额本地缓存层（**非加密**）。
 *
 * 计划 7.1：断网时 UI 仍可展示 stale 数据。DataStore 文件名 `balance_cache`。
 *
 * 存储结构：JSON 序列化的 `Map<ServiceType, CachedBalance>`。
 */
class BalanceCache(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = DefaultJson
) {

    // v2: 2026-06 重构 extras 字段名（rollingUsagePercent → rolling.pct 等）。
    // 升 v2 让旧版缓存失效，避免显示 0%。
    private val cacheKey = stringPreferencesKey("balance_cache_v2")

    suspend fun getAll(): Map<ServiceType, CachedBalance> {
        val raw = dataStore.data.map { it[cacheKey] }.first() ?: return emptyMap()
        return runCatching {
            val map = json.decodeFromString(
                MapSerializer(ServiceType.serializer(), CachedBalance.serializer()),
                raw
            )
            map
        }.getOrDefault(emptyMap())
    }

    suspend fun get(service: ServiceType): CachedBalance? = getAll()[service]

    suspend fun put(service: ServiceType, balance: ServiceBalance) {
        val current = getAll().toMutableMap()
        current[service] = CachedBalance(balance = balance, fetchedAt = System.currentTimeMillis())
        persist(current)
    }

    suspend fun clear() {
        dataStore.edit { it.remove(cacheKey) }
    }

    private suspend fun persist(map: Map<ServiceType, CachedBalance>) {
        val raw = json.encodeToString(
            MapSerializer(ServiceType.serializer(), CachedBalance.serializer()),
            map
        )
        dataStore.edit { it[cacheKey] = raw }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@kotlinx.serialization.Serializable
data class CachedBalance(
    val balance: ServiceBalance,
    val fetchedAt: Long
)

/** 顶层 DataStore 委托。文件名对应计划 7.1。 */
val Context.balanceCacheDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "balance_cache"
)