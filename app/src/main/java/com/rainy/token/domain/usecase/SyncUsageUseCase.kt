package com.rainy.token.domain.usecase

import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.repository.OpenCodeUsageRepository
import javax.inject.Inject
import javax.inject.Provider

/**
 * 用量数据同步 UseCase。
 *
 * ## 首次全量同步
 * 从 cursor=0 开始逐页抓取直至最后一页（count=0 或 <50），全量插入。
 *
 * ## 增量同步
 * 从 cursor=0 逐页抓取，每页比对本地已有 ID，
 * **当某页全部记录都已存在于本地时停止**（说明已接到旧数据，不会漏）。
 */
class SyncUsageUseCase @Inject constructor(
    private val usageRepoProvider: Provider<OpenCodeUsageRepository>,
    private val cacheProvider: Provider<UsageCache>
) {
    suspend fun fullSync(): Result<SyncResult> {
        val repo = usageRepoProvider.get()
        val cache = cacheProvider.get()
        var cursor = 0
        var totalInserted = 0
        val errors = mutableListOf<String>()

        while (true) {
            val pageResult = repo.fetchPage(cursor)
            if (pageResult.isFailure) {
                errors.add("cursor=$cursor: ${pageResult.exceptionOrNull()?.message}")
                break
            }
            val records = pageResult.getOrThrow()
            if (records.isEmpty()) break

            val before = cache.count()
            cache.insertAll(records)
            totalInserted += (cache.count() - before)

            if (records.size < 50) break
            cursor++
        }

        return if (errors.isEmpty()) Result.success(SyncResult(inserted = totalInserted))
        else Result.failure(SyncError.PartialSync(totalInserted, errors))
    }

    suspend fun incrementalSync(): Result<SyncResult> {
        val repo = usageRepoProvider.get()
        val cache = cacheProvider.get()
        var cursor = 0
        var totalInserted = 0

        while (true) {
            val pageResult = repo.fetchPage(cursor)
            if (pageResult.isFailure) return Result.failure(pageResult.exceptionOrNull()!!)

            val records = pageResult.getOrThrow()
            if (records.isEmpty()) break

            // 用第一条记录的 workspaceId 过滤本地已有 ID，避免跨 workspace 碰撞
            val workspaceId = records.firstOrNull()?.workspaceId ?: ""
            val existingIds = if (workspaceId.isNotEmpty()) cache.getIdsByWorkspace(workspaceId) else cache.getAllIds()
            val newRecords = records.filter { it.id !in existingIds }

            if (newRecords.isEmpty()) break            // 整页都已存在 → 接到旧数据

            cache.insertAll(newRecords)
            totalInserted += newRecords.size

            if (records.size < 50) break               // 最后一页
            cursor++
        }

        return Result.success(SyncResult(inserted = totalInserted))
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
    val stopReason: SyncStopReason = SyncStopReason.BOTTOM
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