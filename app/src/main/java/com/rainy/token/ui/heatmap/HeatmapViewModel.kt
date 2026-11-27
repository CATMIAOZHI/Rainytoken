package com.rainy.token.ui.heatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.OpenCodeUsageRepository
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider

// ── 公共数据类 ──────────────────────────────────────────────

/** 热力图视图模式 */
enum class HeatmapViewMode { DAILY, WEEKLY, CUMULATIVE }

/** 年份选择器中的"最近"窗口（最近 365 天）sentinel：selectedYear == RECENT_YEAR 表示最近模式 */
internal const val RECENT_YEAR = -1

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
    val barHeight: Int,    // 柱状图高度（格数）：0 用量周=1（Level 0 浅色格，可见可点），非零周=2~7（按 token 排名比例）
)

/** 年度统计指标（按所选年份的每日数据计算，切换年份跟随变化） */
data class HeatmapStats(
    val totalTokens: Long = 0L, // 累计 token 数（当年全部天之和）
    val peakTokens: Long = 0L,  // 峰值 token 数（单日最大；当年无数据=0）
    val currentStreak: Int = 0, // 当前连续天数（从最后一天往前数连续 >0 的天数；末尾为 0 的天跳过，从最后一个非零天往前数）
    val maxStreak: Int = 0,     // 最长连续天数（当年内连续 >0 的最大天数）
)

