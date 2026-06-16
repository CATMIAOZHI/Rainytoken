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

            val existingIds = cache.getAllIds()        // 本地已有 ID 集合
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

data class SyncResult(
    val inserted: Int,
    val totalCount: Int = 0
)

sealed class SyncError : Exception() {
    class PartialSync(val inserted: Int, val errors: List<String>) : SyncError() {
        override val message: String = "部分同步完成：插入 $inserted 条，${errors.size} 页失败"
    }
}