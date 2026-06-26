package com.rainy.token.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.OverviewStats
import com.rainy.token.data.local.ModelStats
import com.rainy.token.data.local.DailyStats
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.SyncCommandCodeUsageUseCase
import com.rainy.token.domain.usecase.SyncUsageUseCase
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

/** 时间筛选条件 */
sealed class TimeFilter(val label: String) {
    data object All : TimeFilter("全部")
    data object Last5h : TimeFilter("最近5小时")
    data object Last24h : TimeFilter("最近24小时")
    data object Today : TimeFilter("今天")
    data object Yesterday : TimeFilter("昨天")
    data object Last7Days : TimeFilter("最近7天")
    data object Last30Days : TimeFilter("最近30天")
    data object ThisMonth : TimeFilter("当月")
    data class Custom(val from: Long, val to: Long) : TimeFilter("自定义")

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
    private val syncCommandCodeUseCaseProvider: Provider<SyncCommandCodeUsageUseCase>,
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    private var workspaceIdOverride: String? = null
    private var loadGeneration = 0  // 递增：过时的 loadStatsInternal 结果自动丢弃

    /** 覆盖 workspaceId，用于 CCGO 等非 OCGO 服务。必须在 loadStats() 前调用。 */
    fun setWorkspace(wid: String) {
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
                dailyPage = 1
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

    fun sync() {
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true) }
            val result = withContext(Dispatchers.Default) {
                val cache = cacheProvider.get()
                if (workspaceIdOverride == com.rainy.token.data.repository.CommandCodeUsageRepository.CCGO_WORKSPACE_ID) {
                    val useCase = syncCommandCodeUseCaseProvider.get()
                    val count = cache.count(com.rainy.token.data.repository.CommandCodeUsageRepository.CCGO_WORKSPACE_ID)
                    if (count == 0) useCase.fullSync() else useCase.incrementalSync()
                } else {
                    val useCase = syncUseCaseProvider.get()
                    val count = cache.count()
                    if (count == 0) useCase.fullSync() else useCase.incrementalSync()
                }
            }
            result.onSuccess { loadStats() }
            _uiState.update {
                it.copy(
                    syncing = false,
                    lastSyncResult = result.getOrNull()?.inserted ?: 0,
                    lastSyncError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    /** 清除当前 workspace 的缓存数据并全量重新同步。 */
    fun clearAndResync() {
        viewModelScope.launch {
            val wid = workspaceIdOverride ?: return@launch
            _uiState.update { it.copy(syncing = true) }
            val cache = cacheProvider.get()
            cache.deleteByWorkspaceId(wid)
            invalidateData()
            val useCase = syncCommandCodeUseCaseProvider.get()
            val result = useCase.fullSync()
            result.onSuccess { loadStats() }
            _uiState.update {
                it.copy(
                    syncing = false,
                    lastSyncResult = result.getOrNull()?.inserted ?: 0,
                    lastSyncError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    private fun invalidateData() {
        _uiState.value = UsageUiState()
    }
}

data class UsageUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val overview: OverviewStats? = null,
    val modelStats: List<ModelStats> = emptyList(),
    val dailyStats: List<DailyStats> = emptyList(),
    val recordCount: Int = 0,
    val lastSyncResult: Int = 0,
    val lastSyncError: String? = null,
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