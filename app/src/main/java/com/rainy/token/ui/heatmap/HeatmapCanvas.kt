package com.rainy.token.ui.heatmap

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.rainy.token.R
import com.rainy.token.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

// ── 热力图颜色 ──────────────────────────────────────────────

/** 亮色模式 6 级粉色梯度 */
private val LightHeatmapColors = listOf(
    Color(0xFFEFE0E5), // Level 0: 极浅暖灰
    Color(0xFFFFD6E0), // Level 1: 浅樱粉
    Color(0xFFFFB3C6), // Level 2: 草莓粉柔
    Color(0xFFFF85A2), // Level 3: 草莓粉
    Color(0xFFFF6B8E), // Level 4: 草莓粉深
    Color(0xFFE84973), // Level 5: 玫红
)

/** 暗色模式 6 级粉色梯度 */
private val DarkHeatmapColors = listOf(
    Color(0xFF3A2A30), // Level 0: 暖深灰
    Color(0xFF4A2E3A), // Level 1
    Color(0xFF6B3D4A), // Level 2
    Color(0xFF8B4D5A), // Level 3
    Color(0xFFB86880), // Level 4
    Color(0xFFD67F9A), // Level 5
)

/** 月份名称资源（按月索引 1~12，locale-aware） */
private val monthNameRes = intArrayOf(
    R.string.month_1, R.string.month_2, R.string.month_3, R.string.month_4,
    R.string.month_5, R.string.month_6, R.string.month_7, R.string.month_8,
    R.string.month_9, R.string.month_10, R.string.month_11, R.string.month_12,
)

// ── 格式化辅助函数（HeatmapScreen 浮层与滑动查看读数条共用）──

/** 是否为繁体中文 locale（数字单位用 萬/億） */
private fun isTraditionalChinese(locale: Locale): Boolean =
    locale.language == "zh" &&
        (locale.country == "TW" || locale.country == "HK" || locale.country == "MO")

/**
 * 将 Token 数按数量级格式化（locale-aware）：
 * - 中文（zh）：亿级 1.9亿 / 万级 11.2万（繁体用 萬/億）
 * - 其他语言：≥1e9 → 1.9B，≥1e6 → 11.2M，≥1e3 → 16.8K，否则原数字
 *
 * 注意：中文万级先舍入到一位小数再判断是否升亿（如 99999999 → "1.0亿"，而非 "10000.0万"）。
 */
internal fun formatTokenChinese(tokens: Long, locale: Locale = Locale.getDefault()): String {
    if (locale.language != "zh") {
        return when {
            tokens >= 1_000_000_000L -> "${"%.1f".format(tokens / 1_000_000_000.0)}B"
            tokens >= 1_000_000L -> "${"%.1f".format(tokens / 1_000_000.0)}M"
            tokens >= 1_000L -> "${"%.1f".format(tokens / 1_000.0)}K"
            else -> "$tokens"
        }
    }
    val wan = if (isTraditionalChinese(locale)) "萬" else "万"
    val yi = if (isTraditionalChinese(locale)) "億" else "亿"
    return when {
        tokens >= 1_0000_0000 -> {
            val v = tokens / 1_0000_0000.0
            if (v >= 100) "${v.roundToInt()}$yi"
            else "${"%.1f".format(v)}$yi"
        }
        tokens >= 1_0000 -> {
            val v = tokens / 1_0000.0
            // 先舍入到一位小数，避免 "99999999 → 10000.0万"
            val rounded = (v * 10).roundToInt() / 10.0
            if (rounded >= 10000) {
                // 万级溢出升亿：9999.99万 → 1.0亿
                val v2 = rounded / 10000.0
                if (v2 >= 100) "${v2.roundToInt()}$yi"
                else "${"%.1f".format(v2)}$yi"
            } else {
                "${"%.1f".format(v)}$wan"  // 始终保留一位小数
            }
        }
        else -> "$tokens"
    }
}

/**
 * 将时间戳格式化为日期文本（locale-aware），按与数据分桶一致的时区：
 * - 中文（zh）："7月9日"，跨年前缀 "2024年7月9日"
 * - 其他语言：按 locale 的 "MMM d"，跨年 "MMM d, yyyy"（如 "Jul 9, 2024"）
 *
 * 日浮层传 [currentYear]（查看往年时带年份前缀）；周浮层两端传 [selectedYear]，
 * 使跨年周（如 12月28日-次年1月3日）的结束日带年份前缀，避免范围倒挂。
 */
