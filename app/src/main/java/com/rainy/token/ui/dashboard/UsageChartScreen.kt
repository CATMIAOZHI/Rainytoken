package com.rainy.token.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.data.local.ChartBucket
import com.rainy.token.data.local.ChartGranularity
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 图表用色板（每个模型一个颜色，12 色 Material Design 500 色阶，足够区分 12 个模型）
internal val modelColors = listOf(
    Color(0xFFF44336), // Red
    Color(0xFFE91E63), // Pink
    Color(0xFF9C27B0), // Purple
    Color(0xFF673AB7), // Deep Purple
    Color(0xFF3F51B5), // Indigo
    Color(0xFF2196F3), // Blue
    Color(0xFF00BCD4), // Cyan
    Color(0xFF009688), // Teal
    Color(0xFF4CAF50), // Green
    Color(0xFFFF9800), // Orange
    Color(0xFF795548), // Brown
    Color(0xFF607D8B)  // Blue Grey
)

// Token 堆叠色板：命中缓存（浅粉·顶）、未命中输入（粉·中）、输出（深粉·底）
internal val tokenColors = listOf(
    Color(0xFFFFD1DC), // cache hit — 浅粉（上面）
    Color(0xFFFF85A2), // cache miss input — 粉色（中间）
    Color(0xFFE91E63)  // output — 深粉（下面）
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageChartScreen(
    onBack: () -> Unit,
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
                title = { Text("统计图表") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ─── 顶部控制：粒度 + 模型 ───
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 粒度下拉
                        Box {
                            TextButton(onClick = { granularityExpanded = true }) {
                                Text(state.granularity.label, color = StrawberryPink)
                                Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                            }
                            DropdownMenu(granularityExpanded, { granularityExpanded = false }) {
                                ChartGranularity.entries.forEach { g ->
                                    DropdownMenuItem(
                                        text = { Text(g.label) },
                                        onClick = {
                                            granularityExpanded = false
                                            when (g) {
                                                ChartGranularity.CUSTOM_DAY_HOURLY -> showCustomDayPicker = true
                                                ChartGranularity.CUSTOM_MONTH_DAILY -> showCustomMonthPicker = true
                                                ChartGranularity.CUSTOM_RANGE_DAILY -> showCustomRangeStart = true
                                                else -> viewModel.setGranularity(g)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 模型多选下拉
                        Box {
                            val modelLabel = if (state.selectedModels.isEmpty()) "全部模型"
                            else "${state.selectedModels.size} 个模型"
                            TextButton(onClick = { modelExpanded = true }) {
                                Text(modelLabel, color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                                Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                            }
                            DropdownMenu(modelExpanded, { modelExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("全部模型", fontWeight = FontWeight.Bold) },
                                    onClick = { modelExpanded = false; viewModel.selectAllModels() }
                                )
                                state.allModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(
                                                    checked = state.selectedModels.isEmpty() || model in state.selectedModels,
                                                    onCheckedChange = { viewModel.toggleModel(model) }
                                                )
                                                Text(model, modifier = Modifier.padding(start = 4.dp))
                                            }
                                        },
                                        onClick = { viewModel.toggleModel(model) }
                                    )
                                }
                            }
                        }
                    }
                }

                // ─── 图表1：消耗金额（按模型堆叠柱状图） ───
                item {
                    val models = state.selectedModels.ifEmpty { state.allModels.toSet() }
                    val costTotal = state.buckets.sumOf { it.totalCost.toDouble() / 100_000_000.0 }
                    ChartCard(
                        title = "消耗金额 (USD)",
                        summary = "$${String.format(Locale.US, "%.4f", costTotal)}",
                        onSummaryClick = { showCostDetail = true }
                    ) {
                        StackedBarChart(
                            buckets = state.buckets,
                            valueSelector = { it.totalCost.toDouble() / 100_000_000.0 },
                            stackSelector = { bucket ->
                                models.mapIndexedNotNull { idx, model ->
                                    val v = bucket.byModel[model]?.cost ?: return@mapIndexedNotNull null
                                    v.toDouble() / 100_000_000.0 to modelColors[idx % modelColors.size]
                                }
                            },
                            stackLabels = { bucket ->
                                models.mapNotNull { model ->
                                    if (bucket.byModel[model] != null) model else null
                                }
                            },
                            formatValue = { "$${String.format(Locale.US, "%.4f", it)}" },
                            granularity = state.granularity,
                            legendItems = models.mapIndexed { idx, m ->
                                m to modelColors[idx % modelColors.size]
                            }
                        )
                    }
                }

                // ─── 图表2：API 请求次数 ───
                item {
                    val reqTotal = state.buckets.sumOf { it.totalRequests }
                    ChartCard(
                        title = "API 请求次数",
                        summary = "${reqTotal}次",
                        onSummaryClick = { showReqDetail = true }
                    ) {
                        LineChart(
                            buckets = state.buckets,
                            valueSelector = { it.totalRequests.toFloat() },
                            lineColor = StrawberryPink,
                            formatValue = { "${it.toInt()}次" },
                            granularity = state.granularity
                        )
                    }
                }

                // ─── 图表3：Token 消耗（底→顶：输出 / 未命中输入 / 命中缓存） ───
                item {
                    val tokTotal = state.buckets.sumOf { it.cacheHitTokens + it.inputTokens + it.outputTokens }
                    ChartCard(
                        title = "Token 消耗",
                        summary = formatTokenComma(tokTotal),
                        onSummaryClick = { showTokenDetail = true }
                    ) {
                        StackedBarChart(
                            buckets = state.buckets,
                            valueSelector = { (it.cacheHitTokens + it.inputTokens + it.outputTokens).toDouble() },
                            stackSelector = { bucket ->
                                listOfNotNull(
                                    bucket.outputTokens.toDouble() to tokenColors[2],       // 输出 — 底
                                    bucket.inputTokens.toDouble() to tokenColors[1],        // 输入(未命中) — 中
                                    bucket.cacheHitTokens.toDouble() to tokenColors[0]      // 命中缓存 — 顶
                                )
                            },
                            stackLabels = { listOf("输出", "输入(未命中)", "命中缓存") },
                            formatValue = { formatTokenComma(it.toLong()) },
                            granularity = state.granularity,
                            tooltipReversed = true
                        )
                    }
                    ChartLegend(
                        items = listOf(
                            "输入(未命中)" to tokenColors[1],
                            "命中缓存" to tokenColors[0],
                            "输出" to tokenColors[2]
                        )
                    )
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        // ─── 自定义日期选择器 ───
        if (showCustomDayPicker) {
            DateTimePickerDialog(
                title = "选择日期",
                onConfirm = { ms ->
                    val utc = java.time.ZoneOffset.UTC
                    val dayStart = java.time.Instant.ofEpochMilli(ms).atOffset(utc).toLocalDate()
                        .atStartOfDay(utc).toInstant().toEpochMilli()
                    viewModel.setCustomDayRange(dayStart, dayStart + 86400_000L - 1)
                    showCustomDayPicker = false
                },
                onDismiss = { showCustomDayPicker = false }
            )
        }
        if (showCustomMonthPicker) {
            DateTimePickerDialog(
                title = "选择月份（任意一天）",
                onConfirm = { ms ->
                    viewModel.setCustomMonth(ms)
                    showCustomMonthPicker = false
                },
                onDismiss = { showCustomMonthPicker = false }
            )
        }
        if (showCustomRangeStart) {
            DateTimePickerDialog(
                title = "开始日期",
                onConfirm = { ms ->
                    customRangeStartMs = ms
                    showCustomRangeStart = false
                    showCustomRangeEnd = true
                },
                onDismiss = { showCustomRangeStart = false }
            )
        }
        if (showCustomRangeEnd) {
            DateTimePickerDialog(
                title = "结束日期",
                onConfirm = { ms ->
                    val utc = java.time.ZoneOffset.UTC
                    val startDay = java.time.Instant.ofEpochMilli(customRangeStartMs).atOffset(utc).toLocalDate()
                        .atStartOfDay(utc).toInstant().toEpochMilli()
                    val endDay = java.time.Instant.ofEpochMilli(ms).atOffset(utc).toLocalDate()
                        .atStartOfDay(utc).toInstant().toEpochMilli() + 86400_000L - 1
                    viewModel.setCustomRange(startDay, endDay)
                    showCustomRangeEnd = false
                },
                onDismiss = { showCustomRangeEnd = false }
            )
        }

        // ─── 详情弹窗 ───
        val models = state.selectedModels.ifEmpty { state.allModels.toSet() }
        if (showCostDetail) {
            AlertDialog(
                onDismissRequest = { showCostDetail = false },
                title = { Text("消费明细", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        models.forEach { model ->
                            val total = state.buckets.sumOf { it.byModel[model]?.cost ?: 0L }
                            if (total > 0) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(model, style = MaterialTheme.typography.bodySmall)
                                    Text("$${String.format(Locale.US, "%.4f", total / 100_000_000.0)}",
                                        color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCostDetail = false }) { Text("关闭") } }
            )
        }
        if (showReqDetail) {
            AlertDialog(
                onDismissRequest = { showReqDetail = false },
                title = { Text("调用次数明细", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        models.forEach { model ->
                            val total = state.buckets.sumOf { it.byModel[model]?.requests ?: 0 }
                            if (total > 0) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(model, style = MaterialTheme.typography.bodySmall)
                                    Text("${total}次", color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showReqDetail = false }) { Text("关闭") } }
            )
        }
        if (showTokenDetail) {
            val hitTotal = state.buckets.sumOf { it.cacheHitTokens }
            val missTotal = state.buckets.sumOf { it.inputTokens }
            val outTotal = state.buckets.sumOf { it.outputTokens }
            AlertDialog(
                onDismissRequest = { showTokenDetail = false },
                title = { Text("Token 明细", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("命中缓存", style = MaterialTheme.typography.bodySmall)
                            Text(formatTokenComma(hitTotal), color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("输入(未命中)", style = MaterialTheme.typography.bodySmall)
                            Text(formatTokenComma(missTotal), color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("输出", style = MaterialTheme.typography.bodySmall)
                            Text(formatTokenComma(outTotal), color = StrawberryPink, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showTokenDetail = false }) { Text("关闭") } }
            )
        }
    }
}

// ═══════════════════════════════════════════
// 图表卡片容器
// ═══════════════════════════════════════════

@Composable
internal fun ChartCard(
    title: String,
    summary: String = "",
    onSummaryClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = InkMuted, modifier = Modifier.weight(1f))
                if (summary.isNotEmpty()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = StrawberryPink,
                        fontWeight = FontWeight.Bold,
                        modifier = if (onSummaryClick != null)
                            Modifier.clickable { onSummaryClick() }
                        else Modifier
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ═══════════════════════════════════════════
// 堆叠柱状图（自适应宽度）
// ═══════════════════════════════════════════
@Composable
internal fun StackedBarChart(
    buckets: List<ChartBucket>,
    valueSelector: (ChartBucket) -> Double,
    stackSelector: (ChartBucket) -> List<Pair<Double, Color>>,
    stackLabels: (ChartBucket) -> List<String> = { emptyList() },
    tooltipReversed: Boolean = false,
    formatValue: (Double) -> String,
    granularity: ChartGranularity,
    legendItems: List<Pair<String, Color>> = emptyList(),
    useUtc8: Boolean = false
) {
    if (buckets.isEmpty()) {
        Text("暂无数据", color = InkMuted, style = MaterialTheme.typography.bodySmall)
        return
    }
    var tooltipBucket by remember { mutableStateOf<ChartBucket?>(null) }
    val density = LocalDensity.current
    val d = density.density
    val barCount = buckets.size
    val chartHPx = 160f * d
    val labelHPx = 20f * d

    val maxVal = buckets.maxOf { valueSelector(it) }.coerceAtLeast(0.0)
    val refTop = niceCeil(maxVal)
    val refHalf = refTop / 2.0
    // 以 refTop 为满刻度，柱子不会触顶
    val scale = refTop

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availPx = maxWidth.value * d
        val barAreaPx = availPx / barCount
        val barW = barAreaPx * 0.65f

        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp + 20.dp)
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            val idx = (offset.x / barAreaPx).toInt().coerceIn(0, barCount - 1)
                            tooltipBucket = buckets.getOrNull(idx)
                        }
                    }
                    .pointerInput(buckets) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val idx = (change.position.x / barAreaPx).toInt().coerceIn(0, barCount - 1)
                            tooltipBucket = buckets.getOrNull(idx)
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp + 20.dp)
                ) {
                    buckets.forEachIndexed { i, bucket ->
                        val x = i * barAreaPx + (barAreaPx - barW) / 2
                        var yBase = chartHPx
                        val stacks = stackSelector(bucket)
                        stacks.forEach { (value, color) ->
                            val h = (value / scale * chartHPx).toFloat().coerceAtLeast(0f)
                            drawRect(color, Offset(x, yBase - h), Size(barW, h))
                            yBase -= h
                        }
                        val (label, show) = formatChartTime(bucket.ts, granularity, i, barCount, useUtc8)
                        if (show) {
                            drawContext.canvas.nativeCanvas.drawText(
                                label, x + barW / 2, chartHPx + labelHPx - 4f * d,
                                android.graphics.Paint().apply {
                                    color = android.graphics.Color.GRAY
                                    textSize = 10f * d * density.fontScale
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                            )
                        }
                    }
                    // ─── 参考线 ───
                    val refY = chartHPx - (refTop / scale * chartHPx).toFloat()
                    val refHalfY = chartHPx - (refHalf / scale * chartHPx).toFloat()
                    drawLine(Color.LightGray, Offset(0f, refY), Offset(size.width, refY), strokeWidth = 0.5f * d)
                    drawLine(Color.LightGray, Offset(0f, refHalfY), Offset(size.width, refHalfY), strokeWidth = 0.5f * d)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 8f * d * density.fontScale
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refTop), 2f * d, refY - 2f * d, paint)
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refHalf), 2f * d, refHalfY - 2f * d, paint)
                }
            }

            // 动态图例
            if (legendItems.isNotEmpty()) {
                ChartLegend(items = legendItems)
            }

            tooltipBucket?.let { bucket ->
                val total = valueSelector(bucket)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(formatChartTime(bucket.ts, granularity, 0, 1, useUtc8).first, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("合计: ${formatValue(total)}", style = MaterialTheme.typography.bodySmall, color = StrawberryPink)
                        val labels = stackLabels(bucket)
                        val items = stackSelector(bucket).withIndex()
                        val display = if (tooltipReversed) items.toList().reversed() else items.toList()
                        display.forEach { (idx, pair) ->
                            val (v, c) = pair
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(8.dp).height(8.dp).background(c, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(4.dp))
                                val label = labels.getOrNull(idx) ?: ""
                                Text(if (label.isNotEmpty()) "$label ${formatValue(v)}" else formatValue(v),
                                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// 折线图（自适应宽度）
// ═══════════════════════════════════════════

@Composable
internal fun LineChart(
    buckets: List<ChartBucket>,
    valueSelector: (ChartBucket) -> Float,
    lineColor: Color,
    formatValue: (Float) -> String,
    granularity: ChartGranularity,
    useUtc8: Boolean = false
) {
    if (buckets.isEmpty()) {
        Text("暂无数据", color = InkMuted, style = MaterialTheme.typography.bodySmall)
        return
    }
    var tooltipBucket by remember { mutableStateOf<ChartBucket?>(null) }
    val density = LocalDensity.current
    val d = density.density
    val barCount = buckets.size
    val chartHPx = 140f * d
    val labelHPx = 20f * d
    val maxVal = buckets.maxOf { valueSelector(it) }.coerceAtLeast(0f)
    val refTop = niceCeil(maxVal.toDouble()).toFloat()
    val refHalf = refTop / 2f
    val scale = refTop

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availPx = maxWidth.value * d
        val barAreaPx = availPx / barCount

        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp + 20.dp)
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            val idx = (offset.x / barAreaPx).toInt().coerceIn(0, barCount - 1)
                            tooltipBucket = buckets.getOrNull(idx)
                        }
                    }
                    .pointerInput(buckets) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val idx = (change.position.x / barAreaPx).toInt().coerceIn(0, barCount - 1)
                            tooltipBucket = buckets.getOrNull(idx)
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp + 20.dp)
                ) {
                    val points = buckets.mapIndexed { i, b ->
                        Offset(i * barAreaPx + barAreaPx / 2, chartHPx - (valueSelector(b) / scale * chartHPx))
                    }
                    if (points.size >= 2) {
                        val path = Path().apply { moveTo(points[0].x, points[0].y) }
                        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                        drawPath(path, lineColor, style = Stroke(width = 2f * d))
                    }
                    points.forEach { p ->
                        drawCircle(lineColor, radius = 3f * d, center = p)
                    }
                    buckets.forEachIndexed { i, b ->
                        val (label, show) = formatChartTime(b.ts, granularity, i, barCount, useUtc8)
                        if (show) {
                            drawContext.canvas.nativeCanvas.drawText(
                                label, i * barAreaPx + barAreaPx / 2, chartHPx + labelHPx - 4f * d,
                                android.graphics.Paint().apply {
                                    color = android.graphics.Color.GRAY
                                    textSize = 10f * d * density.fontScale
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                            )
                        }
                    }
                    // ─── 参考线 ───
                    val refY = chartHPx - (refTop / scale * chartHPx)
                    val refHalfY = chartHPx - (refHalf / scale * chartHPx)
                    drawLine(Color.LightGray, Offset(0f, refY), Offset(size.width, refY), strokeWidth = 0.5f * d)
                    drawLine(Color.LightGray, Offset(0f, refHalfY), Offset(size.width, refHalfY), strokeWidth = 0.5f * d)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 8f * d * density.fontScale
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refTop), 2f * d, refY - 2f * d, paint)
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refHalf), 2f * d, refHalfY - 2f * d, paint)
                }
            }

            tooltipBucket?.let { bucket ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatChartTime(bucket.ts, granularity, 0, 1).first, style = MaterialTheme.typography.labelSmall)
                        Text(formatValue(valueSelector(bucket)), style = MaterialTheme.typography.bodySmall, color = StrawberryPink)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// 图例
