package com.rainy.token.ui.heatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider

// ── 公共数据类 ──────────────────────────────────────────────

/** 热力图视图模式 */
enum class HeatmapViewMode { DAILY, WEEKLY, CUMULATIVE }

/** 每日 / 累计视图的单格数据 */
data class HeatmapDayData(
    val dayTs: Long,   // UTC 日期时间戳（即天分桶的时间戳）
    val tokens: Long,  // 当天 token 数（DAILY）或累计 token 数（CUMULATIVE）
    val level: Int,    // 0-5 颜色等级
)

/** 每周视图的单格数据 */
data class HeatmapWeekData(
    val weekStartTs: Long, // 该周起始日时间戳
    val tokens: Long,      // 该周 7 天累计 token 数
    val level: Int,        // 0-5 颜色等级
)

/** UI 状态 */
data class HeatmapUiState(
    val loading: Boolean = true,
    val viewMode: HeatmapViewMode = HeatmapViewMode.DAILY,
    val dailyData: List<HeatmapDayData> = emptyList(),
    val weeklyData: List<HeatmapWeekData> = emptyList(),
    val cumulativeData: List<HeatmapDayData> = emptyList(),
    val colorLevels: IntArray = IntArray(6),  // [0, p25, p50, p75, p95, max]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeatmapUiState) return false
        return loading == other.loading &&
            viewMode == other.viewMode &&
            dailyData == other.dailyData &&
            weeklyData == other.weeklyData &&
            cumulativeData == other.cumulativeData &&
            colorLevels.contentEquals(other.colorLevels)
    }

    override fun hashCode(): Int {
        var result = loading.hashCode()
        result = 31 * result + viewMode.hashCode()
        result = 31 * result + dailyData.hashCode()
        result = 31 * result + weeklyData.hashCode()
        result = 31 * result + cumulativeData.hashCode()
        result = 31 * result + colorLevels.contentHashCode()
        return result
    }
}

/**
 * 分位阈值与颜色等级映射。
 *
 * thresholds[0..5] 存 Long 阈值：
 * - Level 0: value == 0
 * - Level 1: 0 < value ≤ P25
 * - Level 2: P25 < value ≤ P50
 * - Level 3: P50 < value ≤ P75
 * - Level 4: P75 < value ≤ P95
 * - Level 5: value > P95
 *
 * 边界情况（非零天数 < 2 或所有非零值相同）时，所有非零值设为 Level 3。
 */
internal data class QuantileLevels(val thresholds: LongArray) {
    fun getColorLevel(value: Long): Int {
        if (value <= 0L) return 0
        for (level in 1..5) {
            if (value <= thresholds[level]) return level
        }
        return 5
    }

    fun toIntArray(): IntArray = IntArray(6) { idx ->
        val v = thresholds[idx]
        if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuantileLevels) return false
        return thresholds.contentEquals(other.thresholds)
    }

    override fun hashCode(): Int = thresholds.contentHashCode()
}

// ── ViewModel ──────────────────────────────────────────────

