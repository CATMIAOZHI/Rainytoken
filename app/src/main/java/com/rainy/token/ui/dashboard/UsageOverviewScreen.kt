package com.rainy.token.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.data.local.ModelStats
import com.rainy.token.data.local.OverviewStats
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 总统计页 —— 总览、按模型、按天统计。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsageOverviewScreen(
    onBack: () -> Unit,
    viewModel: UsageViewModel = hiltViewModel(),
    autoLoad: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // OCGO 首次加载（CCGO 由 setWorkspace 触发，不重复 load）
    LaunchedEffect(Unit) {
        if (autoLoad) viewModel.loadStats()
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var customStartMs by remember { mutableStateOf(0L) }
    var customEndMs by remember { mutableStateOf(0L) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_overview)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 时间筛选
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { menuExpanded = true }) {
                        Text(stringResource(uiState.timeFilter.labelRes), color = StrawberryPink, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                    }
                    Text("UTC+0", style = MaterialTheme.typography.bodySmall, color = inkMuted(), modifier = Modifier.padding(start = 4.dp))
                    DropdownMenu(menuExpanded, { menuExpanded = false }) {
                        listOf(TimeFilter.All, TimeFilter.Last5h, TimeFilter.Last24h,
                            TimeFilter.Today, TimeFilter.Yesterday,
                            TimeFilter.Last7Days, TimeFilter.Last30Days, TimeFilter.ThisMonth
                        ).forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(stringResource(filter.labelRes)) },
                                onClick = { menuExpanded = false; viewModel.setTimeFilter(filter) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.time_custom)) },
                            onClick = { menuExpanded = false; viewModel.setTimeFilter(TimeFilter.Custom(0L, 0L)) }
                        )
                    }
                }
            }
            if (uiState.timeFilter is TimeFilter.Custom) {
                item {
                    CustomTimeRangeRow(customStartMs, customEndMs,
                        { showStartPicker = true }, { showEndPicker = true },
                        { viewModel.setTimeFilter(TimeFilter.Custom(customStartMs, customEndMs)) })
                    Text(stringResource(R.string.usage_utc0_note), style = MaterialTheme.typography.bodySmall, color = inkMuted())
                }
            }
            // 总览
            uiState.overview?.let { overview ->
                item { OverviewCard(overview, false) }
                if (overview.cacheReadTokens > 0 || overview.cacheWriteTokens > 0) {
                    item { CacheBreakdownCard(overview) }
                }
            }
            // 按模型
            if (uiState.modelStats.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_by_model)) }
                items(uiState.modelStats, key = { it.model }) { ModelDetailRow(it) }
            }
            // 按天
            if (uiState.dailyStats.isNotEmpty()) {
                val totalPages = (uiState.dailyStats.size + DAILY_PAGE_SIZE - 1) / DAILY_PAGE_SIZE
                val startIdx = (uiState.dailyPage - 1) * DAILY_PAGE_SIZE
                val shown = uiState.dailyStats.drop(startIdx).take(DAILY_PAGE_SIZE)
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(stringResource(R.string.section_by_day)); Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { modelMenuExpanded = true }) {
                                Text(uiState.modelFilter ?: stringResource(R.string.common_all_models), color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                                Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                            }
                            DropdownMenu(modelMenuExpanded, { modelMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_all_models), fontWeight = FontWeight.Bold) },
                                    onClick = { modelMenuExpanded = false; viewModel.setModelFilter(null) })
                                uiState.modelStats.forEach { stat ->
                                    DropdownMenuItem(text = { Text(stat.model) },
                                        onClick = { modelMenuExpanded = false; viewModel.setModelFilter(stat.model) })
                                }
                            }
                        }
                    }
                }
                items(shown, key = { it.dayTs }) { DailyDetailRow(it) }
                if (totalPages > 1) {
                    item {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.prevDailyPage() }, enabled = uiState.dailyPage > 1) {
                                Text(stringResource(R.string.action_prev_page), color = StrawberryPink, style = MaterialTheme.typography.bodySmall) }
                            Text("${uiState.dailyPage} / $totalPages", style = MaterialTheme.typography.bodySmall, color = inkMuted())
                            TextButton(onClick = { viewModel.nextDailyPage() }, enabled = uiState.dailyPage < totalPages) {
                                Text(stringResource(R.string.action_next_page), color = StrawberryPink, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (showStartPicker) DateTimePickerDialog(stringResource(R.string.date_start), { customStartMs = it; showStartPicker = false }, { showStartPicker = false })
        if (showEndPicker) DateTimePickerDialog(stringResource(R.string.date_end), { customEndMs = it; showEndPicker = false }, { showEndPicker = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun OverviewCard(overview: OverviewStats, isExpanded: Boolean = false) {
    val inputTotal = overview.inputTokens + overview.cacheReadTokens
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (isExpanded) {
                // 平板：FlowRow 横向排列 6 项统计
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatItem(stringResource(R.string.usage_input_tokens), formatTokenCount(inputTotal))
                    StatItem(stringResource(R.string.usage_output_tokens), formatTokenCount(overview.outputTokens))
                    StatItem(stringResource(R.string.usage_reasoning_tokens), formatTokenCount(overview.reasoningTokens))
                    StatItem(stringResource(R.string.usage_total), formatTokenCount(overview.totalTokens))
                    StatItem(stringResource(R.string.usage_total_cost), "$${String.format(Locale.US, "%.4f", overview.totalCost / 100_000_000.0)}")
                    StatItem(stringResource(R.string.usage_records), "${overview.totalCount}")
                }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    StatItem(stringResource(R.string.usage_input_tokens), formatTokenCount(inputTotal))
                    StatItem(stringResource(R.string.usage_output_tokens), formatTokenCount(overview.outputTokens))
                    StatItem(stringResource(R.string.usage_reasoning_tokens), formatTokenCount(overview.reasoningTokens))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    StatItem(stringResource(R.string.usage_total), formatTokenCount(overview.totalTokens))
                    StatItem(stringResource(R.string.usage_total_cost), "$${String.format(Locale.US, "%.4f", overview.totalCost / 100_000_000.0)}")
                    StatItem(stringResource(R.string.usage_records), "${overview.totalCount}")
                }
            }
        }
    }
}

