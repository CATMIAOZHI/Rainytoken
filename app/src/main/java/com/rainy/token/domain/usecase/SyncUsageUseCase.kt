package com.rainy.token.domain.usecase

import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.repository.OpenCodeUsageRepository
import com.rainy.token.data.repository.RepositoryError
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** OpenCode exports one rolling snapshot; there is no cursor or immutable per-request ID. */
@Singleton
class SyncUsageUseCase @Inject constructor(
    private val usageRepoProvider: Provider<OpenCodeUsageRepository>,
    private val cacheProvider: Provider<UsageCache>,
    private val syncCoordinator: UsageSyncCoordinator
) {
    private val inFlightScopes = mutableSetOf<String>()

    suspend fun fullSync(scope: String): Result<SyncResult> = sync(scope, incremental = false)
    suspend fun incrementalSync(scope: String): Result<SyncResult> = sync(scope, incremental = true)

    private suspend fun sync(scope: String, incremental: Boolean): Result<SyncResult> {
        // Only block duplicate refreshes of this key. An older key's blocking HTTP
        // call may still be winding down when a different key needs its first import.
        if (!synchronized(inFlightScopes) { inFlightScopes.add(scope) }) {
            return Result.failure(SyncError.AlreadyRunning())
        }
        return try {
            val export = usageRepoProvider.get().fetchExport(scope).getOrThrow()
            val cache = cacheProvider.get()
            // Compare the whole export before declaring a hit: known rows can grow during the
            // day, and an unchanged recent day does not prove older days are already complete.
            val changes = cache.applyDailyExport(export.cacheScope, export.fromTs, export.toTs, export.records)
            val result = SyncResult(
                inserted = changes.inserted,
                updated = changes.updated,
                deleted = changes.deleted,
                totalCount = cache.count(export.cacheScope),
                scanned = export.records.size,
                pages = 1,
                stopReason = if (incremental && changes.unchanged) SyncStopReason.HIT_EXISTING else SyncStopReason.BOTTOM
            )
            syncCoordinator.saveLastSyncAt(export.cacheScope, System.currentTimeMillis())
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RepositoryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(RepositoryError.Unknown(e))
        } finally {
            synchronized(inFlightScopes) { inFlightScopes.remove(scope) }
        }
    }
}

/** 同步停止原因：把「真追平」与「被截断 / 遇到异常」区分开（旧实现不可区分，空洞因此无法诊断）。 */
enum class SyncStopReason {
    /** 服务端已经没有更多记录（真追平服务端窗口） */
    BOTTOM,

    /** 增量同步：整页记录本地都已存在（已接到已知数据），按启发式停在这里 */
    HIT_EXISTING,

    CURSOR_STUCK,
    MAX_PAGES,
    PAGE_ERROR,
    PARSE_ANOMALY
}

data class SyncResult(
    val inserted: Int,
    val totalCount: Int = 0,
    /** 本次扫过的服务端记录数（含已存在的） */
    val scanned: Int = 0,
    /** 解析失败被丢弃的条数，>0 表示服务端有本地解析不了的数据 */
    val dropped: Int = 0,
    val pages: Int = 0,
    val stopReason: SyncStopReason = SyncStopReason.BOTTOM,
    val updated: Int = 0,
    val deleted: Int = 0
)

sealed class SyncError : Exception() {
    /** 已有同步任务在跑（进程级互斥），本次请求被跳过。 */
    class AlreadyRunning : SyncError() {
        override val message: String = "同步正在进行中"
    }

    class PartialSync(val inserted: Int, val errors: List<String>) : SyncError() {
        override val message: String = buildString {
            append("部分同步完成：插入 ${inserted}条，${errors.size}页失败")
            if (errors.isNotEmpty()) append("。${errors.first().take(200)}")
        }
    }
}