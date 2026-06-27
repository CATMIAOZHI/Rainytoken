package com.rainy.token.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.rainy.token.data.local.ChartSettingsStore
import com.rainy.token.data.local.chartSettingsStore
import com.rainy.token.data.local.secureStorageDataStore
import com.rainy.token.data.local.usageCacheDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * DataStore 的 [Named] 标识。
 *
 * SecureStorage 和 BalanceCache 都基于 [DataStore]<[Preferences]>，必须用 Named 区分。
 */
object DataStoreQualifiers {
        const val SECURE_STORAGE = "dataStore.secureStorage"
        const val BALANCE_CACHE = "dataStore.balanceCache"
        const val USAGE_CACHE = "dataStore.usageCache"
    }

/**
 * 存储层 DataStore 提供模块。
 *
 * - 密文 DataStore 存在 `secure_storage` 文件中
 * - 余额缓存 DataStore 存在 `balance_cache` 文件中
 * - 加密用 Keystore 中的 AES-256 GCM 主密钥
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    @Named(DataStoreQualifiers.SECURE_STORAGE)
    fun provideSecureStorageDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.secureStorageDataStore

    @Provides
    @Singleton
    @Named(DataStoreQualifiers.USAGE_CACHE)
    fun provideUsageCacheDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.usageCacheDataStore

    @Provides
    @Singleton
    fun provideChartSettingsStore(
        @ApplicationContext context: Context
    ): ChartSettingsStore = context.chartSettingsStore
}