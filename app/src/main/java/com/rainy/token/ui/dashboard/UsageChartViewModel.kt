package com.rainy.token.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.ChartAggregator
import com.rainy.token.data.local.ChartBucket
import com.rainy.token.data.local.ChartGranularity
import com.rainy.token.data.local.ChartSettingsStore
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class UsageChartViewModel @Inject constructor(
    private val cacheProvider: Provider<UsageCache>,
    private val credentialRepository: CredentialRepository,
    private val chartSettingsStore: ChartSettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(
        ChartUiState(useUtc8 = chartSettingsStore.getUseUtc8())
    )
    val state: StateFlow<ChartUiState> = _state.asStateFlow()

    private var workspaceIdOverride: String? = null
    private var loadGeneration = 0
    /** 是否允许自动降级到更大时间窗口（仅初始加载时允许，用户手动选粒度后关闭） */
    private var allowFallback = true

    /** 覆盖 workspaceId，用于 CCGO 等非 OCGO 服务。必须在 load() 前调用。 */
    fun setWorkspace(wid: String) {
        workspaceIdOverride = wid
        loadGeneration++
        allowFallback = true  // CCGO 首次加载也允许降级
        // 先清空数据防止 init 自动加载的 OCGO 数据闪一下，但保留持久化偏好
        _state.value = ChartUiState(useUtc8 = _state.value.useUtc8)
        load()
    }

    private suspend fun workspaceId(): String? {
        workspaceIdOverride?.let { return it }
        val c = credentialRepository.get(ServiceType.OPENCODE_GO)
        return (c as? Credential.SessionCredential)?.workspaceId?.takeIf { it.isNotBlank() }
    }

    // 不在 init 自动加载，由 Composable 层显式触发 load()（OCGO）或 setWorkspace()（CCGO）

    fun setGranularity(g: ChartGranularity) {
        allowFallback = false  // 用户手动选择，关闭自动降级
        _state.update { it.copy(granularity = g) }
        load()
    }

    fun toggleModel(model: String) {
        _state.update { s ->
            val new = s.selectedModels.toMutableSet()
            if (new.contains(model)) new.remove(model) else new.add(model)
            if (new.isEmpty()) {
                // all deselected = show all
                s.copy(selectedModels = emptySet())
            } else {
                s.copy(selectedModels = new)
            }
        }
        load()
    }

    fun selectAllModels() {
        _state.update { it.copy(selectedModels = emptySet()) }
        load()
    }

    fun toggleUtc8() {
        _state.update { it.copy(useUtc8 = !it.useUtc8) }
        viewModelScope.launch {
            chartSettingsStore.setUseUtc8(_state.value.useUtc8)
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            val wid = workspaceId() ?: return@launch
            val genAtStart = loadGeneration
            val cache = cacheProvider.get()
            val allModels = cache.getDistinctModels(wid)

            val (fromTs, toTs) = timeRangeFor(_state.value.granularity)
            val records = cache.getRecords(wid, fromTs = fromTs, toTs = toTs)

            // 按模型筛选
            val selected = _state.value.selectedModels
            val filtered = if (selected.isEmpty()) records
            else records.filter { it.model in selected }

            val offsetHours = if (_state.value.useUtc8) 8 else 0
            val buckets = when (_state.value.granularity) {
                ChartGranularity.LAST_12H_10MIN ->
                    ChartAggregator.aggregate10Min(filtered, offsetHours)
                ChartGranularity.LAST_5H_HOURLY,
                ChartGranularity.LAST_24H_HOURLY,
                ChartGranularity.TODAY_HOURLY,
                ChartGranularity.YESTERDAY_HOURLY,
                ChartGranularity.CUSTOM_DAY_HOURLY ->
                    ChartAggregator.aggregateHourly(filtered, offsetHours)
                ChartGranularity.THIS_MONTH_DAILY,
                ChartGranularity.LAST_7D_DAILY,
                ChartGranularity.CUSTOM_MONTH_DAILY,
                ChartGranularity.CUSTOM_RANGE_DAILY ->
                    ChartAggregator.aggregateDaily(filtered, offsetHours)
            }

            // 填充可能空缺的小时/天桶
            val filled = fillGaps(buckets, _state.value.granularity, fromTs, toTs)
            // 最近5小时只保留最后5个桶
            val result = if (_state.value.granularity == ChartGranularity.LAST_5H_HOURLY)
                filled.takeLast(5) else filled

            // 如果 loadGeneration 已经变化（setWorkspace 被调用），丢弃这次结果
            if (loadGeneration != genAtStart) return@launch

            // 自动降级：当前粒度无实际数据且允许降级 → 尝试更大窗口
            val hasData = result.any { it.totalRequests > 0 }
            if (allowFallback && !hasData) {
                val fallbackTo = when (_state.value.granularity) {
                    ChartGranularity.LAST_5H_HOURLY -> ChartGranularity.LAST_12H_10MIN
                    ChartGranularity.LAST_12H_10MIN -> ChartGranularity.LAST_7D_DAILY
                    ChartGranularity.LAST_7D_DAILY -> ChartGranularity.THIS_MONTH_DAILY
                    else -> null
                }
                if (fallbackTo != null) {
                    _state.update { it.copy(granularity = fallbackTo) }
                    load()  // 递归重新加载
                    return@launch
                }
            }
            // 降级后或用户手动选择 → 关闭降级，正常显示
            allowFallback = false

            _state.update {
                it.copy(
                    buckets = result,
                    allModels = allModels,
                    loading = false
                )
            }
        }
    }

    fun setCustomDayRange(from: Long, to: Long) {
        _state.update { it.copy(granularity = ChartGranularity.CUSTOM_DAY_HOURLY) }
        _customDayRange = from to to
        load()
    }

    fun setCustomMonth(monthStartMs: Long) {
        _state.update { it.copy(granularity = ChartGranularity.CUSTOM_MONTH_DAILY) }
        _customMonthStart = monthStartMs
        load()
    }

    fun setCustomRange(from: Long, to: Long) {
        _state.update { it.copy(granularity = ChartGranularity.CUSTOM_RANGE_DAILY) }
        _customRange = from to to
        load()
    }

    private var _customDayRange: Pair<Long, Long> = 0L to 0L
    private var _customMonthStart: Long = 0L
    private var _customRange: Pair<Long, Long> = 0L to 0L

    private fun timeRangeFor(g: ChartGranularity): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        val useUtc8 = _state.value.useUtc8
        val zoneOffset = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
        val today = Instant.ofEpochMilli(now).atOffset(zoneOffset).toLocalDate()
        val todayMidnight = today.atStartOfDay(zoneOffset).toInstant().toEpochMilli()
        return when (g) {
            ChartGranularity.LAST_12H_10MIN -> now - 12 * 3600_000L to null
            ChartGranularity.LAST_5H_HOURLY -> now - 5 * 3600_000L to null
            ChartGranularity.LAST_24H_HOURLY -> now - 24 * 3600_000L to null
            ChartGranularity.TODAY_HOURLY -> todayMidnight to todayMidnight + 86400_000L - 1
            ChartGranularity.YESTERDAY_HOURLY -> todayMidnight - 86400_000L to todayMidnight - 1
            ChartGranularity.LAST_7D_DAILY -> {
                todayMidnight - 6 * 86400_000L to todayMidnight + 86400_000L - 1
            }
            ChartGranularity.CUSTOM_DAY_HOURLY -> {
                if (_customDayRange.first == 0L) todayMidnight to todayMidnight + 86400_000L - 1
                else _customDayRange.first to _customDayRange.second
            }
            ChartGranularity.THIS_MONTH_DAILY -> {
                val firstOfMonth = today.withDayOfMonth(1).atStartOfDay(zoneOffset).toInstant().toEpochMilli()
                val lastOfMonth = today.withDayOfMonth(today.lengthOfMonth())
                    .atStartOfDay(zoneOffset).toInstant().toEpochMilli() + 86400_000L - 1
                firstOfMonth to lastOfMonth
            }
            ChartGranularity.CUSTOM_MONTH_DAILY -> {
                if (_customMonthStart == 0L) null to null
                else {
                    val targetMonth = Instant.ofEpochMilli(_customMonthStart).atOffset(zoneOffset).toLocalDate()
                        .withDayOfMonth(1)
                    val firstOfMonth = targetMonth.atStartOfDay(zoneOffset).toInstant().toEpochMilli()
                    val lastOfMonth = targetMonth.withDayOfMonth(targetMonth.lengthOfMonth())
                        .atStartOfDay(zoneOffset).toInstant().toEpochMilli() + 86400_000L - 1
                    firstOfMonth to lastOfMonth
                }
            }
            ChartGranularity.CUSTOM_RANGE_DAILY -> {
                if (_customRange.first == 0L) null to null
                else _customRange.first to _customRange.second
            }
        }
    }

    private fun fillGaps(
        buckets: List<ChartBucket>,
        granularity: ChartGranularity,
        from: Long?,
        to: Long?
    ): List<ChartBucket> {
        if (buckets.isEmpty() || from == null) return buckets
        val interval = when (granularity) {
            ChartGranularity.LAST_12H_10MIN -> 600_000L  // 10分钟
            ChartGranularity.LAST_5H_HOURLY,
            ChartGranularity.LAST_24H_HOURLY,
            ChartGranularity.TODAY_HOURLY,
            ChartGranularity.YESTERDAY_HOURLY,
            ChartGranularity.CUSTOM_DAY_HOURLY -> 3600_000L
            else -> 86_400_000L
        }
        val end = to ?: (System.currentTimeMillis() / interval * interval)
        val result = mutableListOf<ChartBucket>()
        val map = buckets.associateBy { it.ts }
        // 日粒度：from 已由 timeRangeFor 按当前时区对齐到午夜，直接用
        // 小时粒度：from 是滑动窗口起点，需截断到 interval 边界
        var t = if (interval == 86_400_000L) from else from / interval * interval
        while (t <= end) {
            result.add(map[t] ?: ChartBucket(ts = t, totalCost = 0, totalRequests = 0,
                inputTokens = 0, cacheHitTokens = 0, outputTokens = 0))
            t += interval
        }
        return result
    }
}

data class ChartUiState(
    val loading: Boolean = true,
    val granularity: ChartGranularity = ChartGranularity.LAST_5H_HOURLY,
    val allModels: List<String> = emptyList(),
    val selectedModels: Set<String> = emptySet(), // empty = all
    val buckets: List<ChartBucket> = emptyList(),
    val useUtc8: Boolean = false
)