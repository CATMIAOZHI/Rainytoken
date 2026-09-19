package com.rainy.token.ui.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.R
import com.rainy.token.data.local.OverviewStats
import com.rainy.token.data.local.ModelStats
import com.rainy.token.data.local.DailyStats
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.SyncError
import com.rainy.token.domain.usecase.SyncResult
import com.rainy.token.domain.usecase.SyncUsageUseCase
import com.rainy.token.domain.usecase.UsageSyncCoordinator
import com.rainy.token.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Provider

internal const val DAILY_PAGE_SIZE = 5

/** 时间筛选条件（label 用字符串资源 ID，UI 层按当前语言解析） */
sealed class TimeFilter(@StringRes val labelRes: Int) {
    data object All : TimeFilter(R.string.time_all)
    data object Last5h : TimeFilter(R.string.time_last_5h)
    data object Last24h : TimeFilter(R.string.time_last_24h)
    data object Today : TimeFilter(R.string.time_today)
    data object Yesterday : TimeFilter(R.string.time_yesterday)
    data object Last7Days : TimeFilter(R.string.time_last_7d)
    data object Last30Days : TimeFilter(R.string.time_last_30d)
    data object ThisMonth : TimeFilter(R.string.time_this_month)
    data class Custom(val from: Long, val to: Long) : TimeFilter(R.string.time_custom)

    /** 计算筛选的起止 epoch 毫秒（from 含，to 含）。
     *  返回 Pair(null, null) 表示不限制。 */
    fun toRange(now: Long = System.currentTimeMillis()): Pair<Long?, Long?> {
        val todayMidnight = midnightBefore(now)
        return when (this) {
            is All -> null to null
            is Last5h -> now - 5 * 3600_000L to null
            is Last24h -> now - 24 * 3600_000L to null
            is Today -> todayMidnight to todayMidnight + 86400_000L - 1
            is Yesterday -> todayMidnight - 86400_000L to todayMidnight - 1
            is Last7Days -> todayMidnight - 6 * 86400_000L to todayMidnight + 86400_000L - 1
            is Last30Days -> todayMidnight - 29 * 86400_000L to todayMidnight + 86400_000L - 1
            is ThisMonth -> {
                val localDate = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate()
                val monthStart = localDate.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val monthEnd = localDate.withDayOfMonth(localDate.lengthOfMonth()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
                monthStart to monthEnd
            }
            is Custom -> if (from == 0L && to == 0L) null to null else from to to
        }
    }

    companion object {
        private fun midnightBefore(ts: Long): Long {
            val utc = ZoneOffset.UTC
            val localDate = Instant.ofEpochMilli(ts).atOffset(utc).toLocalDate()
            return localDate.atStartOfDay(utc).toInstant().toEpochMilli()
        }
    }
}

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val cacheProvider: Provider<UsageCache>,
    private val syncUseCaseProvider: Provider<SyncUsageUseCase>,
    private val credentialRepository: CredentialRepository,
    private val syncCoordinator: UsageSyncCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    private var workspaceIdOverride: String? = null
    private var loadGeneration = 0  // 递增：过时的 loadStatsInternal 结果自动丢弃

    init {
        // 跨页面共享的同步状态（进程级单飞）：协调器在跑时，CCGO 各页面的刷新按钮一起转圈；
        // 后台 Worker 完成同步（busy 由真→假）时补一次数据重载，否则界面会停在旧数字上。
        viewModelScope.launch {
            var wasBusy = false
            syncCoordinator.busy.collect { busy ->
                if (workspaceIdOverride == com.rainy.token.data.repository
                        .CommandCodeUsageRepository.CCGO_WORKSPACE_ID
                ) {
                    _uiState.update { it.copy(syncing = busy) }
                    if (wasBusy && !busy) loadStats()
                }
                wasBusy = busy
            }
        }
    }

    // 同步时间与后台同步开关统一由 [UsageSyncCoordinator] 持久化（沿用原 prefs：usage_sync_at / sync_at_<wid>）

    /** 覆盖 workspaceId，用于 CCGO 等非 OCGO 服务。必须在 loadStats() 前调用。 */
    fun setWorkspace(wid: String) {
        // 幂等：进入 / 回到前台会重复调用，同值不重置状态，避免卡片闪一下空数据
        if (workspaceIdOverride == wid) return
        workspaceIdOverride = wid
        loadGeneration++
        // 先清空数据防止 init 自动加载的 OCGO 数据闪一下
        _uiState.value = UsageUiState()
        loadStats()
    }

    // 不在 init 自动加载，由 Composable 层显式触发 loadStats()（OCGO）或 setWorkspace()（CCGO）

    private suspend fun workspaceId(): String? {
        workspaceIdOverride?.let { return it }
        val c = credentialRepository.get(ServiceType.OPENCODE_GO)
        return (c as? Credential.SessionCredential)?.workspaceId?.takeIf { it.isNotBlank() }
    }

    fun loadStats() {
        viewModelScope.launch { loadStatsInternal() }
    }