internal fun formatDateChinese(ts: Long, useUtc8: Boolean, yearContext: Int, locale: Locale = Locale.getDefault()): String {
    val zone = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
    val date = Instant.ofEpochMilli(ts).atOffset(zone).toLocalDate()
    return if (locale.language == "zh") {
        val yearPrefix = if (date.year != yearContext) "${date.year}年" else ""
        "$yearPrefix${date.monthValue}月${date.dayOfMonth}日"
    } else {
        val fmt = if (date.year != yearContext) "MMM d, yyyy" else "MMM d"
        DateTimeFormatter.ofPattern(fmt, locale).format(date)
    }
}

/**
 * 将周数据格式化为范围文本："12月28日-12月31日"（不含"使用了X token"，
 * 句子由调用方用 stringResource 按语言拼接）。
 *
 * - 末周只显示到该周落在所选窗口内的有效日期（[dataEndTs]，数据最后一天），
 *   避免出现未绘制的未来/次年空位日期；数据同源，不依赖实时时钟
 * - 首列跨年（start 在上一年）时，end 与 start 不同年 → end 强制带年份前缀，避免范围倒挂
 * - [RECENT_YEAR]（最近 365 天窗口）时按周自身年份判断：start 不带前缀，
 *   end 仅在与 start 跨年时带前缀（窗口可能跨年，如 "1月5日-2026年1月3日"）
 */
internal fun formatWeekRangeText(
    week: HeatmapWeekData,
    useUtc8: Boolean,
    selectedYear: Int,
    dataEndTs: Long?,
    locale: Locale = Locale.getDefault(),
): String {
    val zone = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
    val startDate = Instant.ofEpochMilli(week.weekStartTs).atOffset(zone).toLocalDate()
    // 最近模式按周自身年份判断前缀；年份模式用所选年作上下文（与既有行为一致）
    val startContext = if (selectedYear == RECENT_YEAR) startDate.year else selectedYear
    val start = formatDateChinese(week.weekStartTs, useUtc8, startContext, locale)
    val endTs = minOf(week.weekStartTs + 6L * 86_400_000L, dataEndTs ?: (week.weekStartTs + 6L * 86_400_000L))
    if (locale.language == "zh") {
        val endDate = Instant.ofEpochMilli(endTs).atOffset(zone).toLocalDate()
        val end = if (endDate.year != startDate.year) {
            "${endDate.year}年${endDate.monthValue}月${endDate.dayOfMonth}日"
        } else {
            formatDateChinese(endTs, useUtc8, startContext, locale)
        }
        return "$start-$end"
    }
    // 非中文：end 用 start 的年份作上下文（同 startDate.year 时无前缀；跨年时带年份）
    return "$start-${formatDateChinese(endTs, useUtc8, startContext, locale)}"
}

// ── 辅助类型与函数 ──────────────────────────────────────────

/**
 * 水平拖动手势的仲裁模式：
 * - VIEW：慢速横向拖动 → 滑动查看模式（显示手指所在天的信息，图表锁定不滚动）
 * - SCROLL：快速横向拖动或纵向拖动 → 放行给 scrollable 正常滚动
 */
private enum class DragMode { VIEW, SCROLL }

/** 慢速/快速拖动分界速度（dp/s，密度无关；低于=查看拖动，高于=滚动拖动） */
private const val VIEW_SPEED_DP_PER_S = 150f

/** 月份标签 */
private data class MonthLabel(
    val weekIndex: Int,
    val label: String,
)

/** 从时间戳获取星期几 (0=Sunday, 6=Saturday)，按指定时区 */
private fun dayOfWeekFromTs(ts: Long, zone: ZoneOffset): Int {
    val dayOfWeek = Instant.ofEpochMilli(ts).atOffset(zone).dayOfWeek.value
    return dayOfWeek % 7 // Sunday=7 → 0
}

/** 从时间戳获取月份 (0=January)，按指定时区 */
private fun monthFromTs(ts: Long, zone: ZoneOffset): Int {
    return Instant.ofEpochMilli(ts).atOffset(zone).monthValue - 1
}

/**
 * 将每日数据按周分组（参考 react-activity-calendar 的 groupByWeeks 逻辑）。
 * 周起始日为周日（weekStart = 0）。
 * 返回 List<List<HeatmapDayData?>>，外层为周，内层为星期 0-6，null 表示空位。
 */
