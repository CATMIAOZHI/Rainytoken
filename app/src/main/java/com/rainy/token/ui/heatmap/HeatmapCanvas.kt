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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainy.token.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneOffset

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

/** 从 UTC 时间戳获取星期几 (0=Sunday, 6=Saturday) */
private fun dayOfWeekFromTs(ts: Long): Int {
    val dayOfWeek = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).dayOfWeek.value
    return dayOfWeek % 7 // Sunday=7 → 0
}

/** 从 UTC 时间戳获取月份 (0=January) */
private fun monthFromTs(ts: Long): Int {
    return Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).monthValue - 1
}

/**
 * 将每日数据按周分组（参考 react-activity-calendar 的 groupByWeeks 逻辑）。
 * 周起始日为周日（weekStart = 0）。
 * 返回 List<List<HeatmapDayData?>>，外层为周，内层为星期 0-6，null 表示空位。
 */
private fun groupByWeeks(dailyData: List<HeatmapDayData>): List<List<HeatmapDayData?>> {
    if (dailyData.isEmpty()) return emptyList()

    val firstDow = dayOfWeekFromTs(dailyData[0].dayTs)

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
private fun getMonthLabels(weeks: List<List<HeatmapDayData?>>): List<MonthLabel> {
    if (weeks.isEmpty()) return emptyList()

    val rawLabels = mutableListOf<MonthLabel>()
    var prevMonth = -1

    for (weekIndex in weeks.indices) {
        val firstDay = weeks[weekIndex].firstOrNull { it != null } ?: continue
        val month = monthFromTs(firstDay.dayTs)

        if (weekIndex == 0 || month != prevMonth) {
            rawLabels.add(MonthLabel(weekIndex, ChineseMonthNames[month]))
            prevMonth = month
        }
    }

    return filterMonthLabels(rawLabels, weeks.size)
}

/** 从每周数据计算月份标签 */
private fun getWeeklyMonthLabels(weeklyData: List<HeatmapWeekData>): List<MonthLabel> {
    if (weeklyData.isEmpty()) return emptyList()

    val rawLabels = mutableListOf<MonthLabel>()
    var prevMonth = -1

    for (weekIndex in weeklyData.indices) {
        val month = monthFromTs(weeklyData[weekIndex].weekStartTs)

        if (weekIndex == 0 || month != prevMonth) {
            rawLabels.add(MonthLabel(weekIndex, ChineseMonthNames[month]))
            prevMonth = month
        }
    }

    return filterMonthLabels(rawLabels, weeklyData.size)
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
 * - [HeatmapViewMode.DAILY]：53×7 格子网格，每日 Token 使用
 * - [HeatmapViewMode.WEEKLY]：52×1 单行格子，每周 Token 总量
 * - [HeatmapViewMode.CUMULATIVE]：53×7 格子网格，累计 Token
 *
 * 使用 Compose Canvas 绘制圆角矩形格子，底部显示中文月份标签，
 * 支持水平滚动和格子点击。
 *
 * @param state UI 状态，包含数据与视图模式
 * @param modifier 布局修饰符
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
    onDayClick: (HeatmapDayData) -> Unit = {},
    onWeekClick: (HeatmapWeekData) -> Unit = {},
) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) DarkHeatmapColors else LightHeatmapColors
    val mutedColor = inkMuted()
    val density = LocalDensity.current

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

    // 构建网格数据（每日/累计视图用）
    val gridWeeks = remember(state.dailyData, state.cumulativeData, state.viewMode) {
        when (state.viewMode) {
            HeatmapViewMode.DAILY -> groupByWeeks(state.dailyData)
            HeatmapViewMode.CUMULATIVE -> groupByWeeks(state.cumulativeData)
            HeatmapViewMode.WEEKLY -> emptyList()
        }
    }

    // 月份标签
    val monthLabels = remember(gridWeeks, state.weeklyData, isWeekly) {
        if (isWeekly) getWeeklyMonthLabels(state.weeklyData) else getMonthLabels(gridWeeks)
    }

    val cols = if (isWeekly) state.weeklyData.size else gridWeeks.size
    val rows = if (isWeekly) 1 else 7

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
    val canvasWidthDp = cols * (blockSize + gap) - gap
    val gridHeightDp = rows * (blockSize + gap) - gap
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

    Column(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = accessibilityDesc.ifEmpty { "Token activity heatmap, ${if (isWeekly) state.weeklyData.size else state.dailyData.size} days" } },
    ) {
        // ── 热力图网格 + 月份标签 ──
        Canvas(
            modifier = Modifier
                .size(canvasWidthDp, totalHeightDp)
                .pointerInput(state, cellStepPx, blockSizePx) {
                    detectTapGestures(onTap = { offset ->
                        val col = (offset.x / cellStepPx).toInt()
                        val row = (offset.y / cellStepPx).toInt()

                        if (isWeekly) {
                            // 每周视图：单行，点击列索引对应周数据
                            if (col >= 0 && col < state.weeklyData.size &&
                                offset.y >= 0f && offset.y < blockSizePx
                            ) {
                                onWeekClick(state.weeklyData[col])
                            }
                        } else {
                            // 每日/累计视图：点击 (col, row) 对应格子
                            if (col >= 0 && col < gridWeeks.size && row >= 0 && row < 7) {
                                val cell = gridWeeks[col][row]
                                if (cell != null) {
                                    onDayClick(cell)
                                }
                            }
                        }
                    })
                },
        ) {
            // 绘制格子
            if (isWeekly) {
                // 每周视图：52 列 × 1 行
                state.weeklyData.forEachIndexed { weekIndex, weekData ->
                    val x = weekIndex * cellStepPx
                    drawRoundRect(
                        color = colors[weekData.level.coerceIn(0, 5)],
                        topLeft = Offset(x, 0f),
                        size = Size(blockSizePx, blockSizePx),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    )
                }
            } else {
                // 每日/累计视图：53 列 × 7 行
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

        Spacer(Modifier.height(8.dp))

        // ── 颜色图例（右下角）──
        Row(
            modifier = Modifier.width(canvasWidthDp),
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