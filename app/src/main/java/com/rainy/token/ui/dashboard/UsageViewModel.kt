package com.rainy.token.ui.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.R
import com.rainy.token.data.local.OverviewStats
import com.rainy.token.data.local.ModelStats
import com.rainy.token.data.local.DailyStats
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.data.repository.OpenCodeUsageRepository
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.SyncError
import com.rainy.token.domain.usecase.SyncResult
import com.rainy.token.domain.usecase.SyncUsageUseCase
import com.rainy.token.domain.usecase.UsageSyncCoordinator
import com.rainy.token.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
internal fun usageTimeFilters(): List<TimeFilter> = listOf(
    TimeFilter.All, TimeFilter.Last5h, TimeFilter.Last24h, TimeFilter.Today,
    TimeFilter.Yesterday, TimeFilter.Last7Days, TimeFilter.Last30Days, TimeFilter.ThisMonth
)
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
    fun toRange(
        now: Long = System.currentTimeMillis(),
        zoneOffset: ZoneOffset = ZoneOffset.UTC
    ): Pair<Long?, Long?> {
        val localDate = Instant.ofEpochMilli(now).atOffset(zoneOffset).toLocalDate()
        val todayMidnight = localDate.atStartOfDay(zoneOffset).toInstant().toEpochMilli()
        return when (this) {
            is All -> null to null
            is Last5h -> now - 5 * 3600_000L to null
            is Last24h -> now - 24 * 3600_000L to null
            is Today -> todayMidnight to todayMidnight + 86400_000L - 1
            is Yesterday -> todayMidnight - 86400_000L to todayMidnight - 1
            is Last7Days -> todayMidnight - 6 * 86400_000L to todayMidnight + 86400_000L - 1
            is Last30Days -> todayMidnight - 29 * 86400_000L to todayMidnight + 86400_000L - 1
            is ThisMonth -> {
                val monthStart = localDate.withDayOfMonth(1).atStartOfDay(zoneOffset).toInstant().toEpochMilli()
                val monthEnd = localDate.withDayOfMonth(localDate.lengthOfMonth()).plusDays(1).atStartOfDay(zoneOffset).toInstant().toEpochMilli() - 1
                monthStart to monthEnd
            }
            is Custom -> if (from == 0L && to == 0L) null to null else from to to
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
    private var statsJob: Job? = null
    private var displayedGoScope: String? = null
    private var goScopeObserver: Job? = null
    private var activeGoSync: Pair<String, Job>? = null

    fun observeGoUsageScope() {
        if (workspaceIdOverride != null || goScopeObserver?.isActive == true) return
        goScopeObserver = viewModelScope.launch {
            credentialRepository.openCodeUsageScopeChanges.collect { scope ->
                if (workspaceIdOverride != null) return@collect
                activeGoSync?.takeIf { (runningScope, _) -> runningScope != scope }?.second?.cancel()
                showGoScope(scope)
                loadStats()
            }
        }
    }

    private fun showGoScope(scope: String?) {
        if (displayedGoScope != scope) {
            displayedGoScope = scope
            _uiState.value = UsageUiState(scopeGeneration = _uiState.value.scopeGeneration + 1)
        }
    }

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
        goScopeObserver?.cancel()
        // 先清空数据防止 init 自动加载的 OCGO 数据闪一下
        _uiState.value = UsageUiState()
        loadStats()
    }

    // 不在 init 自动加载，由 Composable 层显式触发 loadStats()（OCGO）或 setWorkspace()（CCGO）

    private suspend fun cacheScope(): String? {
        workspaceIdOverride?.let { return it }
        return OpenCodeUsageRepository.cacheScope(credentialRepository.get(ServiceType.OPENCODE_GO))
    }

    fun loadStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch { loadStatsInternal() }
    }

    /** 只调一次 getRecords()，所有统计从同一次结果派生。重 IO/CPU 操作跑在 Default 上。 */
    private suspend fun loadStatsInternal() {
        val wid = cacheScope()
        if (workspaceIdOverride == null) showGoScope(wid)
        if (wid == null) {
            _uiState.update { it.copy(loading = false, hasGoApiKey = false) }
            return
        }
        val cache = cacheProvider.get()
        val filter = _uiState.value.timeFilter
        val (fromTs, toTs) = filter.toRange()
        val model = _uiState.value.modelFilter
        val isGo = workspaceIdOverride == null

        // ★ 全部重操作放后台线程，避免阻塞 Compose 动画帧
        val (overview, modelStats, dailyStats, totalCount) = withContext(Dispatchers.Default) {
            val records = cache.getRecords(wid, fromTs = fromTs, toTs = toTs)
            val filtered = if (model != null) records.filter { it.model == model } else records

            val ov = if (filtered.isEmpty()) null else OverviewStats(
                totalTokens = if (isGo) filtered.sumOf { it.totalTokens }
                    else filtered.sumOf { it.inputTokens + it.cacheReadTokens + it.outputTokens },
                totalCost = filtered.sumOf { it.cost ?: 0.0 },
                totalCount = filtered.sumOf { it.requestCount },
                modelCount = filtered.map { it.model }.distinct().size,
                inputTokens = filtered.sumOf { it.inputTokens },
                outputTokens = filtered.sumOf { it.outputTokens },
                reasoningTokens = filtered.sumOf { it.reasoningTokens },
                cacheReadTokens = filtered.sumOf { it.cacheReadTokens },
                cacheWrite5mTokens = filtered.sumOf { it.cacheWrite5mTokens },
                cacheWrite1hTokens = filtered.sumOf { it.cacheWrite1hTokens },
                cacheWriteTokensUnspecified = filtered.sumOf { it.cacheWriteTokensUnspecified ?: 0L },
                pricedRequests = filtered.pricedRequests()
            )

            val ms = filtered.groupBy { it.model }.map { (m, recs) ->
                ModelStats(model = m, totalTokens = recs.sumOf { it.totalTokens }, totalCost = recs.sumOf { it.cost ?: 0.0 }, count = recs.sumOf { it.requestCount }, pricedRequests = recs.pricedRequests())
            }.sortedByDescending { it.totalTokens }

            val ds = filtered.groupBy { it.timeCreated / 86_400_000L * 86_400_000L }
                .map { (dayTs, recs) ->
                    com.rainy.token.data.local.DailyStats(dayTs = dayTs, totalTokens = recs.sumOf { it.totalTokens }, totalCost = recs.sumOf { it.cost ?: 0.0 }, count = recs.sumOf { it.requestCount }, pricedRequests = recs.pricedRequests())
                }.sortedByDescending { it.dayTs }

            LoadResult(ov, ms, ds, records.size)
        }

        val currentScope = cacheScope()
        if (currentScope != wid) {
            if (workspaceIdOverride == null) showGoScope(currentScope)
            loadStats()
            return
        }

        _uiState.update {
            it.copy(
                overview = overview,
                modelStats = modelStats,
                dailyStats = dailyStats,
                recordCount = totalCount,
                loading = false,
                hasGoApiKey = workspaceIdOverride == null,
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

    /** Short refresh and pull are incremental for Go; keep CommandCode's existing manual mode. */
    fun sync() {
        if (workspaceIdOverride != null) syncCcgo(incremental = false) else syncGo(full = false)
    }

    fun refreshGoWindow() {
        if (workspaceIdOverride == null) syncGo(full = true)
    }

    private fun syncGo(full: Boolean, staleOnly: Boolean = false) {
        viewModelScope.launch {
            val scope = cacheScope()
            showGoScope(scope)
            if (scope == null) {
                _uiState.update { it.copy(loading = false) }
                return@launch
            }
            if (staleOnly && !syncCoordinator.isSyncStale(scope, UsageSyncCoordinator.FOREGROUND_MIN_INTERVAL_MS)) {
                return@launch
            }
            activeGoSync?.let { (runningScope, runningJob) ->
                if (runningScope == scope && runningJob.isActive) return@launch
                runningJob.cancel()
            }
            val job = checkNotNull(currentCoroutineContext()[Job])
            activeGoSync = scope to job
            _uiState.update { it.copy(syncing = true, lastSyncError = null, lastSyncResult = 0) }
            try {
                val result = withContext(Dispatchers.Default) {
                    val useCase = syncUseCaseProvider.get()
                    if (full) useCase.fullSync(scope) else useCase.incrementalSync(scope)
                }
                val currentScope = cacheScope()
                if (currentScope != scope || displayedGoScope != scope) {
                    showGoScope(currentScope)
                    loadStats()
                    return@launch
                }
                if (activeGoSync?.second !== job) return@launch
                _uiState.update {
                    it.copy(
                        syncing = false,
                        lastSyncResult = result.getOrNull()?.let { it.inserted + it.updated + it.deleted } ?: 0,
                        lastSyncError = result.exceptionOrNull()?.let(::syncErrorToUiText)
                    )
                }
                loadStats()
            } finally {
                if (activeGoSync?.second === job) activeGoSync = null
            }
        }
    }
    private fun syncCcgo(incremental: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true) }
            val wid = workspaceIdOverride ?: return@launch
            val result = withContext(Dispatchers.Default) {
                syncCoordinator.syncCcgoUsage(incremental = incremental)
            }
            val error = result.exceptionOrNull()
            // 其它页面 / Worker 正在同步时，按钮继续由协调器的 busy 状态驱动。
            if (error is SyncError.AlreadyRunning) {
                _uiState.update { it.copy(syncing = syncCoordinator.busy.value) }
                return@launch
            }
            loadStats()
            _uiState.update {
                it.copy(
                    syncing = false,
                    lastSyncResult = syncInsertedCount(result),
                    lastSyncError = error?.let(::syncErrorToUiText),
                    lastSyncAt = if (result.isSuccess) syncCoordinator.lastSyncAt(wid) else it.lastSyncAt
                )
            }
        }
    }

    fun syncIfStale() {
        val wid = workspaceIdOverride
        if (wid != null) {
            if (syncCoordinator.isSyncStale(wid, UsageSyncCoordinator.FOREGROUND_MIN_INTERVAL_MS)) {
                syncCcgo(incremental = true)
            }
            return
        }
        // The first Go import is explicit; only a previously synced scope auto-refreshes.
        viewModelScope.launch {
            val scope = cacheScope() ?: return@launch
            if (syncCoordinator.lastSyncAt(scope) > 0) syncGo(full = false, staleOnly = true)
        }
    }
}