/** UI 状态 */
data class HeatmapUiState(
    val loading: Boolean = true,
    val scopeGeneration: Int = 0,
    val viewMode: HeatmapViewMode = HeatmapViewMode.DAILY,
    val selectedYear: Int = 0,  // 当前选中范围：0=尚未加载，RECENT_YEAR(-1)=最近365天，>0=具体年份
    val currentYear: Int = 0,   // 今年（浮层日期判断是否显示年份用）
    val availableYears: List<Int> = emptyList(),  // 可选年份（最早数据年份..今年，降序展示）
    val dailyData: List<HeatmapDayData> = emptyList(),
    val weeklyData: List<HeatmapWeekData> = emptyList(),
    val cumulativeData: List<HeatmapDayData> = emptyList(),
    val colorLevels: IntArray = IntArray(6),  // [0, p25, p50, p75, p95, max]
    val stats: HeatmapStats = HeatmapStats(), // 年度统计（累计/峰值/当前连续/最长连续）
    val totalRequests: Long = 0L, // All synced workspace summaries, across calendar views.
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeatmapUiState) return false
        return loading == other.loading &&
            scopeGeneration == other.scopeGeneration &&
            viewMode == other.viewMode &&
            selectedYear == other.selectedYear &&
            currentYear == other.currentYear &&
            availableYears == other.availableYears &&
            dailyData == other.dailyData &&
            weeklyData == other.weeklyData &&
            cumulativeData == other.cumulativeData &&
            colorLevels.contentEquals(other.colorLevels) &&
            stats == other.stats &&
            totalRequests == other.totalRequests
    }

    override fun hashCode(): Int {
        var result = loading.hashCode()
        result = 31 * result + scopeGeneration
        result = 31 * result + viewMode.hashCode()
        result = 31 * result + selectedYear
        result = 31 * result + currentYear
        result = 31 * result + availableYears.hashCode()
        result = 31 * result + dailyData.hashCode()
        result = 31 * result + weeklyData.hashCode()
        result = 31 * result + cumulativeData.hashCode()
        result = 31 * result + colorLevels.contentHashCode()
        result = 31 * result + stats.hashCode()
        result = 31 * result + totalRequests.hashCode()
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

    private var loadJob: Job? = null
    private var yearJob: Job? = null
    private var displayedScope: String? = null
    private var scopeObserver: Job? = null
    // 全量记录缓存（仅在 load 时拉取一次，切换年份/视图时复用）
    private var records: List<UsageRecord> = emptyList()
    // 按年缓存三视图计算结果，切换年份不必重复计算（跨线程读写，用并发容器）
    private val yearResults = ConcurrentHashMap<Int, AllViewsResult>()
    // 最近一次请求的年份（防止快速连续切换时旧协程后完成覆盖新选择）
    private var latestRequestedYear = 0

    fun observeGoUsageScope() {
        if (scopeObserver?.isActive == true) return
        scopeObserver = viewModelScope.launch {
            credentialRepository.openCodeUsageScopeChanges.collect { scope ->
                if (displayedScope != scope) {
                    loadJob?.cancel()
                    loadJob = null
                    showGoScope(scope)
                }
                load()
            }
        }
    }

    private fun showGoScope(scope: String?) {
        if (displayedScope == scope) return
        displayedScope = scope
        yearJob?.cancel()
        yearResults.clear()
        latestRequestedYear = RECENT_YEAR
        records = emptyList()
        _uiState.value = HeatmapUiState(
            loading = scope != null,
            scopeGeneration = _uiState.value.scopeGeneration + 1,
            viewMode = _uiState.value.viewMode
        )
    }

    /**
     * Composable 层在 LaunchedEffect 中调用一次即可。
     * 首次加载拉取全量记录并计算默认年份（今年）的三视图数据。
     */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { loadInternal() }
    }

    /** 切换视图模式（仅改 State，不重新加载数据） */
    fun setViewMode(mode: HeatmapViewMode) {
        val result = yearResults[_uiState.value.selectedYear]
        val levels = result?.let {
            when (mode) {
                HeatmapViewMode.DAILY -> it.dailyLevels
                HeatmapViewMode.WEEKLY -> it.weeklyLevels
                HeatmapViewMode.CUMULATIVE -> it.cumulativeLevels
            }
        }
        _uiState.update { it.copy(viewMode = mode, colorLevels = levels?.toIntArray() ?: it.colorLevels) }
    }

    /** 切换窗口范围（年份或最近365天）：用缓存的记录按所选范围重新聚合三视图 */
    fun setYear(year: Int) {
        // guard 用 latestRequestedYear（最后一次请求意图）而非已提交的 selectedYear：
        // 避免"在途切换未提交时重复点击导致最后一次意图被丢弃"的竞态
        if (year == latestRequestedYear || records.isEmpty()) return
        latestRequestedYear = year
        yearJob?.cancel()
        yearJob = viewModelScope.launch {
            val scope = displayedScope
            val cached = yearResults[year]
            // 最近 365 天窗口以计算时刻的 todayTs 为锚：页面跨天常驻时缓存已过期（窗口末日在昨天），需重算
            val stale = cached != null && year == RECENT_YEAR && cached.dailyData.lastOrNull()?.dayTs != todayTsOf()
            val result = if (cached == null || stale) {
                withContext(Dispatchers.Default) {
                    computeAllViews(records, year = year)
                }
            } else {
                cached
            }
            // 期间用户又切了别的范围：丢弃过期结果，避免旧协程覆盖新选择
            if (cacheScope() != scope) {
                load()
                return@launch
            }
            if (year != latestRequestedYear || displayedScope != scope) return@launch
            yearResults[year] = result
            val mode = _uiState.value.viewMode
            val levels = when (mode) {
                HeatmapViewMode.DAILY -> result.dailyLevels
                HeatmapViewMode.WEEKLY -> result.weeklyLevels
                HeatmapViewMode.CUMULATIVE -> result.cumulativeLevels
            }
            _uiState.update {
                it.copy(
                    selectedYear = year,
                    dailyData = result.dailyData,
                    weeklyData = result.weeklyData,
                    cumulativeData = result.cumulativeData,
                    colorLevels = levels.toIntArray(),
                    stats = computeStats(result.dailyData),
                )
            }
        }
    }

    /** 当前时区下的今天自然日零点（缓存新鲜度校验用） */
    private fun todayTsOf(): Long {
        return startOfDayTs(System.currentTimeMillis(), ZoneOffset.UTC)
    }

    // ── 内部逻辑 ────────────────────────────────────────────

    private suspend fun cacheScope(): String? {
        return OpenCodeUsageRepository.cacheScope(credentialRepository.get(ServiceType.OPENCODE_GO))
    }

    private suspend fun loadInternal() {
        try {
            while (true) {
                val scope = cacheScope()
                if (displayedScope == scope && !_uiState.value.loading) return
                showGoScope(scope)
                if (scope == null) {
                    _uiState.update { it.copy(loading = false) }
                    return
                }
                val cache = cacheProvider.get()
                val currentYear = Instant.now().atOffset(ZoneOffset.UTC).year
                val fetched = withContext(Dispatchers.Default) { cache.getRecords(scope) }
                val (result, availableYears, totalRequests) = withContext(Dispatchers.Default) {
                    Triple(
                        computeAllViews(fetched, year = RECENT_YEAR),
                        computeAvailableYears(fetched, ZoneOffset.UTC, currentYear),
                        computeTotalRequests(fetched)
                    )
                }
                if (cacheScope() != scope) continue
                records = fetched
                yearResults[RECENT_YEAR] = result
                // 重操作完成后按当前视图模式写入对应的分位阈值（加载期间可能已切换视图）
                val mode = _uiState.value.viewMode
                val levels = when (mode) {
                    HeatmapViewMode.DAILY -> result.dailyLevels
                    HeatmapViewMode.WEEKLY -> result.weeklyLevels
                    HeatmapViewMode.CUMULATIVE -> result.cumulativeLevels
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        selectedYear = RECENT_YEAR,
                        currentYear = currentYear,
                        availableYears = availableYears,
                        dailyData = result.dailyData,
                        weeklyData = result.weeklyData,
                        cumulativeData = result.cumulativeData,
                        colorLevels = levels.toIntArray(),
                        stats = computeStats(result.dailyData),
                        totalRequests = totalRequests,
                    )
                }
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(loading = false) }
        }
    }

    /** 可选年份列表：最早有数据的年份 ～ 今年；无数据时只有今年 */
    private fun computeAvailableYears(records: List<UsageRecord>, zone: ZoneOffset, currentYear: Int): List<Int> {
        val minYear = records.minOfOrNull { Instant.ofEpochMilli(it.timeCreated).atOffset(zone).year }
            ?: currentYear
        return (minYear..currentYear).toList()
    }

    // ── 聚合计算 ────────────────────────────────────────────

    companion object {
        private const val DAY_MS = 86_400_000L

        /**
         * 按指定窗口从全部记录计算三种视图数据 + 颜色等级阈值。
         *
         * 窗口类型由 [year] 决定：
         * - [RECENT_YEAR]（-1）：最近 365 天（今天往前 364 天 ～ 今天，含今天共 365 天），默认窗口
         * - 今年（year == 当前自然年）：1月1日 ～ 今天
         * - 往年：1月1日 ～ 12月31日（完整自然年）
         * - 周视图：周日对齐，第一列为窗口首日所在周（可能含窗口前空位），
         *   最后一列为结束日所在周（最近/今年=今天所在周；往年可能跨到次年 1 月初），
         *   列数按窗口浮动（约 52~54 列），不丢数据
         * - 三种视图的分位数均按所选窗口独立计算（颜色随窗口数据变化是预期行为）
         *
         * @param useUtc8 true=按 UTC+8 零点分桶（与全局图表设置一致），false=按 UTC 零点分桶
         * @param year 目标自然年，或 [RECENT_YEAR] 表示最近 365 天
         */
        internal fun computeAllViews(records: List<UsageRecord>, useUtc8: Boolean = false, year: Int): AllViewsResult {
            val zone = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
            val now = System.currentTimeMillis()
            val todayTs = startOfDayTs(now, zone)
            val currentYear = Instant.ofEpochMilli(now).atOffset(zone).year
            val isCurrentYear = year == currentYear
            val isRecent = year == RECENT_YEAR

            // 1. 窗口边界（该时区下的自然日零点）
            val windowStartTs = if (isRecent) {
                todayTs - 364 * DAY_MS
            } else {
                Instant.parse("${year}-01-01T00:00:00Z")
                    .atOffset(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            }
            val windowEndTs = if (isRecent || isCurrentYear) {
                todayTs
            } else {
                Instant.parse("${year}-12-31T00:00:00Z")
                    .atOffset(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            }
            val totalDays = ((windowEndTs - windowStartTs) / DAY_MS).toInt() + 1

            // 2. 按天分桶聚合 token 数（按所选时区的自然日零点切日）
            val dayTokenMap = HashMap<Long, Long>()
            for (r in records) {
                val dayTs = startOfDayTs(r.timeCreated, zone)
                if (dayTs < windowStartTs || dayTs > windowEndTs) continue
                val tokens = r.inputTokens + r.cacheReadTokens + r.reasoningTokens + r.outputTokens
                dayTokenMap.merge(dayTs, tokens, Long::plus)
            }

            // 3. 构建完整的每日数据列表（窗口首日 ～ 结束日）
            val dailyRaw = ArrayList<HeatmapDayData>(totalDays)
            for (i in 0 until totalDays) {
                val ts = windowStartTs + i * DAY_MS
                val tokens = dayTokenMap[ts] ?: 0L
                dailyRaw.add(HeatmapDayData(dayTs = ts, tokens = tokens, level = 0))
            }

            // 4. 计算每日视图颜色等级
            val dailyLevels = computeQuantileLevels(dailyRaw.map { it.tokens })
            val dailyData = dailyRaw.map { it.copy(level = dailyLevels.getColorLevel(it.tokens)) }

            // 5. 每周视图：周日对齐，第一列=窗口首日所在周，最后一列=结束日所在周
            val firstWeekStart = startOfWeekTs(windowStartTs, zone)
            val lastWeekStart = startOfWeekTs(windowEndTs, zone)
            val (weeklyData, weeklyLevels) = buildWeeklyData(dailyRaw, firstWeekStart, lastWeekStart)

            // 6. 累计视图：从窗口首日到结束日的累计总和
            var cumulativeSum = 0L
            val cumulativeRaw = ArrayList<HeatmapDayData>(totalDays)
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
         * 构建每周视图数据：周日对齐，从 firstWeekStart 到 lastWeekStart（含）每周一列。
         *
         * 每列聚合该周落在 [yearStartTs, yearEndTs] 范围内的天数（dailyRaw 只含年内天数，
         * 首列跨前一年 12 月的部分、末列跨次年 1 月初的部分天然不计入），
         * 周总量决定该列统一的热度等级；渲染层（HeatmapCanvas）按 barHeight
         * 从底部向上绘制柱状图（0 用量周=1 格浅色，非零周=2~7 格）。
         */
        private fun buildWeeklyData(
            dailyRaw: List<HeatmapDayData>,
            firstWeekStart: Long,
            lastWeekStart: Long,
        ): Pair<List<HeatmapWeekData>, QuantileLevels> {
            val numWeeks = ((lastWeekStart - firstWeekStart) / (7L * DAY_MS)).toInt() + 1
            val weeks = ArrayList<HeatmapWeekData>(numWeeks)

            for (w in 0 until numWeeks) {
                val ws = firstWeekStart + w * 7L * DAY_MS
                val we = ws + 7L * DAY_MS
                var weekTokens = 0L
                for (d in dailyRaw) {
                    if (d.dayTs >= ws && d.dayTs < we) weekTokens += d.tokens
                }
                weeks.add(HeatmapWeekData(
                    weekStartTs = ws,
                    tokens = weekTokens,
                    level = 0,
                    barHeight = 1,
                ))
            }

            // 颜色等级：6 级分位数（供颜色与图例使用）
            val weekLevels = computeQuantileLevels(weeks.map { it.tokens })
            // 柱高：非零周按 token 去重排序的排名比例映射 2~7 格（排名不同高度必不同），
            // 0 用量周固定 1 格（Level 0 浅色，可见可点击）
            val barHeights = computeBarHeights(weeks.map { it.tokens })
            return weeks.mapIndexed { i, w ->
                w.copy(level = weekLevels.getColorLevel(w.tokens), barHeight = barHeights[i])
            } to weekLevels
        }

        /**
         * 计算每周视图的柱状图高度（格数）。
         *
         * - 0 用量周：1 格（Level 0 浅色格，作为「空白周」的可见标记，不隐藏）
         * - 非零周：按 token 值去重排序，排名比例映射到 2~7 格：
         *   height = 2 + rankIndex * 5 / (uniqueCount - 1)（rankIndex 0-based）
         *   唯一非零值只有 1 个时该周为 7 格（满柱）
         *
         * 用排名比例而非分位档，保证不同量级的周（如 3.7亿 vs 10.7亿）高度不同。
         */
        private fun computeBarHeights(tokens: List<Long>): IntArray {
            val uniqueNonZero = tokens.filter { it > 0L }.distinct().sorted()
            val n = uniqueNonZero.size
            return IntArray(tokens.size) { i ->
                val t = tokens[i]
                when {
                    t <= 0L -> 1
                    n == 1 -> 7
                    else -> 2 + uniqueNonZero.indexOf(t) * 5 / (n - 1)
                }
            }
        }

        /**
         * 计算年度统计指标（基于所选年份的完整日序列，dailyRaw 含 0 token 的天）。
         *
         * - totalTokens：全部天之和
         * - peakTokens：单日最大 token（当年无数据=0）
         * - currentStreak：从最后一天往前数连续 >0 的天数；结尾为 0 的天（如当天尚未产生 token）跳过，
         *   从最后一个非零天往前数（全部为 0 则=0）
         * - maxStreak：当年内连续 >0 的最大天数
         */
        internal fun computeStats(dailyData: List<HeatmapDayData>): HeatmapStats {
            var total = 0L
            var peak = 0L
            var maxStreak = 0
            var run = 0
            for (d in dailyData) {
                total += d.tokens
                if (d.tokens > peak) peak = d.tokens
                if (d.tokens > 0L) run++ else run = 0
                if (run > maxStreak) maxStreak = run
            }
            // 当前连续天数：最后一天即使为 0（当天尚未产生 token）也计入连续——跳过结尾的 0，
            // 从最后一个非零天往前数连续 >0 的天数；全部为 0 时=0
            var currentStreak = 0
            var i = dailyData.size - 1
            while (i >= 0 && dailyData[i].tokens == 0L) i--
            while (i >= 0 && dailyData[i].tokens > 0L) {
                currentStreak++
                i--
            }
            return HeatmapStats(total, peak, currentStreak, maxStreak)
        }

        internal fun computeTotalRequests(records: List<UsageRecord>): Long =
            records.sumOf { it.requestCount }

        private fun startOfDayTs(ts: Long, zone: ZoneOffset): Long {
            return Instant.ofEpochMilli(ts).atOffset(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        }

        /** 返回时间戳在指定时区下所在周的周日零点（0=Sunday） */
        private fun startOfWeekTs(ts: Long, zone: ZoneOffset): Long {
            val dow = dayOfWeekFromTs(ts, zone)
            return startOfDayTs(ts, zone) - dow * DAY_MS
        }

        /** 时间戳在指定时区下的星期几 (0=Sunday, 6=Saturday) */
        private fun dayOfWeekFromTs(ts: Long, zone: ZoneOffset): Int {
            val dow = Instant.ofEpochMilli(ts).atOffset(zone).dayOfWeek.value
            return dow % 7 // Sunday=7 → 0
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