private fun groupByWeeks(dailyData: List<HeatmapDayData>, zone: ZoneOffset): List<List<HeatmapDayData?>> {
    if (dailyData.isEmpty()) return emptyList()

    val firstDow = dayOfWeekFromTs(dailyData[0].dayTs, zone)

    // 左侧填充空位，使第一周从周日开始
    val padded = ArrayList<HeatmapDayData?>(firstDow + dailyData.size)
    repeat(firstDow) { padded.add(null) }
    padded.addAll(dailyData)

    val numWeeks = (padded.size + 6) / 7
    return (0 until numWeeks).map { w ->
        (0 until 7).map { d ->
            val idx = w * 7 + d
            if (idx < padded.size) padded[idx] else null
        }
    }
}

/** 从按周分组的每日数据计算月份标签（[monthNames] 为当前 locale 的月份名，索引 0~11） */
private fun getMonthLabels(
    weeks: List<List<HeatmapDayData?>>,
    zone: ZoneOffset,
    monthNames: List<String>,
): List<MonthLabel> {
    if (weeks.isEmpty()) return emptyList()

    val rawLabels = mutableListOf<MonthLabel>()
    var prevMonth = -1

    for (weekIndex in weeks.indices) {
        val firstDay = weeks[weekIndex].firstOrNull { it != null } ?: continue
        val month = monthFromTs(firstDay.dayTs, zone)

        if (weekIndex == 0 || month != prevMonth) {
            rawLabels.add(MonthLabel(weekIndex, monthNames[month]))
            prevMonth = month
        }
    }

    return filterMonthLabels(rawLabels, weeks.size)
}

/**
 * 过滤月份标签：跳过间距不足的标签（参考 react-activity-calendar 的 label.ts）。
 * - 第一个标签：若与第二个标签间距 < minWeeks，跳过
 * - 最后一个标签：若剩余周数 < minWeeks，跳过
 */
private fun filterMonthLabels(rawLabels: List<MonthLabel>, totalWeeks: Int): List<MonthLabel> {
    val minWeeks = 3
    return rawLabels.filterIndexed { index, label ->
        when {
            index == 0 -> {
                rawLabels.getOrNull(1)?.let { it.weekIndex - label.weekIndex >= minWeeks } ?: false
            }
            index == rawLabels.lastIndex -> {
                totalWeeks - label.weekIndex >= minWeeks
            }
            else -> true
        }
    }
}

// ── HeatmapCanvas Composable ───────────────────────────────

/**
 * Token 活动热力图 Canvas 绘制组件。
 *
 * 支持三种视图：
 * - [HeatmapViewMode.DAILY]：约 53 列 × 7 行（列数按年浮动 52~54），每日 Token 使用
 * - [HeatmapViewMode.WEEKLY]：约 53 列 × 7 行，**离散柱状图**——列=自然周，
 *   整列 7 格全部绘制：底部 barHeight 格 = 该周等级颜色（0 用量周 1 格、非零周 2~7 格
 *   按 token 排名比例），其余格 = Level 0 浅色（空白格不隐藏，网格完整）；
 *   选中周整列加主题色描边强调；列内格子不表示日期
 * - [HeatmapViewMode.CUMULATIVE]：约 53 列 × 7 行，累计 Token
 *
 * 三种视图列数与网格尺寸一致（约 52~54 列 × 7 行），切换视图时布局保持一致。
 *
 * 使用 Compose Canvas 绘制圆角矩形格子，底部显示中文月份标签，
 * 支持水平滚动和格子点击。
 *
 * @param state UI 状态，包含数据与视图模式
 * @param modifier 布局修饰符
 * @param selectedWeek 当前选中的周（每周视图绘制强调边框用，null=未选中）
 * @param onDayClick 每日/累计视图格子点击回调
 * @param onWeekClick 每周视图格子点击回调
 */
