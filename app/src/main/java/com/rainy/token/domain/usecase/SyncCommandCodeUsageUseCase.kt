package com.rainy.token.domain.usecase

import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.repository.CommandCodeUsageRepository
import javax.inject.Inject
import javax.inject.Provider

/**
 * CommandCode 用量同步 UseCase。
 *
 * 游标协议：每页返回 (记录列表, 下一页游标)。
 * - cursor=null → 最新页
 * - 服务端 nextCursor 缺失时按末条自编码回退；「是否到底」以服务端原始条数为准
 *
 * ## 三种同步的分工
 * - [refreshWindow] 立即同步（用户主动点刷新）：从 cursor=null 连续向前翻，拉满服务端窗口，
 *   不把「本地已有」当停止信号，能补齐窗口内被服务端补录 / 曾漏掉的记录；
 * - [incrementalSync] 增量（自动路径：回到前台 / 8h 后台任务）：**整页记录本地已存在即停**，
 *   健康状态下 1 个请求即可，省请求，但探不到窗口内的空洞；
 * - [fullSync] 全量：逐页抓到服务端没有更多。
 *
 * 注意：「整页命中即停」只说明最新一页本地已有，**不代表窗口完整**——补洞要用 [refreshWindow]。
 */
class SyncCommandCodeUsageUseCase @Inject constructor(
    private val usageRepoProvider: Provider<CommandCodeUsageRepository>,
    private val cacheProvider: Provider<UsageCache>
) {
    private companion object {
        /** 全量拉取的防御性页数上限：防止游标异常导致死循环 */
        const val MAX_PAGES = 500

        /** 立即同步（refreshWindow）的页数上限：100 条/页 → 5000 条；服务端 24h 窗口远小于此 */
        const val MAX_WINDOW_PAGES = 50
    }

    suspend fun fullSync(): Result<SyncResult> {
        val repo = usageRepoProvider.get()
        val cache = cacheProvider.get()
        var cursor: String? = null
        var totalInserted = 0
        val errors = mutableListOf<String>()
        var pages = 0

        while (true) {
            val pageResult = repo.fetchPage(cursor)
            if (pageResult.isFailure) {
                errors.add("cursor=${cursor?.take(20)}: ${pageResult.exceptionOrNull()?.message}")
                break
            }
            val page = pageResult.getOrThrow()
            if (page.records.isEmpty() && page.rawCount > 0) {
                // 服务端有数据、但整页都解析失败：不能当作「到底」，否则会静默漏掉整页
                errors.add("parse anomaly: raw=${page.rawCount} dropped=${page.droppedCount}")
                break
            }
            if (page.records.isEmpty()) break
            if (page.nextCursor == cursor) break // 游标未前进，防死循环

            // 精确计数：DAO 的 IGNORE 策略下已存在的行返回 -1；
            // 旧实现用全表 count() 差值，会被并发的其它服务同步污染。
            totalInserted += cache.insertAll(page.records)

            cursor = page.nextCursor
            if (cursor == null) break // 服务端没有更多（正常到底）
            if (++pages >= MAX_PAGES) {
                // 触顶 = 行走被截断，不能算成功：旧实现静默返回 success，会把空洞伪装成「已同步」
                errors.add("page limit $MAX_PAGES reached")
                break
            }
        }

        return if (errors.isEmpty()) Result.success(
            SyncResult(inserted = totalInserted, pages = pages, stopReason = SyncStopReason.BOTTOM)
        ) else Result.failure(SyncError.PartialSync(totalInserted, errors))
    }

    /**
     * 立即同步（用户主动触发）：把服务端当前窗口内的记录一次性拉满。
     *
     * 与 [incrementalSync] 的分工：这里**不把「本地已有」当作停止信号**，
     * 因此能补上窗口内被服务端补录 / 曾漏掉的记录（代价是多几个请求）。
     * 服务端只保留最近约 24 小时的明细（滚动窗口），窗口之外的历史无法回补。
     * 从最新页连续向前翻，直到：服务端没有更多 / 游标不前进 / 触顶 / 出错。
     */
    suspend fun refreshWindow(): Result<SyncResult> {
        val repo = usageRepoProvider.get()
        val cache = cacheProvider.get()
        var cursor: String? = null
        var inserted = 0
        var scanned = 0
        var dropped = 0
        var pages = 0
        val errors = mutableListOf<String>()
        var stop = SyncStopReason.BOTTOM

        while (true) {
            val pageResult = repo.fetchPage(cursor)
            if (pageResult.isFailure) {
                val cause = pageResult.exceptionOrNull()
                // 首页（cursor 为 null）就失败：此时一条记录都还没写入，直接把原异常冒泡——
                // UI 能显示精确文案（凭据失效 / 网络异常 / 服务端错误 / 限流），
                // 后台 Worker 也能据此判断该不该重试。否则会被 PartialSync 吞成「部分同步」。
                if (cursor == null && cause != null) {
                    return Result.failure(cause)
                }
                errors.add("cursor=${cursor?.take(20)}: ${cause?.message}")
                stop = SyncStopReason.PAGE_ERROR
                break
            }

            val page = pageResult.getOrThrow()
            pages++
            scanned += page.rawCount
            dropped += page.droppedCount

            if (page.records.isEmpty() && page.rawCount > 0) {
                // 服务端有数据但整页解析失败：不当作「到底」，而是显式中断并上报
                stop = SyncStopReason.PARSE_ANOMALY
                break
            }

            inserted += cache.insertAll(page.records)

            val next = page.nextCursor
            if (next == null) { stop = SyncStopReason.BOTTOM; break }
            if (next == cursor) { stop = SyncStopReason.CURSOR_STUCK; break }
            if (pages >= MAX_WINDOW_PAGES) { stop = SyncStopReason.MAX_PAGES; break }
            cursor = next
        }

        val result = SyncResult(
            inserted = inserted,
            scanned = scanned,
            dropped = dropped,
            pages = pages,
            stopReason = stop
        )
        // 只有「真追平服务端窗口」才算成功：被页数上限截断、服务端数据解析异常、游标卡住
        // 都属于「没拉完」，必须上报——否则协调器会刷新 lastSyncAt、把窗口守护清掉，
        // 用户看到的是「同步成功、新增 0 条」，洞却被悄悄留着。
        // 部分解析失败：只有丢失显著（≥10 条，或 ≥ 本次扫描的一半）才按「数据没拿全」上报。
        // 偶发个别脏记录不足以让整次同步失败——否则 lastSyncAt 会永久冻住、窗口守护常挂、后台还会无限重试。
        val droppedSignificantly = dropped >= 10 || (scanned > 0 && dropped * 2 >= scanned)
        val abnormal = when {
            stop == SyncStopReason.MAX_PAGES -> "page limit $MAX_WINDOW_PAGES reached"
            stop == SyncStopReason.PARSE_ANOMALY -> "parse anomaly: dropped=$dropped"
            stop == SyncStopReason.CURSOR_STUCK -> "cursor did not advance"
            droppedSignificantly -> "dropped $dropped of $scanned record(s) while parsing"
            else -> null
        }
        return if (errors.isEmpty() && abnormal == null) Result.success(result)
        else Result.failure(
            SyncError.PartialSync(inserted, errors + listOfNotNull(abnormal))
        )
    }

    /**
     * 增量同步（自动同步用）：从最新页向前翻，**某页记录本地全部已存在时停止**。
     *
     * 保留这个启发式的理由：健康状态下只要 1 个请求就能确认「已接到已知数据」，
     * 前台与后台每天会触发多次，这是成本最低的做法。
     *
     * 与 [refreshWindow] 的分工：
     *  - 增量（本方法）：自动路径（回到前台、8h 后台任务）——省请求，但不保证填洞；
     *  - 窗口拉满：[refreshWindow] ——用户主动点「立即同步」时用，多几个请求换「窗口内补齐」。
     *
     * 与旧实现的差别（旧实现的两个缺陷）：
     *  1. 不再用「解析后条数」判断满页/到底 —— 一条记录解析失败曾会导致整页被当成已知数据；
     *  2. 命中即停会作为 [SyncStopReason.HIT_EXISTING] 上报，不再与「窗口真拉完」混为一谈。
     *
     * 注意：命中即停只说明「最新一页本地已有」，**不代表窗口内没有空洞**；它不算失败（返回 success），
     * 但也不应被当作「服务端窗口已拉完」——补洞要靠 [refreshWindow]。
     */
    suspend fun incrementalSync(): Result<SyncResult> {
        val repo = usageRepoProvider.get()
        val cache = cacheProvider.get()
        var cursor: String? = null
        var inserted = 0
        var scanned = 0
        var dropped = 0
        var pages = 0
        val errors = mutableListOf<String>()
        var stop = SyncStopReason.BOTTOM
        // 行程开始前取一次本地已有 ID（避免每页重复扫全表；本行程新插入的 id 不会再出现在后续页）
        val existingIds = cache.getIdsByWorkspace(CommandCodeUsageRepository.CCGO_WORKSPACE_ID)

        while (true) {
            val pageResult = repo.fetchPage(cursor)
            if (pageResult.isFailure) {
                val cause = pageResult.exceptionOrNull()
                // 首页失败：直接冒泡原异常（UI 文案精确，后台可据此判断是否值得重试）
                if (cursor == null && cause != null) return Result.failure(cause)
                errors.add("cursor=${cursor?.take(20)}: ${cause?.message}")
                stop = SyncStopReason.PAGE_ERROR
                break
            }

            val page = pageResult.getOrThrow()
            pages++
            scanned += page.rawCount
            dropped += page.droppedCount

            if (page.records.isEmpty() && page.rawCount > 0) {
                // 服务端有数据但整页解析失败：按异常处理，不停成「已接到旧数据」
                stop = SyncStopReason.PARSE_ANOMALY
                break
            }

            // 整页命中判定基于服务端原始条数：个别记录解析失败不应让整页被误判为「已存在」
            val newRecords = page.records.filter { it.id !in existingIds }

            if (page.rawCount > 0 && newRecords.isEmpty()) {
                // 整页都是已知数据 → 已接到旧数据，停止（保留既有启发式）
                stop = SyncStopReason.HIT_EXISTING
                break
            }

            inserted += cache.insertAll(newRecords)

            val next = page.nextCursor
            if (next == null) { stop = SyncStopReason.BOTTOM; break }
            if (next == cursor) { stop = SyncStopReason.CURSOR_STUCK; break }
            if (pages >= MAX_WINDOW_PAGES) { stop = SyncStopReason.MAX_PAGES; break }
            cursor = next
        }

        val result = SyncResult(
            inserted = inserted,
            scanned = scanned,
            dropped = dropped,
            pages = pages,
            stopReason = stop
        )
        // 命中即停 / 真追平都算成功；只有被截断、显著解析丢失或遇到异常才上报失败。
        // （个别脏记录不足以让整次同步失败，否则 lastSyncAt 会冻住、窗口守护常挂、后台会无限重试）
        val droppedSignificantly = dropped >= 10 || (scanned > 0 && dropped * 2 >= scanned)
        val abnormal = when {
            stop == SyncStopReason.MAX_PAGES -> "page limit $MAX_WINDOW_PAGES reached"
            stop == SyncStopReason.PARSE_ANOMALY -> "parse anomaly: dropped=$dropped"
            stop == SyncStopReason.CURSOR_STUCK -> "cursor did not advance"
            droppedSignificantly -> "dropped $dropped of $scanned record(s) while parsing"
            else -> null
        }
        return if (errors.isEmpty() && abnormal == null) Result.success(result)
        else Result.failure(
            SyncError.PartialSync(inserted, errors + listOfNotNull(abnormal))
        )
    }
}