@HiltViewModel
class HeatmapViewModel @Inject constructor(
    private val cacheProvider: Provider<UsageCache>,
    private val credentialRepository: CredentialRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HeatmapUiState())
    val uiState: StateFlow<HeatmapUiState> = _uiState.asStateFlow()

    private var loaded = false
    private var allViewsResult: AllViewsResult? = null

    /**
     * Composable 层在 LaunchedEffect 中调用一次即可。
     * 三种视图数据全部在此次加载中计算好，切换视图不需要重新加载。
     */
    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch { loadInternal() }
    }

    /** 切换视图模式（仅改 State，不重新加载数据） */
    fun setViewMode(mode: HeatmapViewMode) {
        val result = allViewsResult
        val levels = result?.let {
            when (mode) {
                HeatmapViewMode.DAILY -> it.dailyLevels
                HeatmapViewMode.WEEKLY -> it.weeklyLevels
                HeatmapViewMode.CUMULATIVE -> it.cumulativeLevels
            }
        }
        _uiState.update { it.copy(viewMode = mode, colorLevels = levels?.toIntArray() ?: it.colorLevels) }
    }

    // ── 内部逻辑 ────────────────────────────────────────────

    private suspend fun workspaceId(): String? {
        val c = credentialRepository.get(ServiceType.OPENCODE_GO)
        return (c as? Credential.SessionCredential)?.workspaceId?.takeIf { it.isNotBlank() }
    }

    private suspend fun loadInternal() {
        try {
            val wid = workspaceId() ?: run {
                _uiState.update { it.copy(loading = false) }
                return
            }
            val cache = cacheProvider.get()

            // 所有重操作放在 Dispatchers.Default 上
            val result = withContext(Dispatchers.Default) {
                val records = cache.getRecords(wid)
                computeAllViews(records)
            }

            allViewsResult = result

            _uiState.update {
                it.copy(
                    loading = false,
                    dailyData = result.dailyData,
                    weeklyData = result.weeklyData,
                    cumulativeData = result.cumulativeData,
                    colorLevels = result.dailyLevels.toIntArray(),
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(loading = false) }
        }
    }

    // ── 聚合计算 ────────────────────────────────────────────

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val RANGE_DAYS = 365

        /**
         * 从全部记录计算三种视图数据 + 颜色等级阈值。
         * 返回 (dailyData, weeklyData, cumulativeData, colorLevels)
         */
        internal fun computeAllViews(records: List<UsageRecord>): AllViewsResult {
            val now = System.currentTimeMillis()

            // 1. 按天分桶聚合 token 数
            val todayTs = now / DAY_MS * DAY_MS
            val rangeStartTs = todayTs - (RANGE_DAYS - 1) * DAY_MS

            // 聚合每一天的 token
            val dayTokenMap = HashMap<Long, Long>()
            for (r in records) {
                val dayTs = r.timeCreated / DAY_MS * DAY_MS
                if (dayTs < rangeStartTs || dayTs > todayTs) continue
                val tokens = r.inputTokens + r.cacheReadTokens + r.reasoningTokens + r.outputTokens
                dayTokenMap.merge(dayTs, tokens, Long::plus)
            }

            // 2. 构建完整的每日数据列表（365 天，从 rangeStartTs 到 todayTs）
            val dailyRaw = ArrayList<HeatmapDayData>(RANGE_DAYS)
            for (i in 0 until RANGE_DAYS) {
                val ts = rangeStartTs + i * DAY_MS
                val tokens = dayTokenMap[ts] ?: 0L
                dailyRaw.add(HeatmapDayData(dayTs = ts, tokens = tokens, level = 0))
            }

            // 3. 计算每日视图颜色等级
            val dailyLevels = computeQuantileLevels(dailyRaw.map { it.tokens })
            val dailyData = dailyRaw.map { it.copy(level = dailyLevels.getColorLevel(it.tokens)) }

            // 4. 每周视图：从最早日数据开始，每 7 天一组
            val (weeklyData, weeklyLevels) = buildWeeklyData(dailyRaw)

            // 5. 累计视图：从第一天到当天的累计总和
            var cumulativeSum = 0L
            val cumulativeRaw = ArrayList<HeatmapDayData>(RANGE_DAYS)
            for (d in dailyRaw) {
                cumulativeSum += d.tokens
                cumulativeRaw.add(HeatmapDayData(dayTs = d.dayTs, tokens = cumulativeSum, level = 0))
            }
            val cumulativeLevels = computeQuantileLevels(cumulativeRaw.map { it.tokens })
            val cumulativeData = cumulativeRaw.map { it.copy(level = cumulativeLevels.getColorLevel(it.tokens)) }

            // 保存三种视图各自的分位阈值，供切换视图时更新图例
            return AllViewsResult(dailyData, weeklyData, cumulativeData, dailyLevels, weeklyLevels, cumulativeLevels)
        }

        /**
         * 构建每周视图数据。
         * 从 rangeStartTs 对应的那天开始，每 7 天为一组。
         */
        private fun buildWeeklyData(dailyRaw: List<HeatmapDayData>): Pair<List<HeatmapWeekData>, QuantileLevels> {
            val weeks = ArrayList<HeatmapWeekData>()
            var i = 0
            while (i < 52 * 7 && i < dailyRaw.size) {
                val weekEnd = minOf(i + 7, dailyRaw.size)
                var weekTokens = 0L
                for (j in i until weekEnd) {
                    weekTokens += dailyRaw[j].tokens
                }
                weeks.add(HeatmapWeekData(
                    weekStartTs = dailyRaw[i].dayTs,
                    tokens = weekTokens,
                    level = 0,
                ))
                i += 7
            }

            // 计算每周视图颜色等级
            val weekLevels = computeQuantileLevels(weeks.map { it.tokens })
            return weeks.map { it.copy(level = weekLevels.getColorLevel(it.tokens)) } to weekLevels
        }

        // ── 分位数颜色等级计算 ──────────────────────────────

        /**
         * 使用 nearest-rank 分位数法计算 P25/P50/P75/P95 阈值。
         *
         * 边界情况：
         * - 非零天数 < 2 → 全部设为 Level 3
         * - 所有非零值相同 → 全部设为 Level 3
         *
         * 返回 [QuantileLevels]，内部 thresholds 为 LongArray(6)：
         * index 0 = 0（Level 0），index 1-5 分别存 P25/P50/P75/P95/MaxValue。
         */
        internal fun computeQuantileLevels(values: List<Long>): QuantileLevels {
            // 收集所有非零值
            val nonZero = values.filter { it > 0L }

            // 边界：非零值 < 2 或全部相同 → 全部设为 Level 3
            if (nonZero.size < 2 || nonZero.toSet().size == 1) {
                // thresholds[3] = Long.MAX_VALUE 使得 0 < value ≤ MAX → Level 3
                val t = LongArray(6)
                t[3] = Long.MAX_VALUE
                return QuantileLevels(t)
            }

            val sorted = nonZero.sorted()
            val n = sorted.size

            val p25 = nearestRank(sorted, n, 0.25)
            val p50 = nearestRank(sorted, n, 0.50)
            val p75 = nearestRank(sorted, n, 0.75)
            val p95 = nearestRank(sorted, n, 0.95)

            // LongArray(6): [0, p25, p50, p75, p95, MaxValue]
            val thresholds = LongArray(6)
            thresholds[0] = 0L
            thresholds[1] = p25
            thresholds[2] = p50
            thresholds[3] = p75
            thresholds[4] = p95
            thresholds[5] = Long.MAX_VALUE
            return QuantileLevels(thresholds)
        }

        private fun nearestRank(sorted: List<Long>, n: Int, percentile: Double): Long {
            val rank = (Math.ceil(n * percentile).toInt() - 1).coerceIn(0, n - 1)
            return sorted[rank]
        }
    }
}

// ── 辅助类型 ──────────────────────────────────────────────

/** computeAllViews 返回值 */
internal data class AllViewsResult(
    val dailyData: List<HeatmapDayData>,
    val weeklyData: List<HeatmapWeekData>,
    val cumulativeData: List<HeatmapDayData>,
    val dailyLevels: QuantileLevels,
    val weeklyLevels: QuantileLevels,
    val cumulativeLevels: QuantileLevels,
)