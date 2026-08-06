package com.rainy.token.ui.heatmap

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.rainy.token.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneOffset
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

/** 中文月份名称 */
private val ChineseMonthNames = listOf(
    "一月", "二月", "三月", "四月", "五月", "六月",
    "七月", "八月", "九月", "十月", "十一月", "十二月",
)

// ── 辅助类型与函数 ──────────────────────────────────────────

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

/** 从按周分组的每日数据计算月份标签 */
private fun getMonthLabels(weeks: List<List<HeatmapDayData?>>, zone: ZoneOffset): List<MonthLabel> {
    if (weeks.isEmpty()) return emptyList()

    val rawLabels = mutableListOf<MonthLabel>()
    var prevMonth = -1

    for (weekIndex in weeks.indices) {
        val firstDay = weeks[weekIndex].firstOrNull { it != null } ?: continue
        val month = monthFromTs(firstDay.dayTs, zone)

        if (weekIndex == 0 || month != prevMonth) {
            rawLabels.add(MonthLabel(weekIndex, ChineseMonthNames[month]))
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

    // 月份标签（三种视图共用同一计算，位置与每日视图完全一致）
    val monthLabels = remember(gridWeeks, zone) {
        getMonthLabels(gridWeeks, zone)
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

    // 打开页面 / 切换视图 / 切换年份时，横向滚动自动定位到最右（最新数据）
    LaunchedEffect(cols, isWeekly, state.selectedYear) {
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
                    .pointerInput(state, cellStepPx, blockSizePx, gridWeeks) {
                    detectTapGestures(onTap = { offset ->
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