package com.rainy.token.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rainy.token.data.repository.RefreshWriteSession
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.currentCoroutineContext
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
        return decode(raw)
    }

    suspend fun get(service: ServiceType): CachedBalance? = getAll()[service]

    suspend fun put(service: ServiceType, balance: ServiceBalance) {
        currentCoroutineContext()[RefreshWriteSession]?.let { session ->
            session.stageBalance(service, balance)
            return
        }
        putCached(
            service = service,
            cachedBalance = CachedBalance(
                balance = balance,
                fetchedAt = System.currentTimeMillis()
            )
        )
    }

    /** 恢复一份已有缓存并保留原 fetchedAt；仅供凭据测试安全回滚使用。 */
    internal suspend fun putCached(service: ServiceType, cachedBalance: CachedBalance) {
        dataStore.edit { prefs ->
            val updated = decode(prefs[cacheKey]).toMutableMap()
            updated[service] = cachedBalance
            prefs[cacheKey] = encode(updated)
        }
    }

    /** 删除单个服务缓存。凭据新增、替换或删除时由 CredentialRepository 调用。 */
    suspend fun remove(service: ServiceType) {
        dataStore.edit { prefs ->
            val current = decode(prefs[cacheKey])
            if (service !in current) return@edit
            val updated = current.toMutableMap()
            updated.remove(service)
            if (updated.isEmpty()) {
                prefs.remove(cacheKey)
            } else {
                prefs[cacheKey] = encode(updated)
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(cacheKey) }
    }

    private fun decode(raw: String?): Map<ServiceType, CachedBalance> {
        if (raw == null) return emptyMap()
        return runCatching {
            json.decodeFromString(
                MapSerializer(ServiceType.serializer(), CachedBalance.serializer()),
                raw
            )
        }.getOrDefault(emptyMap())
    }

    private fun encode(value: Map<ServiceType, CachedBalance>): String =
        json.encodeToString(
            MapSerializer(ServiceType.serializer(), CachedBalance.serializer()),
            value
        )

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
