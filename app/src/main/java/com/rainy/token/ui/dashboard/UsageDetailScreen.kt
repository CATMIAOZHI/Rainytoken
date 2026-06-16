package com.rainy.token.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.data.local.ChartGranularity
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * 用量详情页 —— 统计图表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDetailScreen(
    onBack: () -> Unit,
    onOpenOverview: () -> Unit,
    onOpenData: () -> Unit = {},
    viewModel: UsageChartViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var granularityExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var showCustomDayPicker by remember { mutableStateOf(false) }
    var showCustomMonthPicker by remember { mutableStateOf(false) }
    var showCustomRangeStart by remember { mutableStateOf(false) }
    var showCustomRangeEnd by remember { mutableStateOf(false) }
    var customRangeStartMs by remember { mutableStateOf(0L) }
    var showCostDetail by remember { mutableStateOf(false) }
    var showReqDetail by remember { mutableStateOf(false) }
    var showTokenDetail by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("用量详情") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = onOpenData) {
                        Text("详细数据", color = StrawberryPink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("加载中…", color = InkMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { granularityExpanded = true }) {
                                Text(state.granularity.label, color = StrawberryPink)
                                Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                            }
                            DropdownMenu(granularityExpanded, { granularityExpanded = false }) {
                                ChartGranularity.entries.forEach { g ->
                                    DropdownMenuItem(text = { Text(g.label) }, onClick = {
                                        granularityExpanded = false
                                        when (g) {
                                            ChartGranularity.CUSTOM_DAY_HOURLY -> showCustomDayPicker = true
                                            ChartGranularity.CUSTOM_MONTH_DAILY -> showCustomMonthPicker = true
                                            ChartGranularity.CUSTOM_RANGE_DAILY -> showCustomRangeStart = true
                                            else -> viewModel.setGranularity(g)
                                        }
                                    })
                                }
                            }
                        }
                        Text("UTC+0", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                        Spacer(Modifier.weight(1f))
                        Box {
                            val label = if (state.selectedModels.isEmpty()) "全部模型" else "${state.selectedModels.size} 个模型"
                            TextButton(onClick = { modelExpanded = true }) {
                                Text(label, color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                                Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                            }
                            DropdownMenu(modelExpanded, { modelExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("全部模型", fontWeight = FontWeight.Bold) },
                                    onClick = { modelExpanded = false; viewModel.selectAllModels() })
                                state.allModels.forEach { model ->
                                    DropdownMenuItem(text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(state.selectedModels.isEmpty() || model in state.selectedModels, { viewModel.toggleModel(model) })
                                            Text(model, modifier = Modifier.padding(start = 4.dp))
                                        }
                                    }, onClick = { viewModel.toggleModel(model) })
                                }
                            }
                        }
                    }
                }

                // 图表1
                item {
                    val models = state.selectedModels.ifEmpty { state.allModels.toSet() }
                    val costTotal = state.buckets.sumOf { it.totalCost.toDouble() / 100_000_000.0 }
                    ChartCard("消耗金额 (USD)", "$${String.format(Locale.US, "%.4f", costTotal)}", { showCostDetail = true }) {
                        StackedBarChart(state.buckets,
                            { it.totalCost.toDouble() / 100_000_000.0 },
                            { bucket -> models.mapIndexedNotNull { idx, m -> val v = bucket.byModel[m]?.cost ?: return@mapIndexedNotNull null; v.toDouble() / 100_000_000.0 to modelColors[idx % modelColors.size] } },
                            { bucket -> models.mapNotNull { if (bucket.byModel[it] != null) it else null } },
                            formatValue = { "$${String.format(Locale.US, "%.4f", it)}" },
                            granularity = state.granularity,
                            legendItems = models.mapIndexed { idx, m -> m to modelColors[idx % modelColors.size] })
                    }
                }
                // 图表2
                item {
                    val reqTotal = state.buckets.sumOf { it.totalRequests }
                    ChartCard("API 请求次数", "${reqTotal}次", { showReqDetail = true }) {
                        LineChart(state.buckets, { it.totalRequests.toFloat() }, StrawberryPink, { "${it.toInt()}次" }, state.granularity)
                    }
                }
                // 图表3
                item {
                    val tokTotal = state.buckets.sumOf { it.cacheHitTokens + it.inputTokens + it.outputTokens }
                    ChartCard("Token 消耗", formatTokenComma(tokTotal), { showTokenDetail = true }) {
                        StackedBarChart(state.buckets,
                            { (it.cacheHitTokens + it.inputTokens + it.outputTokens).toDouble() },
                            { bucket -> listOfNotNull(bucket.outputTokens.toDouble() to tokenColors[2], bucket.inputTokens.toDouble() to tokenColors[1], bucket.cacheHitTokens.toDouble() to tokenColors[0]) },
                            { listOf("输出", "输入(未命中)", "命中缓存") },
                            tooltipReversed = true,
                            formatValue = { formatTokenComma(it.toLong()) },
                            granularity = state.granularity)
                    }
                    ChartLegend(listOf("输入(未命中)" to tokenColors[1], "命中缓存" to tokenColors[0], "输出" to tokenColors[2]))
                }
                // 总统计入口
                item {
                    TextButton(onClick = onOpenOverview, modifier = Modifier.fillMaxWidth()) {
                        Text("📋 总统计", color = StrawberryPink, fontWeight = FontWeight.Bold)
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
        // 自定义日期选择器
        if (showCustomDayPicker) DateTimePickerDialog("选择日期", { ms ->
            val utc = ZoneOffset.UTC; val ds = Instant.ofEpochMilli(ms).atOffset(utc).toLocalDate().atStartOfDay(utc).toInstant().toEpochMilli()
            viewModel.setCustomDayRange(ds, ds + 86400_000L - 1); showCustomDayPicker = false }, { showCustomDayPicker = false })
        if (showCustomMonthPicker) DateTimePickerDialog("选择月份（任意一天）", { viewModel.setCustomMonth(it); showCustomMonthPicker = false }, { showCustomMonthPicker = false })
        if (showCustomRangeStart) DateTimePickerDialog("开始日期", { customRangeStartMs = it; showCustomRangeStart = false; showCustomRangeEnd = true }, { showCustomRangeStart = false })
        if (showCustomRangeEnd) DateTimePickerDialog("结束日期", { ms ->
            val utc = ZoneOffset.UTC; val sd = Instant.ofEpochMilli(customRangeStartMs).atOffset(utc).toLocalDate().atStartOfDay(utc).toInstant().toEpochMilli()
            val ed = Instant.ofEpochMilli(ms).atOffset(utc).toLocalDate().atStartOfDay(utc).toInstant().toEpochMilli() + 86400_000L - 1
            viewModel.setCustomRange(sd, ed); showCustomRangeEnd = false }, { showCustomRangeEnd = false })
        // 详情弹窗
        val models = state.selectedModels.ifEmpty { state.allModels.toSet() }
        if (showCostDetail) ChartDetailDialog("消费明细", { showCostDetail = false }) {
            models.forEach { model -> val t = state.buckets.sumOf { it.byModel[model]?.cost ?:0L }; if (t>0) DetailRow(model, "$${String.format(Locale.US, "%.4f", t/100_000_000.0)}") }
        }
        if (showReqDetail) ChartDetailDialog("调用次数明细", { showReqDetail = false }) {
            models.forEach { model -> val t = state.buckets.sumOf { it.byModel[model]?.requests ?:0 }; if (t>0) DetailRow(model, "${t}次") }
        }
        if (showTokenDetail) ChartDetailDialog("Token 明细", { showTokenDetail = false }) {
            DetailRow("命中缓存", formatTokenComma(state.buckets.sumOf { it.cacheHitTokens }))
            DetailRow("输入(未命中)", formatTokenComma(state.buckets.sumOf { it.inputTokens }))
            DetailRow("输出", formatTokenComma(state.buckets.sumOf { it.outputTokens }))
        }
    }
}