@Composable private fun CacheBreakdownCard(overview: OverviewStats) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage_cache_breakdown), style = MaterialTheme.typography.labelMedium, color = inkMuted())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                StatItem(stringResource(R.string.stat_cache_read), formatTokenCount(overview.cacheReadTokens))
                StatItem(stringResource(R.string.stat_cache_write), formatTokenCount(overview.cacheWriteTokens))
            }
            if (overview.cacheReadTokens > 0) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.usage_cache_read_note), style = MaterialTheme.typography.bodySmall, color = inkMuted())
            }
        }
    }
}

@Composable private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable private fun ModelDetailRow(stat: ModelStats) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stat.model, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.usage_calls, stat.count), style = MaterialTheme.typography.bodySmall, color = inkMuted())
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTokenCount(stat.totalTokens), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = StrawberryPink)
                Text("$${String.format(Locale.US, "%.4f", stat.totalCost / 100_000_000.0)}", style = MaterialTheme.typography.bodySmall, color = inkMuted())
            }
        }
    }
}

@Composable private fun DailyDetailRow(day: com.rainy.token.data.local.DailyStats) {
    val datePattern = stringResource(R.string.date_format_md)
    // 跟随系统语言（Locale.getDefault()），保证英文环境下日期符号与资源同语言
    val utcFmt = remember(datePattern, Locale.getDefault()) { SimpleDateFormat(datePattern, Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } }
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(utcFmt.format(Date(day.dayTs)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTokenCount(day.totalTokens), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = StrawberryPink)
                Text(stringResource(R.string.daily_count_cost, day.count, String.format(Locale.US, "%.4f", day.totalCost / 100_000_000.0)), style = MaterialTheme.typography.bodySmall, color = inkMuted())
            }
        }
    }
}

@Composable private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StrawberryPink)
        Text(label, style = MaterialTheme.typography.bodySmall, color = inkMuted())
    }
}

private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${"%.1f".format(Locale.US, tokens / 1_000_000.0)}M"
    tokens >= 1_000 -> "${"%.1f".format(Locale.US, tokens / 1_000.0)}K"
    else -> "$tokens"
}