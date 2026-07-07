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

    // v3: 2026-07 统一 Codex amount 为 usedPct（之前是 remainingPct），旧缓存失效
    private val cacheKey = stringPreferencesKey("balance_cache_v3")

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
        // 在 dataStore.edit 的互斥锁内做 read-modify-write，避免并发覆盖
        dataStore.edit { prefs ->
            val raw = prefs[cacheKey]
            val current = if (raw != null) {
                runCatching {
                    json.decodeFromString(
                        MapSerializer(ServiceType.serializer(), CachedBalance.serializer()),
                        raw
                    )
                }.getOrDefault(emptyMap())
            } else emptyMap()
            val updated = current.toMutableMap()
            updated[service] = CachedBalance(balance = balance, fetchedAt = System.currentTimeMillis())
            prefs[cacheKey] = json.encodeToString(
                MapSerializer(ServiceType.serializer(), CachedBalance.serializer()),
                updated
            )
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(cacheKey) }
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