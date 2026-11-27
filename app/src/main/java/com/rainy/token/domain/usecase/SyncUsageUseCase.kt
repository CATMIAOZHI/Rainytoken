package com.rainy.token.domain.usecase

import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.repository.OpenCodeRequestLogs
import com.rainy.token.data.repository.OpenCodeUsageRepository
import com.rainy.token.data.repository.RepositoryError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** OpenCode request logs: one paginated snapshot per bearer-key scope. */
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
            val session = usageRepoProvider.get().openSession(scope)
            val cache = cacheProvider.get()
            val window = openCodeLogsWindow(
                now = System.currentTimeMillis(),
                incremental = incremental,
                checkpoint = syncCoordinator.openCodeLogsCheckpoint(scope)
            )
            Result.success(syncOpenCodeRequestLogs(
                window = window,
                fetchPage = session::fetchPage,
                insertRecords = { records ->
                    session.ensureCurrent()
                    // If the key changes during the database transaction, these rows are still
                    // isolated in the original scope; saveIfCurrent will reject the checkpoint.
                    cache.insertRequestLogs(records)
                },
                countRecords = { cache.count(scope) },
                saveCheckpoint = { until ->
                    session.saveIfCurrent {
                        syncCoordinator.saveOpenCodeLogsSuccess(scope, until, System.currentTimeMillis())
                    }
                }
            ))
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

private const val LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
private const val LOG_OVERLAP_MS = 60L * 60 * 1000

internal data class OpenCodeLogsWindow(val since: Long, val until: Long)

internal fun openCodeLogsWindow(
    now: Long, incremental: Boolean, checkpoint: Long
): OpenCodeLogsWindow {
    val until = now.coerceAtLeast(0)
    val floor = (until - LOG_RETENTION_MS).coerceAtLeast(0)
    // A missing or future checkpoint (e.g. the wall clock just moved back) is not a safe bound.
    // Use the completed snapshot, not the latest local row: partial writes can have newer
    // rows, and a dormant account can have no new rows for the entire retention window.
    val since = if (incremental && checkpoint > 0 && checkpoint <= until) {
        (checkpoint - LOG_OVERLAP_MS).coerceAtLeast(floor)
    } else floor
    return OpenCodeLogsWindow(since, until)
}

/** Iterate to a genuine bottom even after a page inserts nothing; later pages can fill holes. */
internal suspend fun syncOpenCodeRequestLogs(
    window: OpenCodeLogsWindow,
    fetchPage: suspend (since: Long, until: Long, cursor: String?) -> OpenCodeRequestLogs.Page,
    insertRecords: suspend (List<UsageRecord>) -> Int,
    countRecords: suspend () -> Int,
    saveCheckpoint: suspend (Long) -> Unit
): SyncResult {
    var cursor: String? = null
    var until = window.until
    val seenCursors = mutableSetOf<String>()
    var inserted = 0
    var scanned = 0
    var pages = 0
    do {
        currentCoroutineContext().ensureActive()
        val page = fetchPage(window.since, until, cursor)
        currentCoroutineContext().ensureActive()
        if (pages == 0 && page.until != null) {
            until = minOf(page.until, until)
            if (until < window.since) OpenCodeRequestLogs.malformed("OpenCode request-logs until precedes since")
        } else if (page.until != null && page.until != until) {
            OpenCodeRequestLogs.malformed("OpenCode request-logs window changed during pagination")
        }
        if (page.records.any { it.timeCreated !in window.since..until }) {
            OpenCodeRequestLogs.malformed("OpenCode request logs fall outside the requested window")
        }
        val next = page.nextCursor
        if (next != null && !seenCursors.add(next)) {
            OpenCodeRequestLogs.malformed("OpenCode request-logs cursor repeated")
        }
        if (page.records.isNotEmpty()) inserted += insertRecords(page.records)
        scanned += page.records.size
        pages++
        cursor = next
    } while (cursor != null)
    val result = SyncResult(inserted = inserted, totalCount = countRecords(), scanned = scanned, pages = pages)
    currentCoroutineContext().ensureActive()
    saveCheckpoint(until)
    return result
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