@Composable
private fun ChartDetailDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { androidx.compose.foundation.layout.Column { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
@Composable private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CustomTimeRangeRow(
    startMs: Long,
    endMs: Long,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onApply: () -> Unit
) {
    val fmt = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPickStart, modifier = Modifier.weight(1f)) {
                Text(
                    text = if (startMs > 0) "从 ${fmt.format(Date(startMs))}" else "开始时间",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (startMs > 0) StrawberryPink else InkMuted
                )
            }
            TextButton(onClick = onPickEnd, modifier = Modifier.weight(1f)) {
                Text(
                    text = if (endMs > 0) "至 ${fmt.format(Date(endMs))}" else "结束时间",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (endMs > 0) StrawberryPink else InkMuted
                )
            }
        }
        if (startMs > 0 && endMs > 0) {
            TextButton(onClick = onApply) {
                Text("应用自定义范围", color = StrawberryPink, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimePickerDialog(
    title: String,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val dateState = rememberDatePickerState()
    val timeState = rememberTimePickerState()
    val utc = remember { ZoneOffset.UTC }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = dateState.selectedDateMillis
                if (millis != null) {
                    val localDate = Instant.ofEpochMilli(millis).atOffset(utc).toLocalDate()
                    val localDateTime = LocalDateTime.of(
                        localDate.year, localDate.month, localDate.dayOfMonth,
                        timeState.hour, timeState.minute, 0, 0
                    )
                    val epochMs = localDateTime.atOffset(utc).toInstant().toEpochMilli()
                    onConfirm(epochMs)
                }
            }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    ) {
        Column {
            DatePicker(state = dateState)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "时间 ${
                    String.format("%02d", timeState.hour)
                }:${
                    String.format("%02d", timeState.minute)
                }",
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleMedium
            )
            TimePicker(state = timeState)
        }
    }
}
