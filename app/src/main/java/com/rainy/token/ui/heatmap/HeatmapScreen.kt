package com.rainy.token.ui.heatmap

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Token 活动热力图页面。
 *
 * Scaffold + TopAppBar 布局，包含视图切换器（每日/每周/累计）和 HeatmapCanvas。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(
    onBack: () -> Unit,
    viewModel: HeatmapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ── 浮层状态 ──
    var selectedDay: HeatmapDayData? by remember { mutableStateOf(null) }
    var selectedWeek: HeatmapWeekData? by remember { mutableStateOf(null) }
    // 浮层锚点：被点击格子中心在窗口中的位置
    var popupAnchor by remember { mutableStateOf<IntOffset?>(null) }
    // 浮层自身尺寸（用于锚点偏移计算）
    var popupSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // ── i18n 字符串 ──
    val noDataText = stringResource(R.string.heatmap_no_data)
    val lessText = stringResource(R.string.heatmap_legend_less)
    val moreText = stringResource(R.string.heatmap_legend_more)

    // ── 年份选择器状态 ──
    var yearMenuExpanded by remember { mutableStateOf(false) }
    val selectYearDesc = stringResource(R.string.heatmap_select_year)

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.heatmap_title))
                        if (state.availableYears.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            // 年份下拉选择器
                            Text(
                                text = "${state.selectedYear} ▾",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { yearMenuExpanded = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                    .semantics { contentDescription = "$selectYearDesc: ${state.selectedYear}" }
                            )
                            DropdownMenu(
                                expanded = yearMenuExpanded,
                                onDismissRequest = { yearMenuExpanded = false }
                            ) {
                                state.availableYears.sortedDescending().forEach { year ->
                                    DropdownMenuItem(
                                        text = { Text("$year") },
                                        onClick = {
                                            yearMenuExpanded = false
                                            viewModel.setYear(year)
                                            // 切换年份时关闭浮层
                                            selectedDay = null
                                            selectedWeek = null
                                            popupAnchor = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── 视图切换器 ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
            ) {
                HeatmapViewMode.entries.forEach { mode ->
                    val label = when (mode) {
                        HeatmapViewMode.DAILY -> stringResource(R.string.heatmap_view_daily)
                        HeatmapViewMode.WEEKLY -> stringResource(R.string.heatmap_view_weekly)
                        HeatmapViewMode.CUMULATIVE -> stringResource(R.string.heatmap_view_cumulative)
                    }
                    val isSelected = state.viewMode == mode
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else inkMuted(),
                        modifier = Modifier.clickable {
                            viewModel.setViewMode(mode)
                            // 切换视图时关闭浮层
                            selectedDay = null
                            selectedWeek = null
                            popupAnchor = null
                        }
                    )
                }
            }

            // ── 热力图 Canvas（带 Crossfade 淡入淡出动画）──
            Crossfade(
                targetState = state.viewMode,
                animationSpec = tween(150),
                label = "heatmap_view"
            ) { mode ->
                // 用 mode 参数构造视图快照：过渡期间新旧两个分支渲染各自模式，
                // 否则两者都读最新 viewMode，动画会变成空操作
                // 无障碍描述也在分支内按 mode 计算，避免淡出分支播报错误的模式
                val desc = if (mode == HeatmapViewMode.WEEKLY) {
                    stringResource(R.string.heatmap_accessibility_desc_weekly, state.weeklyData.size)
                } else {
                    stringResource(R.string.heatmap_accessibility_desc, state.dailyData.size)
                }
                HeatmapCanvas(
                    state = state.copy(viewMode = mode),
                    noDataText = noDataText,
                    lessText = lessText,
                    moreText = moreText,
                    accessibilityDesc = desc,
                    selectedWeek = selectedWeek,
                    onDayClick = { dayData, anchor ->
                        // 再次点击同一格子则关闭浮层（0 token 的天也可点击查看）
                        if (selectedDay == dayData) {
                            selectedDay = null
                            popupAnchor = null
                        } else {
                            selectedDay = dayData
                            selectedWeek = null
                            popupAnchor = anchor
                        }
                    },
                    onWeekClick = { weekData, anchor ->
                        if (selectedWeek == weekData) {
                            selectedWeek = null
                            popupAnchor = null
                        } else {
                            selectedWeek = weekData
                            selectedDay = null
                            popupAnchor = anchor
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ── 浮层 Popup（锚定到被点击格子的中心位置）──
    val day = selectedDay
    val week = selectedWeek
    val anchor = popupAnchor
    if (day != null || week != null) {
        val useUtc8 = state.useUtc8
        val zone = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
        val popupText = when {
            day != null -> "${formatDateChinese(day.dayTs, useUtc8, state.currentYear)} 使用了${formatTokenChinese(day.tokens)}token"
            week != null -> {
                val start = formatDateChinese(week.weekStartTs, useUtc8, state.selectedYear)
                // 末周/跨年周只显示到该周落在所选年内的有效日期（数据最后一天），
                // 避免出现未绘制的未来/次年空位日期；数据同源，不依赖实时时钟
                val dataEndTs = state.dailyData.lastOrNull()?.dayTs ?: (week.weekStartTs + 6L * 86_400_000L)
                val endTs = minOf(week.weekStartTs + 6L * 86_400_000L, dataEndTs)
                // 首列跨年（start 在上一年）时，end 与 start 不同年 → end 也强制带年份前缀，避免范围歧义
                val startDate = Instant.ofEpochMilli(week.weekStartTs).atOffset(zone).toLocalDate()
                val endDate = Instant.ofEpochMilli(endTs).atOffset(zone).toLocalDate()
                val end = if (endDate.year != startDate.year) {
                    "${endDate.year}年${endDate.monthValue}月${endDate.dayOfMonth}日"
                } else {
                    formatDateChinese(endTs, useUtc8, state.selectedYear)
                }
                "$start-$end 使用了${formatTokenChinese(week.tokens)}token"
            }
            else -> ""
        }
        // 浮层相对锚点的偏移：水平居中、优先显示在格子上方 8dp 处；
        // 上方空间不足时翻转到下方；x/y 均 clamp 在窗口内
        val gapPx = with(density) { 8.dp.toPx() }.roundToInt()
        val edgePaddingPx = with(density) { 8.dp.toPx() }.roundToInt()
        val configuration = LocalConfiguration.current
        val windowWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.roundToInt()
        val windowHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.roundToInt()
        val maxX = (windowWidthPx - popupSize.width - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        val maxY = (windowHeightPx - popupSize.height - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        val popupOffset = if (anchor != null) {
            val x = (anchor.x - popupSize.width / 2).coerceIn(edgePaddingPx, maxX)
            val y = if (anchor.y - popupSize.height - gapPx >= edgePaddingPx) {
                // 上方放得下：显示在格子上方
                (anchor.y - popupSize.height - gapPx).coerceIn(edgePaddingPx, maxY)
            } else {
                // 上方放不下：翻转到格子下方
                (anchor.y + gapPx).coerceIn(edgePaddingPx, maxY)
            }
            IntOffset(x, y)
        } else {
            IntOffset.Zero
        }
        Popup(
            alignment = Alignment.TopStart,
            offset = popupOffset,
            onDismissRequest = {
                selectedDay = null
                selectedWeek = null
                popupAnchor = null
            },
            properties = PopupProperties(focusable = true)
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .onGloballyPositioned { popupSize = it.size }
            ) {
                Text(
                    text = popupText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

// ── 格式化辅助函数 ──────────────────────────────────────────

/**
 * 将 Token 数按中文数量级格式化：
 * - 亿级（≥1亿）：1.9亿
 * - 万级（≥1万）：11.2万 / 1124.5万
 * - 万以下：原始数字
 *
 * 注意：万级先舍入到一位小数再判断是否升亿（如 99999999 → "1.0亿"，而非 "10000.0万"）。
 */
private fun formatTokenChinese(tokens: Long): String {
    return when {
        tokens >= 1_0000_0000 -> {
            val v = tokens / 1_0000_0000.0
            if (v >= 100) "${v.roundToInt()}亿"
            else "${"%.1f".format(v)}亿"
        }
        tokens >= 1_0000 -> {
            val v = tokens / 1_0000.0
            // 先舍入到一位小数，避免 "99999999 → 10000.0万"
            val rounded = (v * 10).roundToInt() / 10.0
            if (rounded >= 10000) {
                // 万级溢出升亿：9999.99万 → 1.0亿
                val v2 = rounded / 10000.0
                if (v2 >= 100) "${v2.roundToInt()}亿"
                else "${"%.1f".format(v2)}亿"
            } else {
                "${"%.1f".format(v)}万"  // 始终保留一位小数
            }
        }
        else -> "$tokens"
    }
}

/**
 * 将时间戳格式化为 "7月9日" 格式（月+日），按与数据分桶一致的时区。
 * 日期所在年份与 [yearContext] 不同时，前缀加年份（如 "2024年7月9日"）。
 *
 * 日浮层传 [currentYear]（查看往年时带年份前缀）；周浮层两端传 [selectedYear]，
 * 使跨年周（如 12月28日-次年1月3日）的结束日带 "2026年" 前缀，避免范围倒挂。
 */
private fun formatDateChinese(ts: Long, useUtc8: Boolean, yearContext: Int): String {
    val zone = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
    val date = Instant.ofEpochMilli(ts).atOffset(zone).toLocalDate()
    val yearPrefix = if (date.year != yearContext) "${date.year}年" else ""
    return "$yearPrefix${date.monthValue}月${date.dayOfMonth}日"
}