@Composable
fun HeatmapCanvas(
    state: HeatmapUiState,
    modifier: Modifier = Modifier,
    noDataText: String = "",
    lessText: String = "",
    moreText: String = "",
    accessibilityDesc: String = "",
    selectedWeek: HeatmapWeekData? = null,
    onDayClick: (HeatmapDayData, IntOffset) -> Unit = { _, _ -> },
    onWeekClick: (HeatmapWeekData, IntOffset) -> Unit = { _, _ -> },
) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) DarkHeatmapColors else LightHeatmapColors
    val mutedColor = inkMuted()
    val density = LocalDensity.current
    // 应用内语言（LocaleManager 只覆写配置、不改变 Locale.getDefault()，故从配置取；
    // 与 UsageOverviewScreen 的 date_format_md 同一做法）
    val activeLocale = LocalConfiguration.current.locales[0]
    // 与全局图表设置一致的时区（分桶与显示口径统一）
    val zone = if (state.useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC

    // 格子参数
    val blockSize = 11.dp
    val gap = 3.dp
    val cornerRadius = 3.dp

    // 预计算 px 值（供 Canvas 绘制和点击检测共用）
    val blockSizePx = with(density) { blockSize.toPx() }
    val gapPx = with(density) { gap.toPx() }
    val cellStepPx = blockSizePx + gapPx
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    val isWeekly = state.viewMode == HeatmapViewMode.WEEKLY

    // 选中周强调样式（主题主色描边，明暗模式均可见）
    val selectionColor = MaterialTheme.colorScheme.primary
    val selectionStrokePx = with(density) { 1.5.dp.toPx() }

    // 滑动查看指示器（三种视图）：慢速拖动或长按时"所见即所得"——手指所在格子高亮预览，
    // 底部读数条显示该格数据（每日/累计=当天，每周=该周）；抬起后保留最后预览，切换视图/年份时清除
    var indicatorDay by remember { mutableStateOf<HeatmapDayData?>(null) }
    var indicatorWeek by remember { mutableStateOf<HeatmapWeekData?>(null) }
    var indicatorCol by remember { mutableStateOf(-1) }
    var indicatorRow by remember { mutableStateOf(-1) }

    // Canvas 在窗口中的位置（浮层锚点定位用）
    var canvasWindowPos by remember { mutableStateOf(IntOffset.Zero) }

    // 构建网格数据（每日/累计视图用于绘制与点击；每周视图仅用于列数与月份标签，
    // 柱状图绘制/点击直接基于 weeklyData）
    val gridWeeks = remember(state.dailyData, state.cumulativeData, state.viewMode, zone) {
        when (state.viewMode) {
            HeatmapViewMode.DAILY, HeatmapViewMode.WEEKLY -> groupByWeeks(state.dailyData, zone)
            HeatmapViewMode.CUMULATIVE -> groupByWeeks(state.cumulativeData, zone)
        }
    }

    // 月份标签（三种视图共用同一计算，位置与每日视图完全一致；月份名按当前 locale）
    val monthNames = List(12) { stringResource(monthNameRes[it]) }
    val monthLabels = remember(gridWeeks, zone, monthNames) {
        getMonthLabels(gridWeeks, zone, monthNames)
    }

    val cols = gridWeeks.size
    val rows = 7

    // 加载中或无数据
    if (state.loading || cols == 0) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(if (state.loading) 200.dp else 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.loading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Text(noDataText.ifEmpty { "No data" }, color = mutedColor, fontSize = 14.sp)
            }
        }
        return
    }

    // Canvas 尺寸计算
    val canvasWidthDp = (blockSize + gap) * cols - gap
    val gridHeightDp = (blockSize + gap) * rows - gap
    val monthLabelHeightDp = 20.dp
    val totalHeightDp = gridHeightDp + monthLabelHeightDp

    val gridHeightPx = with(density) { gridHeightDp.toPx() }
    val labelGapPx = with(density) { 4.dp.toPx() }

    // 月份标签画笔
    val monthLabelPaint = remember(mutedColor, density) {
        Paint().apply {
            textSize = with(density) { 12.sp.toPx() }
            color = mutedColor.toArgb()
            isAntiAlias = true
        }
    }

    // 横向滚动状态（图例固定在外层，不随热力图滚动）
    val scrollState = rememberScrollState()

    // 打开页面 / 切换视图 / 切换年份时，横向滚动自动定位到最右（最新数据），并清除滑动查看指示器
    LaunchedEffect(cols, state.viewMode, state.selectedYear) {
        indicatorDay = null
        indicatorWeek = null
        indicatorCol = -1
        indicatorRow = -1
        snapshotFlow { scrollState.maxValue }.first { it > 0 }
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(modifier = modifier) {
        // ── 热力图网格 + 月份标签（可横向滚动）──
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .semantics { contentDescription = accessibilityDesc.ifEmpty {
                    if (isWeekly) "Token activity heatmap, ${state.weeklyData.size} weeks"
                    else "Token activity heatmap, ${state.dailyData.size} days"
                } },
        ) {
            Canvas(
                modifier = Modifier
                    .size(canvasWidthDp, totalHeightDp)
                    .onGloballyPositioned { canvasWindowPos = it.positionInWindow().round() }
                    // 手势仲裁（与点击共存）：慢速横向拖动/长按=滑动查看（锁定图表、指示器跟手），
                    // 快速横向拖动或纵向拖动=放行给 scrollable 正常滚动；三种视图均启用。
                    // ⚠️ 必须声明在下方 detectTapGestures 之前：仲裁先收到 Main pass 事件，
                    // VIEW 模式先 consume 可阻止 tap 误触发（顺序承重，勿调换）
                    .pointerInput(state, cellStepPx, blockSizePx, gridWeeks, state.viewMode) {
                        // 慢速/快速分界：密度无关（dp/s），保证不同设备手感一致
                        val viewSpeedThresholdPxPerMs = with(density) { VIEW_SPEED_DP_PER_S.dp.toPx() } / 1000f
                        // 指示器更新：手指所在列/行的格子（所见即所得）。
                        // change.position 已是滚动内容内的本地坐标（与 detectTapGestures 命中一致），
                        // 不能再加 scrollState.value，否则滚动后列索引整体偏右
                        fun updateIndicator(pos: Offset) {
                            val col = (pos.x / cellStepPx).toInt().coerceIn(0, cols - 1)
                            val row = (pos.y / cellStepPx).toInt().coerceIn(0, rows - 1)
                            if (isWeekly) {
                                // 每周视图：整列=同一周（柱状图），读数条显示该周范围与总量
                                val week = state.weeklyData.getOrNull(col)
                                if (week != null) {
                                    indicatorDay = null
                                    indicatorWeek = week
                                    indicatorCol = col
                                    indicatorRow = row
                                } else {
                                    indicatorDay = null
                                    indicatorWeek = null
                                    indicatorCol = -1
                                    indicatorRow = -1
                                }
                            } else {
                                // 每日/累计视图：格子=一天（累计视图 tokens 为累计值）
                                val cell = gridWeeks.getOrNull(col)?.getOrNull(row)
                                if (cell != null) {
                                    indicatorWeek = null
                                    indicatorDay = cell
                                    indicatorCol = col
                                    indicatorRow = row
                                } else {
                                    indicatorWeek = null
                                    indicatorDay = null
                                    indicatorCol = -1
                                    indicatorRow = -1
                                }
                            }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 模式判定（本次手势内锁定）：
                            // - 按住不动/慢速横向拖动 → VIEW：指示器跟手、消费事件锁定图表滚动
                            // - 快速横向拖动或纵向主导 → SCROLL：不消费，放行给 scrollable 正常滚动
                            var mode: DragMode? = null
                            var lastPos = down.position
                            var lastTime = SystemClock.uptimeMillis()
                            var totalDx = 0f
                            var totalDy = 0f
                            val slop = viewConfiguration.touchSlop
                            val downTime = lastTime
                            // 长按阈值：按住不动（含微移未超 slop）超过系统长按时长 → 直接进入查看模式。
                            // 静止时系统不一定派发 move 事件，故用 withTimeoutOrNull 做超时心跳，不依赖事件频率。
                            // ⚠️ 此符号解析为 AwaitPointerEventScope 的框架成员（非 kotlinx 协程版），
                            // 勿补 import kotlinx.coroutines.withTimeoutOrNull（会被接口成员遮蔽且语义不同）
                            val longPressMs = viewConfiguration.longPressTimeoutMillis

                            // 阶段一：模式判定。位移超 slop 按帧速度判 VIEW/SCROLL；提前抬起 = 点击放行给 tap；
                            // 长按超时（手指未动）→ VIEW
                            while (mode == null) {
                                val remaining = longPressMs - (SystemClock.uptimeMillis() - downTime)
                                val event = if (remaining > 0) {
                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                } else {
                                    null
                                }
                                if (event == null) {
                                    // 长按成立：按住不动（或微移 < slop）→ 查看模式
                                    mode = DragMode.VIEW
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break // 快速抬起 = 点击，交给 detectTapGestures
                                val cur = change.position
                                val now = SystemClock.uptimeMillis()
                                // 帧瞬时速度：停顿后快甩的帧速度大 → 判为滚动；慢速拖动各帧速度都小 → 判为查看
                                val dxFrame = cur.x - lastPos.x
                                val dyFrame = cur.y - lastPos.y
                                val dtFrame = (now - lastTime).coerceAtLeast(1L)
                                val speedX = abs(dxFrame) / dtFrame
                                lastPos = cur
                                lastTime = now
                                totalDx += dxFrame
                                totalDy += dyFrame
                                // 首次超过触摸阈值时判定模式（本次手势内锁定）：
                                // 横向主导且慢速 → VIEW（查看）；否则 → SCROLL（滚动）
                                if (abs(totalDx) > slop || abs(totalDy) > slop) {
                                    mode = if (abs(totalDx) > abs(totalDy) && speedX < viewSpeedThresholdPxPerMs) {
                                        DragMode.VIEW
                                    } else {
                                        DragMode.SCROLL
                                    }
                                    if (mode == DragMode.VIEW) {
                                        // 判决事件立即消费：否则同帧（Main pass leaf→root）会漏到父 scrollable，
                                        // 其越过自身 slop 先滚一帧（约 0~4px 抖动）后才被 phase2 消费取消
                                        change.consume()
                                    }
                                }
                            }

                            if (mode == DragMode.VIEW) {
                                // 进入查看模式立即用最后已知位置更新指示器（长按超时瞬间就要显示，不等下一个事件）
                                updateIndicator(lastPos)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    // 先消费再判抬起：up 若不消费会漏到 detectTapGestures 误触发 onTap 浮层
                                    // （waitForUpOrCancellation 对 consumed up 返回 null → tap 取消），
                                    // 与慢拖路径行为保持一致
                                    updateIndicator(change.position)
                                    change.consume() // 消费事件锁定图表滚动
                                    if (!change.pressed) break // 抬起：保留最后预览的格子
                                }
                            } else if (mode == DragMode.SCROLL) {
                                // 滚动模式：清除上次预览的指示器，避免滚动后残留过期信息；
                                // 不消费事件，scrollable 正常滚动（含惯性）
                                indicatorDay = null
                                indicatorWeek = null
                                indicatorCol = -1
                                indicatorRow = -1
                            }
                        }
                    }
                    .pointerInput(state, cellStepPx, blockSizePx, gridWeeks) {
                    detectTapGestures(onTap = { offset ->
                        // 点击时清除滑动查看残留的预览（抬起后保留的指示器），避免与浮层同时显示
                        indicatorDay = null
                        indicatorWeek = null
                        indicatorCol = -1
                        indicatorRow = -1
                        // 点击落在格子间隙内时不触发（Minor：命中精度）
                        if (offset.x % cellStepPx >= blockSizePx || offset.y % cellStepPx >= blockSizePx) {
                            return@detectTapGestures
                        }
                        val col = (offset.x / cellStepPx).toInt()
                        val row = (offset.y / cellStepPx).toInt()
                        if (col < 0 || col >= gridWeeks.size || row < 0 || row >= rows) {
                            return@detectTapGestures
                        }
                        val cellCenterX = (col * cellStepPx + blockSizePx / 2f).roundToInt()
                        val cellCenterY = (row * cellStepPx + blockSizePx / 2f).roundToInt()
                        val anchor = IntOffset(canvasWindowPos.x + cellCenterX, canvasWindowPos.y + cellCenterY)

                        if (isWeekly) {
                            // 每周视图（柱状图）：该列任意格子都对应同一周 → 点击触发该周浮层；
                            // 0 用量周也有 1 格 Level 0 浅色格，同样可点击（显示「使用了0token」）
                            val weekData = state.weeklyData.getOrNull(col) ?: return@detectTapGestures
                            onWeekClick(weekData, anchor)
                        } else {
                            // 每日/累计视图：点击 (col, row) 对应格子（空位不可点）
                            val cell = gridWeeks[col][row] ?: return@detectTapGestures
                            onDayClick(cell, anchor)
                        }
                    })
                },
        ) {
            // 绘制格子：
            // - 每日/累计：7 行网格按天等级绘制，null 空位不绘制
            // - 每周：离散柱状图——列=自然周，整列 7 格全部绘制：
            //   底部 barHeight 格 = 该周等级颜色（柱：0 用量周 1 格 Level 0、非零周 2~7 格），
            //   其余格 = Level 0 浅色（空白格不隐藏，网格完整）；
            //   选中周整列加主题色描边强调
            if (isWeekly) {
                state.weeklyData.forEachIndexed { weekIndex, weekData ->
                    val level = weekData.level.coerceIn(0, 5)
                    val barHeight = weekData.barHeight.coerceIn(1, 7)
                    val isSelected = weekData == selectedWeek
                    val x = weekIndex * cellStepPx
                    for (row in 0 until rows) {
                        val inBar = row >= rows - barHeight
                        val y = row * cellStepPx
                        drawRoundRect(
                            color = if (inBar) colors[level] else colors[0],
                            topLeft = Offset(x, y),
                            size = Size(blockSizePx, blockSizePx),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        )
                        if (isSelected) {
                            drawRoundRect(
                                color = selectionColor,
                                topLeft = Offset(x, y),
                                size = Size(blockSizePx, blockSizePx),
                                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                                style = Stroke(width = selectionStrokePx),
                            )
                        }
                    }
                }
            } else {
                gridWeeks.forEachIndexed { weekIndex, week ->
                    week.forEachIndexed { dayIndex, dayData ->
                        if (dayData == null) return@forEachIndexed
                        val x = weekIndex * cellStepPx
                        val y = dayIndex * cellStepPx
                        drawRoundRect(
                            color = colors[dayData.level.coerceIn(0, 5)],
                            topLeft = Offset(x, y),
                            size = Size(blockSizePx, blockSizePx),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        )
                    }
                }
            }

            // 绘制月份标签（底部）
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                // labelY = 网格底部 + 间距 + 文字基线偏移
                val labelY = gridHeightPx + labelGapPx - monthLabelPaint.ascent()
                monthLabels.forEach { label ->
                    val x = label.weekIndex * cellStepPx
                    nativeCanvas.drawText(label.label, x, labelY, monthLabelPaint)
                }
            }

            // 预览格子高亮（三种视图，所见即所得）：手指所在格子加主题色边框。
            // 防御性校验：指示器指向的格子数据仍存在（防未来"同一年份数据刷新"后残留过期高亮）
            val indicatorValid = when {
                indicatorWeek != null -> state.weeklyData.getOrNull(indicatorCol) != null
                indicatorDay != null -> gridWeeks.getOrNull(indicatorCol)?.getOrNull(indicatorRow) != null
                else -> false
            }
            if (indicatorValid &&
                indicatorCol in 0 until cols && indicatorRow in 0 until rows
            ) {
                drawRoundRect(
                    color = selectionColor,
                    topLeft = Offset(indicatorCol * cellStepPx, indicatorRow * cellStepPx),
                    size = Size(blockSizePx, blockSizePx),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = selectionStrokePx * 1.5f),
                )
            }
            }
        }

        // 滑动查看读数条（三种视图）：显示手指所在格子的数据（每日/累计=当天，每周=该周范围），
        // 抬起后保留；固定高度占位，避免出现/消失导致图例上下跳动
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val d = indicatorDay
            val w = indicatorWeek
            val text = when {
                d != null -> stringResource(
                    R.string.heatmap_day_used,
                    formatDateChinese(d.dayTs, state.useUtc8, state.currentYear, activeLocale),
                    formatTokenChinese(d.tokens, activeLocale),
                )
                w != null -> stringResource(
                    R.string.heatmap_week_used,
                    formatWeekRangeText(w, state.useUtc8, state.selectedYear, state.dailyData.lastOrNull()?.dayTs, activeLocale),
                    formatTokenChinese(w.tokens, activeLocale),
                )
                else -> null
            }
            if (text != null) {
                Text(
                    text = text,
                    fontSize = 12.sp,
                    color = mutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 颜色图例（右下角，固定不随热力图横向滚动）──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lessText.ifEmpty { "Less" }, color = mutedColor, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            for (level in 0..5) {
                Box(
                    modifier = Modifier
                        .size(blockSize)
                        .background(colors[level], RoundedCornerShape(cornerRadius)),
                )
                if (level < 5) {
                    Spacer(Modifier.width(gap))
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(moreText.ifEmpty { "More" }, color = mutedColor, fontSize = 12.sp)
        }
    }
}