private fun List<UsageRecord>.pricedRequests(): Long =
    sumOf { if (it.cost != null) it.requestCount else 0L }

/** 同步结果里的新增条数：成功取 result，部分失败时从 PartialSync 异常里取。 */
private fun syncInsertedCount(result: Result<SyncResult>): Int =
    result.getOrNull()?.inserted
        ?: (result.exceptionOrNull() as? SyncError.PartialSync)?.inserted
        ?: 0

data class UsageUiState(
    val loading: Boolean = true,
    val scopeGeneration: Int = 0,
    val hasGoApiKey: Boolean = false,
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
    is SyncError.AlreadyRunning -> UiText.Resource(R.string.go_usage_already_syncing)
    is SyncError.PartialSync ->
        UiText.Resource(R.string.sync_partial, listOf(error.inserted, error.errors.size))
    is RepositoryError.InvalidCredential ->
        UiText.Resource(R.string.error_credential_invalid_reconfigure)
    is RepositoryError.CredentialChanged ->
        UiText.Resource(R.string.error_credential_changed_reload)
    is RepositoryError.OpenCodeUsageForbidden -> when (error.reason) {
        RepositoryError.OpenCodeUsageForbiddenReason.MISSING_PERMISSION -> UiText.Resource(R.string.error_opencode_usage_permission)
        RepositoryError.OpenCodeUsageForbiddenReason.NOT_OWNER_OR_ADMIN -> UiText.Resource(R.string.error_opencode_usage_role)
        RepositoryError.OpenCodeUsageForbiddenReason.UNSPECIFIED -> UiText.Resource(R.string.error_opencode_usage_forbidden)
    }
    is RepositoryError.OpenCodeUsageBadRequest -> UiText.Resource(R.string.error_opencode_usage_bad_request)
    is RepositoryError.OpenCodeUsageSummaryUnavailable -> UiText.Resource(R.string.error_opencode_usage_unavailable)
    is RepositoryError.OpenCodeGoSubscriptionRequired -> UiText.Resource(R.string.error_opencode_go_subscription_required)
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