    /** 只调一次 getRecords()，所有统计从同一次结果派生。重 IO/CPU 操作跑在 Default 上。 */
    private suspend fun loadStatsInternal() {
        val wid = workspaceId() ?: return
        val genAtStart = loadGeneration  // 记下发起时的世代
        val cache = cacheProvider.get()
        val filter = _uiState.value.timeFilter
        val (fromTs, toTs) = filter.toRange()
        val model = _uiState.value.modelFilter

        // ★ 全部重操作放后台线程，避免阻塞 Compose 动画帧
        val (overview, modelStats, dailyStats, totalCount) = withContext(Dispatchers.Default) {
            val records = cache.getRecords(wid, fromTs = fromTs, toTs = toTs)
            val filtered = if (model != null) records.filter { it.model == model } else records

            val ov = if (filtered.isEmpty()) null else OverviewStats(
                totalTokens = filtered.sumOf { it.inputTokens + it.cacheReadTokens + it.outputTokens },
                totalCost = filtered.sumOf { it.cost },
                totalCount = filtered.size,
                modelCount = filtered.map { it.model }.distinct().size,
                inputTokens = filtered.sumOf { it.inputTokens },
                outputTokens = filtered.sumOf { it.outputTokens },
                reasoningTokens = filtered.sumOf { it.reasoningTokens },
                cacheReadTokens = filtered.sumOf { it.cacheReadTokens },
                cacheWrite5mTokens = filtered.sumOf { it.cacheWrite5mTokens },
                cacheWrite1hTokens = filtered.sumOf { it.cacheWrite1hTokens }
            )

            val ms = filtered.groupBy { it.model }.map { (m, recs) ->
                ModelStats(model = m, totalTokens = recs.sumOf { it.totalTokens }, totalCost = recs.sumOf { it.cost }, count = recs.size)
            }.sortedByDescending { it.totalTokens }

            val ds = filtered.groupBy { it.timeCreated / 86_400_000L * 86_400_000L }
                .map { (dayTs, recs) ->
                    com.rainy.token.data.local.DailyStats(dayTs = dayTs, totalTokens = recs.sumOf { it.totalTokens }, totalCost = recs.sumOf { it.cost }, count = recs.size)
                }.sortedByDescending { it.dayTs }

            LoadResult(ov, ms, ds, records.size)
        }

        // 如果 loadGeneration 已经变化（setWorkspace 被调用），丢弃这次结果
        if (loadGeneration != genAtStart) return

        _uiState.update {
            it.copy(
                overview = overview,
                modelStats = modelStats,
                dailyStats = dailyStats,
                recordCount = totalCount,
                loading = false,
                dailyPage = 1,
                lastSyncAt = syncCoordinator.lastSyncAt(wid),
                syncStale = syncCoordinator.isSyncStale(wid)
            )
        }
    }

    fun setModelFilter(model: String?) {
        _uiState.update { it.copy(modelFilter = model, dailyPage = 1) }
        loadStats()
    }

    fun setTimeFilter(filter: TimeFilter) {
        _uiState.update { it.copy(timeFilter = filter, dailyPage = 1) }
        // Custom(0,0) 是占位初始态，等用户选好时间再 loadStats
        if (filter !is TimeFilter.Custom || (filter.from != 0L || filter.to != 0L)) {
            loadStats()
        }
    }

    fun prevDailyPage() {
        _uiState.update { it.copy(dailyPage = (it.dailyPage - 1).coerceAtLeast(1)) }
    }

    fun nextDailyPage() {
        val total = _uiState.value.dailyStats.size
        val totalPages = (total + DAILY_PAGE_SIZE - 1) / DAILY_PAGE_SIZE
        _uiState.update { it.copy(dailyPage = (it.dailyPage + 1).coerceAtMost(totalPages)) }
    }