// ═══════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartLegend(items: List<Pair<String, Color>>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        items.forEachIndexed { i, (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(10.dp).background(color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(3.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            if (i < items.size - 1) Spacer(Modifier.width(12.dp))
        }
    }
}

// ═══════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════

/** 返回格式化标签 + 是否应该绘制 */
private fun formatChartTime(ts: Long, granularity: ChartGranularity, index: Int, total: Int, useUtc8: Boolean = false): Pair<String, Boolean> {
val show = when (granularity) {
ChartGranularity.LAST_12H_10MIN ->
index == 0 || index == total - 1 || index % 12 == 0  // 每2小时（每12个10分钟桶）
ChartGranularity.LAST_24H_HOURLY ->
index == 0 || index == total - 1 || index % 3 == 0  // 每3小时
ChartGranularity.TODAY_HOURLY,
ChartGranularity.YESTERDAY_HOURLY,
ChartGranularity.CUSTOM_DAY_HOURLY ->
index == 0 || index == total - 1  // 只显示首尾小时
ChartGranularity.LAST_7D_DAILY,
ChartGranularity.THIS_MONTH_DAILY,
ChartGranularity.CUSTOM_MONTH_DAILY,
ChartGranularity.CUSTOM_RANGE_DAILY ->
index == 0 || index == total - 1  // 只显示首尾日期
else -> true  // 5小时全显示
}
val tz = if (useUtc8) java.util.TimeZone.getTimeZone("Asia/Shanghai") else java.util.TimeZone.getTimeZone("UTC")
val fmt = when (granularity) {
ChartGranularity.LAST_12H_10MIN,
ChartGranularity.LAST_5H_HOURLY,
ChartGranularity.LAST_24H_HOURLY,
ChartGranularity.TODAY_HOURLY,
ChartGranularity.YESTERDAY_HOURLY,
ChartGranularity.CUSTOM_DAY_HOURLY -> SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
timeZone = tz
}
else -> SimpleDateFormat("MM/dd", Locale.getDefault()).apply {
timeZone = tz
}
}
return fmt.format(Date(ts)) to show
}

private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${"%.1f".format(Locale.US, tokens / 1_000_000.0)}M"
    tokens >= 1_000 -> "${"%.1f".format(Locale.US, tokens / 1_000.0)}K"
    else -> "$tokens"
}

/** 千分位逗号格式，不缩写 */
internal fun formatTokenComma(tokens: Long): String {
    return String.format(Locale.US, "%,d", tokens)
}

/** 向上取整到漂亮数字（如9→10,883→1000,150M→200M） */
private fun niceCeil(v: Double): Double {
    if (v <= 0.0) return 1.0
    val exp = Math.floor(Math.log10(v)).toInt()
    val magnitude = Math.pow(10.0, exp.toDouble())
    val normalized = v / magnitude // 1..10
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 1.15 -> 1.15     // 15% 余量
        normalized <= 1.25 -> 1.25     // 25% 余量
        normalized <= 1.5 -> 1.5       // 50% 余量
        normalized <= 2.0 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 3.0 -> 3.0
        normalized <= 4.0 -> 4.0
        normalized <= 5.0 -> 5.0
        normalized <= 7.5 -> 7.5
        else -> 10.0
    }
    return nice * magnitude
}