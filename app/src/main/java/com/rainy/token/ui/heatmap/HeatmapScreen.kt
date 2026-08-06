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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Popup
import androidx.compose.material3.PopupProperties
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneOffset

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

    // ── i18n 字符串 ──
    val noDataText = stringResource(R.string.heatmap_no_data)
    val lessText = stringResource(R.string.heatmap_legend_less)
    val moreText = stringResource(R.string.heatmap_legend_more)
    val accessibilityDesc = stringResource(R.string.heatmap_accessibility_desc, if (state.viewMode == HeatmapViewMode.WEEKLY) state.weeklyData.size else state.dailyData.size)

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.heatmap_title)) },
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
                modifier = Modifier.padding(horizontal = 4.dp, bottom = 12.dp)
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
                HeatmapCanvas(
                    state = state,
                    noDataText = noDataText,
                    lessText = lessText,
                    moreText = moreText,
                    accessibilityDesc = accessibilityDesc,
                    onDayClick = { dayData ->
                        // 再次点击同一格子则关闭浮层
                        if (selectedDay == dayData) {
                            selectedDay = null
                        } else {
                            selectedDay = dayData
                            selectedWeek = null
                        }
                    },
                    onWeekClick = { weekData ->
                        if (selectedWeek == weekData) {
                            selectedWeek = null
                        } else {
                            selectedWeek = weekData
                            selectedDay = null
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ── 浮层 Popup ──
    val day = selectedDay
    val week = selectedWeek
    if (day != null || week != null) {
        val popupText = when {
            day != null -> "${formatDateChinese(day.dayTs)} 使用了${formatTokenChinese(day.tokens)}token"
            week != null -> {
                val start = formatDateChinese(week.weekStartTs)
                val endTs = week.weekStartTs + 6L * 86_400_000L
                val end = formatDateChinese(endTs)
                "$start-$end 使用了${formatTokenChinese(week.tokens)}token"
            }
            else -> ""
        }
        Popup(
            alignment = androidx.compose.ui.Alignment.TopCenter,
            onDismissRequest = {
                selectedDay = null
                selectedWeek = null
            },
            properties = PopupProperties(focusable = true)
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(horizontal = 8.dp)
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
 */
private fun formatTokenChinese(tokens: Long): String {
    return when {
        tokens >= 1_0000_0000 -> {
            val v = tokens / 1_0000_0000.0
            if (v >= 100) "${v.toInt()}亿"
            else "${"%.1f".format(v)}亿"
        }
        tokens >= 1_0000 -> {
            val v = tokens / 1_0000.0
            "${"%.1f".format(v)}万"  // 始终保留一位小数
        }
        else -> "$tokens"
    }
}

/**
 * 将 UTC 时间戳格式化为 "7月9日" 格式（月+日，不加年）。
 */
private fun formatDateChinese(ts: Long): String {
    val instant = Instant.ofEpochMilli(ts)
    val date = instant.atOffset(ZoneOffset.UTC).toLocalDate()
    return "${date.monthValue}月${date.dayOfMonth}日"
}