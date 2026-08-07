package com.rainy.token.ui.heatmap

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

            // ── 年度统计（按所选年份，切换年份跟随变化）──
            // loading 期间显示占位，避免全 0 造成"无数据"假象
            val dash = "–"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatItem(
                    label = stringResource(R.string.heatmap_stats_total),
                    value = if (state.loading) dash else formatTokenChinese(state.stats.totalTokens),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.heatmap_stats_peak),
                    value = if (state.loading) dash else formatTokenChinese(state.stats.peakTokens),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.heatmap_stats_current_streak),
                    value = if (state.loading) {
                        dash
                    } else {
                        pluralStringResource(R.plurals.heatmap_stats_days, state.stats.currentStreak, state.stats.currentStreak)
                    },
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.heatmap_stats_max_streak),
                    value = if (state.loading) {
                        dash
                    } else {
                        pluralStringResource(R.plurals.heatmap_stats_days, state.stats.maxStreak, state.stats.maxStreak)
                    },
                    modifier = Modifier.weight(1f),
                )
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
        val popupText = when {
            day != null -> "${formatDateChinese(day.dayTs, useUtc8, state.currentYear)} 使用了${formatTokenChinese(day.tokens)}token"
            week != null -> formatWeekRangeText(week, useUtc8, state.selectedYear, state.dailyData.lastOrNull()?.dayTs)
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
            // 不拦截图表交互：浮层不获取焦点，点击/滑动直接作用于图表，
            // 点击其他格子即切换浮层，再次点击同一格子关闭
            properties = PopupProperties(focusable = false)
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

// ── 年度统计卡片（累计/峰值/当前连续/最长连续）──
@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
