package com.rainy.token.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.domain.usecase.SyncError
import com.rainy.token.domain.usecase.UsageSyncCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** Hilt EntryPoint：让 Worker（非 Hilt 组件）拿到 @Singleton 的同步协调器。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UsageSyncEntryPoint {
    fun usageSyncCoordinator(): UsageSyncCoordinator
}

/**
 * 后台用量同步 Worker。
 *
 * 定位：**兜底**。WorkManager 不保证准时（Doze / 厂商省电会把它推迟，app 被强行停止后
 * 要等用户手动打开才恢复），所以真正的主力是前台进入页面时的自动同步。
 * 但只要一天里任意一层成功同步过一次，就不会丢数据（服务端明细窗口约 24h）。
 */
class UsageSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val coordinator = EntryPointAccessors
            .fromApplication(applicationContext, UsageSyncEntryPoint::class.java)
            .usageSyncCoordinator()

        // 用户在设置里关掉了后台同步 → 直接成功退出（不重试、不报警）
        if (!coordinator.backgroundSyncEnabled) return Result.success()

        // 自动路径走增量同步（整页命中即停）：健康状态下 1 个请求就能确认「已接到已知数据」，
        // 省电省流量；补窗口内的洞交给用户主动点的「立即同步」（refreshWindow）。
        val result = coordinator.syncCcgoUsage(incremental = true)
        return when (val error = result.exceptionOrNull()) {
            null -> Result.success()
            // 前台正在同步：不算失败，别和前台抢
            is SyncError.AlreadyRunning -> Result.success()
            // 凭据失效 / 被替换 / 服务端响应无法解析：重试一万次也不会成功，直接结束（避免无谓耗电）
            is RepositoryError.InvalidCredential,
            is RepositoryError.CredentialChanged,
            is RepositoryError.ParseError -> Result.success()
            // 网络波动 / 服务端错误 / 限流（429）：交给 WorkManager 的退避重试
            else -> Result.retry()
        }
    }
}

/**
 * 后台同步调度：每 8 小时一次，仅在网络可用时执行。
 *
 * 用唯一任务名 + KEEP，重复调用不会堆积任务（例如每次冷启动都调用 apply()）。
 */
object UsageSyncScheduler {

    private const val WORK_NAME = "usage_sync_ccgo"
    private const val INTERVAL_HOURS = 8L

    fun apply(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