    /**
     * 手动同步（刷新按钮 / 下拉刷新）：默认把服务端窗口拉满，能把窗口内的空洞补齐。
     *
     * 自动路径（[syncIfStale]、后台 Worker）传 [incremental] = true 走增量（整页命中即停），省请求。
     */
    fun sync(incremental: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true) }
            val wid = workspaceIdOverride ?: workspaceId()
            val isCcgo = workspaceIdOverride == com.rainy.token.data.repository
                .CommandCodeUsageRepository.CCGO_WORKSPACE_ID

            val result = withContext(Dispatchers.Default) {
                if (isCcgo) {
                    syncCoordinator.syncCcgoUsage(incremental = incremental)
                } else {
                    // OCGO：保持既有分页同步（服务端窗口与游标语义不同，本次不动）
                    val useCase = syncUseCaseProvider.get()
                    val cache = cacheProvider.get()
                    if (cache.count() == 0) useCase.fullSync() else useCase.incrementalSync()
                }
            }

            val error = result.exceptionOrNull()
            // 已有同步在跑（其它页面 / 后台 Worker）：不清空上次结果，按钮状态交给协调器的 busy 驱动。
            if (error is SyncError.AlreadyRunning) {
                // 极小概率竞态：busy 刚转 false 又被判定为「正在跑」，此时不会有人再复位 syncing
                _uiState.update { it.copy(syncing = syncCoordinator.busy.value) }
                return@launch
            }

            // OCGO 不走协调器，成功时间要在这里补写（否则卡片的「更新于 X」会永远停在旧值）
            if (result.isSuccess && !isCcgo && wid != null) {
                syncCoordinator.saveLastSyncAt(wid, System.currentTimeMillis())
            }

            // 失败（尤其 PartialSync）时通常也已经写入了一部分记录：必须重新加载，
            // 否则界面停在旧数据上，用户会以为「刷新没生效」。
            // 这里无条件刷新（多一次全表统计读，代价很小）；不依赖 busy 跳变，避免 StateFlow 合并导致漏刷新。
            loadStats()

            _uiState.update {
                it.copy(
                    syncing = false,
                    lastSyncResult = syncInsertedCount(result),
                    lastSyncError = error?.let { syncErrorToUiText(it) },
                    lastSyncAt = if (result.isSuccess && wid != null) syncCoordinator.lastSyncAt(wid)
                    else it.lastSyncAt
                )
            }
        }
    }

    /**
     * 进入 / 回到前台时的自动同步（主力防线）。
     *
     * 距上次成功同步超过 [UsageSyncCoordinator.FOREGROUND_MIN_INTERVAL_MS]（8 小时）才真正发起，
     * 避免每次切页面都打网络；服务端明细窗口约 24h，这一层保证「每天打开过就不会丢」。
     * 走增量同步（整页命中即停），省请求；补洞交给用户主动点的「立即同步」。
     */
    fun syncIfStale() {
        val wid = workspaceIdOverride ?: return
        if (syncCoordinator.isSyncStale(wid, UsageSyncCoordinator.FOREGROUND_MIN_INTERVAL_MS)) {
            sync(incremental = true)
        }
    }
}

/** 同步结果里的新增条数：成功取 result，部分失败时从 PartialSync 异常里取（旧实现失败一律显示 0）。 */
private fun syncInsertedCount(result: Result<SyncResult>): Int =
    result.getOrNull()?.inserted
        ?: (result.exceptionOrNull() as? SyncError.PartialSync)?.inserted
        ?: 0

data class UsageUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val overview: OverviewStats? = null,
    val modelStats: List<ModelStats> = emptyList(),
    val dailyStats: List<DailyStats> = emptyList(),
    val recordCount: Int = 0,
    val lastSyncResult: Int = 0,
    val lastSyncError: UiText? = null,
    val lastSyncAt: Long = 0,  // 最近一次成功同步的 epoch ms（0 = 从未同步）
    /** 距上次成功同步已超过窗口守护阈值：数据可能过期，UI 给出提示 */
    val syncStale: Boolean = false,
    val timeFilter: TimeFilter = TimeFilter.All,
    val dailyPage: Int = 1,
    val modelFilter: String? = null  // null = 全部模型
)

/** withContext 返回四元组 */
private data class LoadResult(
    val overview: OverviewStats?,
    val modelStats: List<ModelStats>,
    val dailyStats: List<DailyStats>,
    val totalCount: Int
)

/** 把同步错误映射为本地化文案：SyncError / RepositoryError 全部走资源，不透传中文 message。 */
private fun syncErrorToUiText(error: Throwable): UiText = when (error) {
    is SyncError.PartialSync ->
        UiText.Resource(R.string.sync_partial, listOf(error.inserted, error.errors.size))
    is RepositoryError.InvalidCredential ->
        UiText.Resource(R.string.error_credential_invalid_reconfigure)
    is RepositoryError.CredentialChanged ->
        UiText.Resource(R.string.error_credential_changed_reload)
    is RepositoryError.RateLimited -> UiText.Resource(
        R.string.error_rate_limited_retry,
        listOf(
            error.retryAfterSeconds?.let {
                UiText.Resource(R.string.error_rate_limited_retry_suffix, listOf(it))
            } ?: ""
        )
    )
    is RepositoryError.Network -> UiText.Resource(R.string.error_network_check)
    is RepositoryError.ServerError ->
        UiText.Resource(R.string.error_server_http, listOf(error.code))
    is RepositoryError.ParseError -> when (error.reason) {
        RepositoryError.ParseErrorReason.EMPTY_BODY -> UiText.Resource(R.string.error_parse_empty_body)
        RepositoryError.ParseErrorReason.NOT_JSON_OBJECT -> UiText.Resource(R.string.error_parse_not_json)
        RepositoryError.ParseErrorReason.NO_WINDOWS -> UiText.Resource(R.string.error_parse_no_windows)
        RepositoryError.ParseErrorReason.NO_MODELS -> UiText.Resource(R.string.error_parse_no_models)
        RepositoryError.ParseErrorReason.MODELS_EMPTY -> UiText.Resource(R.string.error_parse_models_empty)
        RepositoryError.ParseErrorReason.MALFORMED_RESPONSE -> UiText.Resource(R.string.error_parse_malformed)
    }
    is RepositoryError.Unknown -> UiText.Resource(R.string.common_unknown)
    else -> UiText.Resource(R.string.common_unknown)
}