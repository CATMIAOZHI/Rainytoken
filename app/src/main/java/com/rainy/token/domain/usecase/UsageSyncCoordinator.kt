package com.rainy.token.domain.usecase

import android.content.Context
import android.content.SharedPreferences
import com.rainy.token.data.repository.CommandCodeUsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 用量同步协调器：进程级单飞 + 同步状态持久化。
 *
 * 为什么必须单独存在这一层：
 *  - 同一 workspace 会同时存在多个 UsageViewModel 实例（仪表盘卡片、详情页、概览页、
 *    双栏详情各自持有 ViewModelStore），ViewModel 内部的 syncing 标志无法互斥，
 *    多入口同时同步会重复请求并互相竞争。
 *  - UseCase 不是 @Singleton（调用方用 Provider.get() 每次新建实例），
 *    实例内的 Mutex 不共享，所以互斥必须放在 @Singleton 的这一层。
 *
 * 顺带统一管理（沿用既有 prefs，不破坏老版本已写入的数据）：
 *  - 最近一次成功同步时间（usage_sync_at / sync_at_<workspaceId>）
 *  - 后台同步开关（usage_sync_at / background_sync_enabled）
 */
@Singleton
class UsageSyncCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val ccgoSyncProvider: Provider<SyncCommandCodeUsageUseCase>
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val syncMutex = Mutex()

    private val _busy = MutableStateFlow(false)

    /** 是否有同步正在进行：跨页面共享，让所有入口的刷新按钮一起转圈。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * CCGO 同步（进程级单飞）。
     *
     * @param incremental true = 增量同步（整页命中即停，自动路径用：回到前台 / 8h 后台任务，省请求）
     *                    false = 把服务端窗口拉满（用户主动点「立即同步」用，能把窗口内的洞补齐）
     *
     * 并发调用时直接返回 [SyncError.AlreadyRunning]，不排队、不伪装成功。
     */
    suspend fun syncCcgoUsage(incremental: Boolean): Result<SyncResult> {
        if (!syncMutex.tryLock()) {
            return Result.failure(SyncError.AlreadyRunning())
        }
        _busy.value = true
        return try {
            val useCase = ccgoSyncProvider.get()
            val result = if (incremental) useCase.incrementalSync() else useCase.refreshWindow()
            if (result.isSuccess) {
                saveLastSyncAt(
                    CommandCodeUsageRepository.CCGO_WORKSPACE_ID,
                    System.currentTimeMillis()
                )
            }
            result
        } finally {
            _busy.value = false
            syncMutex.unlock()
        }
    }

    // ===== 同步时间 =====

    fun lastSyncAt(workspaceId: String): Long = prefs.getLong(syncAtKey(workspaceId), 0L)

    /** 距上次成功同步是否已超过 [maxAgeMs]；从未成功同步过也算过期。 */
    fun isSyncStale(workspaceId: String, maxAgeMs: Long = STALE_AFTER_MS): Boolean {
        val last = lastSyncAt(workspaceId)
        return last <= 0L || System.currentTimeMillis() - last > maxAgeMs
    }

    fun saveLastSyncAt(workspaceId: String, ts: Long) {
        prefs.edit().putLong(syncAtKey(workspaceId), ts).apply()
    }

    // ===== 后台同步开关 =====

    var backgroundSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_SYNC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, value).apply()
        }

    companion object {
        /** 沿用既有 prefs 名与 key 格式，保证老版本写入的同步时间不丢失 */
        private const val PREFS_NAME = "usage_sync_at"
        private const val KEY_PREFIX = "sync_at_"
        private const val KEY_BACKGROUND_SYNC = "background_sync_enabled"

        /**
         * 窗口守护阈值：超过它没有成功同步，UI 提示「数据可能已过期」。
         * 服务端窗口约 24h，取 12h 留出一次补救机会。
         */
        const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L

        /** 前台进入页面时的自动同步最小间隔（距上次成功同步超过它才自动同步）。 */
        const val FOREGROUND_MIN_INTERVAL_MS = 8 * 60 * 60 * 1000L

        private fun syncAtKey(workspaceId: String) = KEY_PREFIX + workspaceId
    }
}
