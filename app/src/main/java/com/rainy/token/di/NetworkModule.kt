package com.rainy.token.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.balanceCacheDataStore
import com.rainy.token.data.remote.DeepSeekApi
import com.rainy.token.data.local.SecureStorage
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.local.usageCacheDataStore
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.DeepSeekRepository
import com.rainy.token.data.repository.CommandCodeGoRepository
import com.rainy.token.data.repository.CommandCodeUsageRepository
import com.rainy.token.data.repository.OpenCodeGoRepository
import com.rainy.token.data.repository.OpenCodeUsageRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * 网络层 + JSON 序列化 + Room 数据库 DI 模块。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json
    ): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.deepseek.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideDeepSeekApi(retrofit: Retrofit): DeepSeekApi =
        retrofit.create(DeepSeekApi::class.java)

    // ---- Room ----

    @Provides
    @Singleton
    fun provideUsageCache(
        @Named(DataStoreQualifiers.USAGE_CACHE) dataStore: DataStore<Preferences>,
        json: Json
    ): UsageCache = UsageCache(dataStore, json)

    // ---- Repositories ----

    @Provides
    @Singleton
    fun provideOpenCodeUsageRepository(
        okHttpClient: OkHttpClient,
        credentialRepository: CredentialRepository
    ): OpenCodeUsageRepository = OpenCodeUsageRepository(okHttpClient, credentialRepository)

    /**
     * OpenCode Go 仓库：同上用 @Provides 显式构造。
     */
    @Provides
    @Singleton
    fun provideOpenCodeGoRepository(
        okHttpClient: OkHttpClient,
        credentialRepository: CredentialRepository,
        balanceCache: BalanceCache
    ): OpenCodeGoRepository = OpenCodeGoRepository(okHttpClient, credentialRepository, balanceCache)

    /**
     * CommandCode Go 用量仓库。
     */
    @Provides
    @Singleton
    fun provideCommandCodeUsageRepository(
        okHttpClient: OkHttpClient,
        credentialRepository: CredentialRepository
    ): CommandCodeUsageRepository = CommandCodeUsageRepository(okHttpClient, credentialRepository)

    /**
     * CommandCode Go 仓库：API Key 认证，调 JSON API。
     */
    @Provides
    @Singleton
    fun provideCommandCodeGoRepository(
        okHttpClient: OkHttpClient,
        credentialRepository: CredentialRepository,
        balanceCache: BalanceCache
    ): CommandCodeGoRepository = CommandCodeGoRepository(okHttpClient, credentialRepository, balanceCache)

    /**
     * DeepSeek 仓库：同上。注意即使 [DeepSeekRepository] 自己有 @Inject constructor，
     * 在 3+ Repository 同模式时 KSP 2.x 仍会误报，这里统一改 @Provides 杜绝隐患。
     */
    @Provides
    @Singleton
    fun provideDeepSeekRepository(
        deepSeekApi: DeepSeekApi,
        credentialRepository: CredentialRepository,
        balanceCache: BalanceCache
    ): DeepSeekRepository = DeepSeekRepository(deepSeekApi, credentialRepository, balanceCache)

    /** 余额缓存 DataStore（计划 7.1） */
    @Provides
    @Singleton
    @Named(DataStoreQualifiers.BALANCE_CACHE)
    fun provideBalanceCacheDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.balanceCacheDataStore

    @Provides
    @Singleton
    fun provideBalanceCache(
        @Named(DataStoreQualifiers.BALANCE_CACHE) dataStore: DataStore<Preferences>,
        json: Json
    ): BalanceCache = BalanceCache(dataStore, json)

    /** SecureStorage 复用 json */
    @Provides
    @Singleton
    fun provideSecureStorage(
        @Named(DataStoreQualifiers.SECURE_STORAGE) dataStore: DataStore<Preferences>,
        json: Json
    ): SecureStorage = SecureStorage(dataStore, json)
}