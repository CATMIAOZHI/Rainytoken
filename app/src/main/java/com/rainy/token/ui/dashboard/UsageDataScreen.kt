package com.rainy.token.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * 详细数据页 —— 表格式原始记录浏览，支持时间/模型筛选 + 分页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDataScreen(
    onBack: () -> Unit,
    viewModel: UsageDataViewModel = hiltViewModel(),
    autoLoad: Boolean = true
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // OCGO 首次加载（CCGO 由 setWorkspace 触发，不重复 load）
    LaunchedEffect(Unit) {
        if (autoLoad) viewModel.loadData()
    }

    var timeMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var customStartMs by remember { mutableStateOf(0L) }
    var customEndMs by remember { mutableStateOf(0L) }
    var showCustomDayPicker by remember { mutableStateOf(false) }
    var showCustomMonthPicker by remember { mutableStateOf(false) }
    var rawRecord by remember { mutableStateOf<UsageRecord?>(null) }
    var pageInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("详细数据") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp)) {
            // ─── 筛选栏 ───
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                // 时间筛选
                Box {
                    TextButton(onClick = { timeMenuExpanded = true }) {
                        Text(state.timeFilter.label, color = StrawberryPink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                    }
                    DropdownMenu(timeMenuExpanded, { timeMenuExpanded = false }) {
                        listOf(
                            TimeFilter.All, TimeFilter.Last5h, TimeFilter.Last24h,
                            TimeFilter.Today, TimeFilter.Yesterday,
                            TimeFilter.Last7Days, TimeFilter.Last30Days, TimeFilter.ThisMonth
                        ).forEach { f ->
                            DropdownMenuItem(text = { Text(f.label) }, onClick = {
                                timeMenuExpanded = false; viewModel.setTimeFilter(f)
                            })
                        }
                        DropdownMenuItem(text = { Text("自定义天") }, onClick = {
                            timeMenuExpanded = false; showCustomDayPicker = true
                        })
                        DropdownMenuItem(text = { Text("自定义月") }, onClick = {
                            timeMenuExpanded = false; showCustomMonthPicker = true
                        })
                        DropdownMenuItem(text = { Text("自定义时间") }, onClick = {
                            timeMenuExpanded = false; viewModel.setTimeFilter(TimeFilter.Custom(0L, 0L))
                        })
                    }
                }
                Text("UTC+0", style = MaterialTheme.typography.bodySmall, color = inkMuted())
                Spacer(Modifier.weight(1f))
                // 模型筛选
                Box {
                    val label = if (state.selectedModels.isEmpty()) "全部模型" else "${state.selectedModels.size} 个"
                    TextButton(onClick = { modelMenuExpanded = true }) {
                        Text(label, color = StrawberryPink, fontSize = 13.sp)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                    }
                    DropdownMenu(modelMenuExpanded, { modelMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("全部模型", fontWeight = FontWeight.Bold) },
                            onClick = { modelMenuExpanded = false; viewModel.selectAllModels() })
                        state.allModels.forEach { m ->
                            DropdownMenuItem(text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(state.selectedModels.isEmpty() || m in state.selectedModels, { viewModel.toggleModel(m) })
                                    Text(m, modifier = Modifier.padding(start = 4.dp), fontSize = 13.sp)
                                }
                            }, onClick = { viewModel.toggleModel(m) })
                        }
                    }
                }
            }

            // 自定义时间范围
            if (state.timeFilter is TimeFilter.Custom && (state.timeFilter as TimeFilter.Custom).from == 0L) {
                CustomTimeRangeRow(customStartMs, customEndMs,
                    { showStartPicker = true }, { showEndPicker = true },
                    { viewModel.setTimeFilter(TimeFilter.Custom(customStartMs, customEndMs)) })
                Text("所有时间按 UTC+0 计算", style = MaterialTheme.typography.bodySmall, color = inkMuted())
            }

            // ─── 记录数 + 分页 ───
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("${state.totalRecords} 条记录", style = MaterialTheme.typography.bodySmall, color = inkMuted())
                if (state.totalPages > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.prevPage() }, enabled = state.currentPage > 1) {
                            Text("◀", color = StrawberryPink, fontSize = 13.sp) }
                        Text("${state.currentPage}/${state.totalPages}", style = MaterialTheme.typography.bodySmall, color = inkMuted())
                        TextButton(onClick = { viewModel.nextPage() }, enabled = state.currentPage < state.totalPages) {
                            Text("▶", color = StrawberryPink, fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = pageInput,
                            onValueChange = { newVal -> pageInput = newVal.filter { it.isDigit() } },
                            modifier = Modifier.width(52.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StrawberryPink,
                                unfocusedBorderColor = inkMuted().copy(alpha = 0.4f)
                            )
                        )
                        TextButton(onClick = {
                            val p = pageInput.toIntOrNull()
                            if (p != null) { viewModel.goToPage(p); pageInput = "" }
                        }) {
                            Text("跳转", color = StrawberryPink, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ─── 表头 ───
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    HeaderCell("时间", Modifier.weight(1.5f))
                    HeaderCell("模型", Modifier.weight(1.8f))
                    HeaderCell("输入(缓存)", Modifier.weight(1.5f))
                    HeaderCell("输出", Modifier.weight(1.2f))
                    HeaderCell("成本", Modifier.weight(1.0f))
                }
            }

            // ─── 记录列表 ───
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text("加载中…", color = inkMuted())
                }
            } else if (state.records.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text("暂无数据", color = inkMuted())
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    itemsIndexed(state.records, key = { _, r -> r.id }) { idx, record ->
                        DataRecordRow(record, idx, { rawRecord = it })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        // 日期选择器
        if (showCustomDayPicker) DateTimePickerDialog("选择日期", { ms ->
            val utc = ZoneOffset.UTC; val ds = Instant.ofEpochMilli(ms).atOffset(utc).toLocalDate().atStartOfDay(utc).toInstant().toEpochMilli()
            viewModel.setTimeFilter(TimeFilter.Custom(ds, ds + 86400_000L - 1)); showCustomDayPicker = false
        }, { showCustomDayPicker = false })
        if (showCustomMonthPicker) DateTimePickerDialog("选择月份（任意一天）", { ms ->
            val utc = ZoneOffset.UTC; val ld = Instant.ofEpochMilli(ms).atOffset(utc).toLocalDate()
            val msStart = ld.withDayOfMonth(1).atStartOfDay(utc).toInstant().toEpochMilli()
            val msEnd = ld.withDayOfMonth(ld.lengthOfMonth()).plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli() - 1
            viewModel.setTimeFilter(TimeFilter.Custom(msStart, msEnd)); showCustomMonthPicker = false
        }, { showCustomMonthPicker = false })
        if (showStartPicker) DateTimePickerDialog("开始时间", { customStartMs = it; showStartPicker = false }, { showStartPicker = false })
        if (showEndPicker) DateTimePickerDialog("结束时间", { ms ->
            viewModel.setTimeFilter(TimeFilter.Custom(customStartMs, ms)); showEndPicker = false
        }, { showEndPicker = false })

        // 原始数据弹窗
        rawRecord?.let { record ->
            AlertDialog(
                onDismissRequest = { rawRecord = null },
                title = { Text("原始数据", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        RawField("ID", record.id)
                        RawField("时间", formatUtcTime(record.timeCreated))
                        RawField("模型", record.model)
                        RawField("Provider", record.provider)
                        RawField("输入 Token", "%,d".format(Locale.US, record.inputTokens))
                        RawField("输出 Token", "%,d".format(Locale.US, record.outputTokens))
                        RawField("推理 Token", "%,d".format(Locale.US, record.reasoningTokens))
                        RawField("缓存读取", "%,d".format(Locale.US, record.cacheReadTokens))
                        RawField("缓存写入(5m)", "%,d".format(Locale.US, record.cacheWrite5mTokens))
                        RawField("缓存写入(1h)", "%,d".format(Locale.US, record.cacheWrite1hTokens))
                        RawField("成本(USD)", "$${String.format(Locale.US, "%.6f", record.costUsd)}")
                        RawField("成本(Raw)", "${record.cost}")
                        RawField("KeyId", record.keyId)
                        RawField("SessionId", record.sessionId)
                    }
                },
                confirmButton = { TextButton(onClick = { rawRecord = null }) { Text("关闭") } }
            )
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = inkMuted(), textAlign = TextAlign.Center)
}

@Composable
private fun DataRecordRow(record: UsageRecord, idx: Int, onClick: (UsageRecord) -> Unit) {
    val bg = if (idx % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
    Card(Modifier.fillMaxWidth().clickable { onClick(record) },
        RoundedCornerShape(0.dp), CardDefaults.cardColors(containerColor = bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DataCell(formatUtcTimeShort(record.timeCreated), Modifier.weight(1.5f))
            DataCell(record.model, Modifier.weight(1.8f))
            DataCell(formatInputWithCache(record), Modifier.weight(1.5f))
            DataCell("%,d".format(Locale.US, record.outputTokens), Modifier.weight(1.2f))
            DataCell("$${String.format(Locale.US, "%.4f", record.costUsd)}", Modifier.weight(1.0f))
        }
    }
}

@Composable
private fun DataCell(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center, maxLines = 1)
}

@Composable
private fun RawField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$label: ", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = inkMuted())
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatInputWithCache(record: UsageRecord): String {
    val input = record.inputTokens + record.cacheReadTokens
    val cache = record.cacheReadTokens
    return if (cache > 0) "%,d(%,d)".format(Locale.US, input, cache)
    else "%,d".format(Locale.US, input)
}

private fun formatUtcTimeShort(ts: Long): String {
    val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    return fmt.format(Date(ts))
}

private fun formatUtcTime(ts: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    return fmt.format(Date(ts))
}