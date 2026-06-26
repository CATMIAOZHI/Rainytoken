package com.rainy.token.domain.usecase

import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.repository.CommandCodeUsageRepository
import javax.inject.Inject
import javax.inject.Provider

/**
 * CommandCode Go 用量同步 UseCase。
 *
 * 游标协议：每页返回 (记录列表, 下一页游标)。
 * - cursor=null → 最新页
 * - 返回的列表长度 < PAGE_SIZE → 到底
 *
 * ## 全量同步
 * 从 cursor=null 逐页抓取直至最后一页。
 *
 * ## 增量同步
 * 从 cursor=null 逐页抓取，每页比对本地已有 ID，
 * 当某页全部记录都已存在时停止。
 */
class SyncCommandCodeUsageUseCase @Inject constructor(
    private val usageRepoProvider: Provider<CommandCodeUsageRepository>,
    private val cacheProvider: Provider<UsageCache>
) {
    suspend fun fullSync(): Result<SyncResult> {
        val repo = usageRepoProvider.get()
        val cache = cacheProvider.get()
        var cursor: String? = null
        var totalInserted = 0
        val errors = mutableListOf<String>()

        while (true) {
            val pageResult = repo.fetchPage(cursor)
            if (pageResult.isFailure) {
                errors.add("cursor=${cursor?.take(20)}: ${pageResult.exceptionOrNull()?.message}")
                break
            }
            val (records, nextCursor) = pageResult.getOrThrow()
            if (records.isEmpty()) break

            val before = cache.count()
            cache.insertAll(records)
            totalInserted += (cache.count() - before)

            if (records.size < CommandCodeUsageRepository.PAGE_SIZE) break
            cursor = nextCursor
        }

        return if (errors.isEmpty()) Result.success(SyncResult(inserted = totalInserted))
        else Result.failure(SyncError.PartialSync(totalInserted, errors))
    }

    suspend fun incrementalSync(): Result<SyncResult> {
        val repo = usageRepoProvider.get()
        val cache = cacheProvider.get()
        var cursor: String? = null
        var totalInserted = 0

        while (true) {
            val pageResult = repo.fetchPage(cursor)
            if (pageResult.isFailure) return Result.failure(pageResult.exceptionOrNull()!!)

            val (records, nextCursor) = pageResult.getOrThrow()
            if (records.isEmpty()) break

            val existingIds = cache.getAllIds()
            val newRecords = records.filter { it.id !in existingIds }

            if (newRecords.isEmpty()) break

            cache.insertAll(newRecords)
            totalInserted += newRecords.size

            if (records.size < CommandCodeUsageRepository.PAGE_SIZE) break
            cursor = nextCursor
        }

        return Result.success(SyncResult(inserted = totalInserted))
    }
}