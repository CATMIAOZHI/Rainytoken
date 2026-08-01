package com.rainy.token.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.data.local.ChartGranularity
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsageDetailScreen(
    onBack: () -> Unit,
    onOpenOverview: () -> Unit,
    onOpenData: () -> Unit = {},
    viewModel: UsageChartViewModel = hiltViewModel(),
    clearViewModel: UsageViewModel? = null  // non-null = CCGO, 显示清除按钮
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // OCGO 首次加载（CCGO 由 NavHost 的 setWorkspace 触发，不重复 load）
    LaunchedEffect(Unit) {
        if (clearViewModel == null) viewModel.load()
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var clearCountdown by remember { mutableStateOf(0) }
    var granularityExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var showCustomDayPicker by remember { mutableStateOf(false) }
    var showCustomMonthPicker by remember { mutableStateOf(false) }
    var showCustomRangeStart by remember { mutableStateOf(false) }
    var showCustomRangeEnd by remember { mutableStateOf(false) }
    var customRangeStartDate by remember { mutableStateOf<LocalDate?>(null) }
    var showCostDetail by remember { mutableStateOf(false) }
    var showReqDetail by remember { mutableStateOf(false) }
    var showTokenDetail by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_usage_detail)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (clearViewModel != null) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = onOpenData) {
                        Text(stringResource(R.string.action_view_raw_data), color = StrawberryPink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.common_loading), color = InkMuted)
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                // 容器自身宽度 > 700dp 时图表并排（自适应父容器，而非全局窗口）
                val wideEnough = maxWidth > 700.dp
                val hPad = if (wideEnough) 24.dp else 16.dp
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = hPad),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                            Box {
                                TextButton(onClick = { granularityExpanded = true }) {
                                    Text(stringResource(state.granularity.labelRes), color = StrawberryPink)
                                    Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                                }
                                DropdownMenu(granularityExpanded, { granularityExpanded = false }) {
                                    ChartGranularity.entries.forEach { g ->
                                        DropdownMenuItem(text = { Text(stringResource(g.labelRes)) }, onClick = {
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
                            TextButton(onClick = {
                                viewModel.toggleUtc8()
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (state.useUtc8) context.getString(R.string.msg_utc8_on) else context.getString(R.string.msg_utc0_on),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }) {
                                Text(
                                    if (state.useUtc8) "UTC+8」" else "UTC+0」",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.useUtc8) StrawberryPink else InkMuted,
                                    fontWeight = if (state.useUtc8) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    stringResource(R.string.usage_click_to_switch),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkMuted.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Normal
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Box {
                                val label = if (state.selectedModels.isEmpty()) stringResource(R.string.common_all_models) else stringResource(R.string.model_count, state.selectedModels.size)
                                TextButton(onClick = { modelExpanded = true }) {
                                    Text(label, color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                                    Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                                }
                                DropdownMenu(modelExpanded, { modelExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_all_models), fontWeight = FontWeight.Bold) },
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

                    if (wideEnough) {
                        // 面板够宽：前两张图表并排
                        item {
                            val models = state.selectedModels.ifEmpty { state.allModels.toSet() }
                            val costTotal = state.buckets.sumOf { it.totalCost.toDouble() / 100_000_000.0 }
                            val reqTotal = state.buckets.sumOf { it.totalRequests }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    ChartCard(stringResource(R.string.chart_cost), "$${String.format(Locale.US, "%.4f", costTotal)}", { showCostDetail = true }) {
                                        StackedBarChart(state.buckets,
                                            { it.totalCost.toDouble() / 100_000_000.0 },
                                            { bucket -> models.mapIndexedNotNull { idx, m -> val v = bucket.byModel[m]?.cost ?: return@mapIndexedNotNull null; v.toDouble() / 100_000_000.0 to modelColors[idx % modelColors.size] } },
                                            { bucket -> models.mapNotNull { if (bucket.byModel[it] != null) it else null } },
                                            formatValue = { "$${String.format(Locale.US, "%.4f", it)}" },
                                            granularity = state.granularity,
                                            legendItems = models.mapIndexed { idx, m ->
                                                m to modelColors[idx % modelColors.size]
                                            },
                                            useUtc8 = state.useUtc8
                                        )
                                    }
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    ChartCard(stringResource(R.string.chart_requests), stringResource(R.string.count_times, reqTotal), { showReqDetail = true }) {
                                        LineChart(state.buckets, { it.totalRequests.toFloat() }, StrawberryPink, { context.getString(R.string.count_times, it.toInt()) }, state.granularity, useUtc8 = state.useUtc8)
                                    }
                                }
                            }
                        }
                        item {
                            val tokTotal = state.buckets.sumOf { it.cacheHitTokens + it.inputTokens + it.outputTokens }
                            ChartCard(stringResource(R.string.chart_tokens), formatTokenComma(tokTotal), { showTokenDetail = true }) {
                                StackedBarChart(state.buckets,
                                    { (it.cacheHitTokens + it.inputTokens + it.outputTokens).toDouble() },
                                    { bucket -> listOfNotNull(bucket.outputTokens.toDouble() to tokenColors[2], bucket.inputTokens.toDouble() to tokenColors[1], bucket.cacheHitTokens.toDouble() to tokenColors[0]) },
                                    { listOf(context.getString(R.string.chart_stack_output), context.getString(R.string.chart_stack_input), context.getString(R.string.chart_stack_cache)) },
                                    formatValue = { formatTokenComma(it.toLong()) },
                                    granularity = state.granularity,
                                    tooltipReversed = true,
                                    useUtc8 = state.useUtc8
                                )
                            }
                            ChartLegend(listOf(stringResource(R.string.chart_stack_input) to tokenColors[1], stringResource(R.string.chart_stack_cache) to tokenColors[0], stringResource(R.string.chart_stack_output) to tokenColors[2]))
                        }
                    } else {
                        // 窄面板：三张图表纵向堆叠
                        item {
                            val models = state.selectedModels.ifEmpty { state.allModels.toSet() }
                            val costTotal = state.buckets.sumOf { it.totalCost.toDouble() / 100_000_000.0 }
                            ChartCard(stringResource(R.string.chart_cost), "$${String.format(Locale.US, "%.4f", costTotal)}", { showCostDetail = true }) {
                                StackedBarChart(state.buckets,
                                    { it.totalCost.toDouble() / 100_000_000.0 },
                                    { bucket -> models.mapIndexedNotNull { idx, m -> val v = bucket.byModel[m]?.cost ?: return@mapIndexedNotNull null; v.toDouble() / 100_000_000.0 to modelColors[idx % modelColors.size] } },
                                    { bucket -> models.mapNotNull { if (bucket.byModel[it] != null) it else null } },
                                    formatValue = { "$${String.format(Locale.US, "%.4f", it)}" },
                                    granularity = state.granularity,
                                    legendItems = models.mapIndexed { idx, m ->
                                        m to modelColors[idx % modelColors.size]
                                    },
                                    useUtc8 = state.useUtc8
                                )
                            }
                        }
                        item {
                            val reqTotal = state.buckets.sumOf { it.totalRequests }
                            ChartCard(stringResource(R.string.chart_requests), stringResource(R.string.count_times, reqTotal), { showReqDetail = true }) {
                                LineChart(state.buckets, { it.totalRequests.toFloat() }, StrawberryPink, { context.getString(R.string.count_times, it.toInt()) }, state.granularity, useUtc8 = state.useUtc8)
                            }
                        }
                        item {
                            val tokTotal = state.buckets.sumOf { it.cacheHitTokens + it.inputTokens + it.outputTokens }
                            ChartCard(stringResource(R.string.chart_tokens), formatTokenComma(tokTotal), { showTokenDetail = true }) {
                                StackedBarChart(state.buckets,
                                    { (it.cacheHitTokens + it.inputTokens + it.outputTokens).toDouble() },
                                    { bucket -> listOfNotNull(bucket.outputTokens.toDouble() to tokenColors[2], bucket.inputTokens.toDouble() to tokenColors[1], bucket.cacheHitTokens.toDouble() to tokenColors[0]) },
                                    { listOf(context.getString(R.string.chart_stack_output), context.getString(R.string.chart_stack_input), context.getString(R.string.chart_stack_cache)) },
                                    formatValue = { formatTokenComma(it.toLong()) },
                                    granularity = state.granularity,
                                    tooltipReversed = true,
                                    useUtc8 = state.useUtc8
                                )
                            }
                            ChartLegend(listOf(stringResource(R.string.chart_stack_input) to tokenColors[1], stringResource(R.string.chart_stack_cache) to tokenColors[0], stringResource(R.string.chart_stack_output) to tokenColors[2]))
                        }
                    }
                    item {
                        TextButton(onClick = onOpenOverview, modifier = Modifier.fillMaxWidth()) {
                            Text("📋 " + stringResource(R.string.title_overview), color = StrawberryPink, fontWeight = FontWeight.Bold)
                        }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
        // 自定义日期选择器
        if (showCustomDayPicker) DateOnlyPickerDialog(stringResource(R.string.date_select_day), { date ->
            viewModel.setCustomDay(date)
            showCustomDayPicker = false
        }, { showCustomDayPicker = false })
        if (showCustomMonthPicker) DateOnlyPickerDialog(stringResource(R.string.date_select_month), { date ->
            viewModel.setCustomMonth(date)
            showCustomMonthPicker = false
        }, { showCustomMonthPicker = false })
        if (showCustomRangeStart) DateOnlyPickerDialog(stringResource(R.string.date_pick_start), { date ->
            customRangeStartDate = date
            showCustomRangeStart = false
            showCustomRangeEnd = true
        }, { showCustomRangeStart = false })
        if (showCustomRangeEnd) {
            val startDate = customRangeStartDate
            DateOnlyPickerDialog(
                title = stringResource(R.string.date_pick_end),
                onConfirm = { endDate ->
                    if (startDate != null && !endDate.isBefore(startDate)) {
                        viewModel.setCustomRange(startDate, endDate)
                        showCustomRangeEnd = false
                    }
                },
                onDismiss = { showCustomRangeEnd = false },
                minDate = startDate
            )
        }
        val models = state.selectedModels.ifEmpty { state.allModels.toSet() }
        if (showCostDetail) ChartDetailDialog(stringResource(R.string.chart_detail_cost), { showCostDetail = false }) {
            models.forEach { model -> val t = state.buckets.sumOf { it.byModel[model]?.cost ?:0L }; if (t>0) DetailRow(model, "$${String.format(Locale.US, "%.4f", t/100_000_000.0)}") }
        }
        if (showReqDetail) ChartDetailDialog(stringResource(R.string.chart_detail_requests), { showReqDetail = false }) {
            models.forEach { model -> val t = state.buckets.sumOf { it.byModel[model]?.requests ?: 0 }; if (t>0) DetailRow(model, stringResource(R.string.count_times, t)) }
        }
        if (showTokenDetail) ChartDetailDialog(stringResource(R.string.chart_detail_tokens), { showTokenDetail = false }) {
            DetailRow(stringResource(R.string.chart_stack_cache), formatTokenComma(state.buckets.sumOf { it.cacheHitTokens }))
            DetailRow(stringResource(R.string.chart_stack_input), formatTokenComma(state.buckets.sumOf { it.inputTokens }))
            DetailRow(stringResource(R.string.chart_stack_output), formatTokenComma(state.buckets.sumOf { it.outputTokens }))
        }
        if (showClearDialog && clearViewModel != null) {
            val cd = clearCountdown
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showClearDialog = false; clearCountdown = 0 },
                title = { Text("⚠️ " + stringResource(R.string.dialog_clear_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(stringResource(R.string.dialog_clear_body))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.dialog_clear_confirm_question), fontWeight = FontWeight.SemiBold)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            clearCountdown = 0
                            clearViewModel?.clearAndResync()
                            onBack()
                        },
                        enabled = cd == 0
                    ) {
                        Text(
                            if (cd > 0) stringResource(R.string.action_confirm_countdown, cd) else stringResource(R.string.action_confirm_clear),
                            color = if (cd == 0) MaterialTheme.colorScheme.error else InkMuted
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false; clearCountdown = 0 }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
            LaunchedEffect(showClearDialog) {
                if (!showClearDialog) return@LaunchedEffect
                clearCountdown = 3
                for (i in 3 downTo 1) {
                    kotlinx.coroutines.delay(1000)
                    clearCountdown = i - 1
                }
            }
        }
    }
}

@Composable
private fun ChartDetailDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { androidx.compose.foundation.layout.Column { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } })
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
                    text = if (startMs > 0) stringResource(R.string.range_from, fmt.format(Date(startMs))) else stringResource(R.string.date_start),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (startMs > 0) StrawberryPink else InkMuted
                )
            }
            TextButton(onClick = onPickEnd, modifier = Modifier.weight(1f)) {
                Text(
                    text = if (endMs > 0) stringResource(R.string.range_to, fmt.format(Date(endMs))) else stringResource(R.string.date_end),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (endMs > 0) StrawberryPink else InkMuted
                )
            }
        }
        if (startMs > 0 && endMs > 0) {
            TextButton(onClick = onApply) {
                Text(stringResource(R.string.action_apply_custom_range), color = StrawberryPink, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateOnlyPickerDialog(
    title: String,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    minDate: LocalDate? = null
) {
    val utc = remember { ZoneOffset.UTC }
    val minDateStartMs = remember(minDate) { minDate?.atStartOfDay(utc)?.toInstant()?.toEpochMilli() }
    val selectableDates = remember(minDateStartMs) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                return minDateStartMs == null || utcTimeMillis >= minDateStartMs
            }
        }
    }
    val dateState = rememberDatePickerState(selectableDates = selectableDates)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = dateState.selectedDateMillis ?: return@TextButton
                    onConfirm(Instant.ofEpochMilli(millis).atOffset(utc).toLocalDate())
                },
                enabled = dateState.selectedDateMillis != null
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        Column {
            Text(
                text = title,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (minDate != null) {
                Text(
                    text = stringResource(R.string.date_end_before_start, minDate.formatDateLabel()),
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkMuted
                )
            }
            DatePicker(state = dateState)
        }
    }
}

private fun LocalDate.formatDateLabel(): String =
    String.format(Locale.US, "%04d-%02d-%02d", year, monthValue, dayOfMonth)

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
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        Column {
            DatePicker(state = dateState)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.time_hm,
                    String.format("%02d", timeState.hour),
                    String.format("%02d", timeState.minute)
                ),
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleMedium
            )
            TimePicker(state = timeState)
        }
    }
}