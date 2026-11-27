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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
        if (autoLoad) viewModel.observeGoUsageScope()
    }

    var timeMenuExpanded by remember(state.scopeGeneration) { mutableStateOf(false) }
    var modelMenuExpanded by remember(state.scopeGeneration) { mutableStateOf(false) }
    var showRangePicker by remember(state.scopeGeneration) { mutableStateOf(false) }
    var showCustomDayPicker by remember(state.scopeGeneration) { mutableStateOf(false) }
    var showCustomMonthPicker by remember(state.scopeGeneration) { mutableStateOf(false) }
    var rawRecordId by remember(state.scopeGeneration, state.currentPage, state.timeFilter, state.selectedModels) { mutableStateOf<String?>(null) }
    var pageInput by remember(state.scopeGeneration) { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (autoLoad) R.string.go_usage_rows_title else R.string.title_raw_data)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp)) {
            if (autoLoad) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.go_usage_retention), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = inkMuted())
                    Box {
                        var zoneMenuExpanded by remember(state.scopeGeneration) { mutableStateOf(false) }
                        TextButton(onClick = { zoneMenuExpanded = true }) {
                            Text(state.zoneLabel, color = StrawberryPink)
                            Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink, modifier = Modifier.width(16.dp))
                        }
                        DropdownMenu(zoneMenuExpanded, { zoneMenuExpanded = false }) {
                            listOf(false, true).forEach { utc8 ->
                                DropdownMenuItem(
                                    text = { Text(if (utc8) "UTC+8" else "UTC+0") },
                                    onClick = {
                                        zoneMenuExpanded = false
                                        viewModel.setTimeZone(utc8)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            // ─── 筛选栏 ───
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                // 时间筛选
                Box {
                    TextButton(onClick = { timeMenuExpanded = true }) {
                        Text(stringResource(state.timeFilter.labelRes), color = StrawberryPink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                    }
                    DropdownMenu(timeMenuExpanded, { timeMenuExpanded = false }) {
                        usageTimeFilters().forEach { f ->
                            DropdownMenuItem(text = { Text(stringResource(f.labelRes)) }, onClick = {
                                timeMenuExpanded = false; viewModel.setTimeFilter(f)
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.time_custom_day)) }, onClick = {
                            timeMenuExpanded = false; showCustomDayPicker = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.time_custom_month)) }, onClick = {
                            timeMenuExpanded = false; showCustomMonthPicker = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.time_custom_range)) }, onClick = {
                            timeMenuExpanded = false; showRangePicker = true
                        })
                    }
                }
                if (!autoLoad) Text("UTC+0", style = MaterialTheme.typography.bodySmall, color = inkMuted())
                Spacer(Modifier.weight(1f))
                // 模型筛选
                Box {
                    val label = if (state.selectedModels.isEmpty()) stringResource(R.string.common_all_models) else stringResource(R.string.model_count_short, state.selectedModels.size)
                    TextButton(onClick = { modelMenuExpanded = true }) {
                        Text(label, color = StrawberryPink, fontSize = 13.sp)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = StrawberryPink)
                    }
                    DropdownMenu(modelMenuExpanded, { modelMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_all_models), fontWeight = FontWeight.Bold) },
                            onClick = { modelMenuExpanded = false; viewModel.selectAllModels() })
                        state.allModels.forEach { m ->
                            DropdownMenuItem(text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox((!autoLoad && state.selectedModels.isEmpty()) || m in state.selectedModels, null)
                                    Text(m, modifier = Modifier.padding(start = 4.dp), fontSize = 13.sp)
                                }
                            }, onClick = { viewModel.toggleModel(m) })
                        }
                    }
                }
            }

            // 自定义时间范围：确认选择后显示 UTC 说明
            if (!autoLoad && state.timeFilter is TimeFilter.Custom) {
                Text(stringResource(R.string.usage_utc0_note), style = MaterialTheme.typography.bodySmall, color = inkMuted())
            }

            // ─── 记录数 + 分页 ───
            if (autoLoad) Text(stringResource(R.string.go_usage_row_count, state.totalRecords), style = MaterialTheme.typography.bodySmall, color = inkMuted())
            if (!autoLoad || state.totalPages > 1) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), if (autoLoad) Arrangement.End else Arrangement.SpaceBetween, Alignment.CenterVertically) {
                if (!autoLoad) Text(stringResource(R.string.usage_record_count, state.totalRecords), style = MaterialTheme.typography.bodySmall, color = inkMuted())
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
                            Text(stringResource(R.string.action_jump), color = StrawberryPink, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ─── 表头 ───
            if (!autoLoad) Card(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    HeaderCell(stringResource(R.string.header_time), Modifier.weight(1.5f), false)
                    HeaderCell(stringResource(R.string.header_model), Modifier.weight(1.8f), false)
                    HeaderCell(stringResource(R.string.header_input_cache), Modifier.weight(1.5f), false)
                    HeaderCell(stringResource(R.string.header_output), Modifier.weight(1.2f), false)
                    HeaderCell(stringResource(R.string.header_cost), Modifier.weight(1.0f), false)
                }
            }

            // ─── 记录列表 ───
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.common_loading), color = inkMuted())
                }
            } else if (state.records.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    val message = if (autoLoad && !state.hasGoApiKey) R.string.go_usage_configure_key else R.string.common_no_data
                    Text(stringResource(message), color = inkMuted(), textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    itemsIndexed(state.records, key = { _, r -> r.id }) { idx, record ->
                        if (autoLoad) GoRequestRow(record, state.zoneOffset) { rawRecordId = record.id }
                        else DataRecordRow(record, idx, { rawRecordId = it.id }, false)
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        // 日期选择器
        if (showCustomDayPicker) DateOnlyPickerDialog(
            title = stringResource(R.string.date_select_day),
            initialDate = (state.timeFilter as? TimeFilter.Custom)?.from?.takeIf { it > 0 }?.toLocalDate(state.zoneOffset),
            onConfirm = { date ->
                val ds = date.startOfDayMillis(state.zoneOffset)
                viewModel.setTimeFilter(TimeFilter.Custom(ds, ds + 86400_000L - 1))
                showCustomDayPicker = false
            },
            onDismiss = { showCustomDayPicker = false }
        )
        if (showCustomMonthPicker) DateOnlyPickerDialog(
            title = stringResource(R.string.date_select_month),
            initialDate = (state.timeFilter as? TimeFilter.Custom)?.from?.takeIf { it > 0 }?.toLocalDate(state.zoneOffset),
            onConfirm = { date ->
                val zone = state.zoneOffset
                val msStart = date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val msEnd = date.withDayOfMonth(date.lengthOfMonth()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                viewModel.setTimeFilter(TimeFilter.Custom(msStart, msEnd))
                showCustomMonthPicker = false
            },
            onDismiss = { showCustomMonthPicker = false }
        )
        if (showRangePicker) {
            val current = state.timeFilter as? TimeFilter.Custom
            DateRangePickerDialog(
                title = stringResource(R.string.date_pick_range),
                initialStart = current?.from?.takeIf { it > 0 }?.toLocalDate(state.zoneOffset),
                initialEnd = current?.to?.takeIf { it > 0 }?.toLocalDate(state.zoneOffset),
                onConfirm = { from, to ->
                    val fromMs = from.startOfDayMillis(state.zoneOffset)
                    val toMs = to.startOfDayMillis(state.zoneOffset)
                    viewModel.setTimeFilter(TimeFilter.Custom(fromMs, toMs + 86400_000L - 1))
                    showRangePicker = false
                },
                onDismiss = { showRangePicker = false }
            )
        }

        state.records.firstOrNull { it.id == rawRecordId }?.let { record ->
            if (autoLoad) GoRequestDetail(record, state.zoneOffset, state.zoneLabel) { rawRecordId = null }
            else LegacyRecordDetail(record) { rawRecordId = null }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, expanded: Boolean = false) {
    Text(text, modifier = modifier,
        fontSize = if (expanded) 14.sp else 12.sp,
        fontWeight = FontWeight.Bold,
        color = inkMuted(), textAlign = TextAlign.Center)
}

@Composable
private fun DataRecordRow(record: UsageRecord, idx: Int, onClick: (UsageRecord) -> Unit, expanded: Boolean = false) {
    val bg = if (idx % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
    val rowPad = if (expanded) 12.dp else 8.dp
    Card(Modifier.fillMaxWidth().clickable { onClick(record) },
        RoundedCornerShape(0.dp), CardDefaults.cardColors(containerColor = bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = rowPad),
            verticalAlignment = Alignment.CenterVertically) {
            DataCell(formatRecordTime(record.timeCreated, ZoneOffset.UTC, short = true), Modifier.weight(1.5f), expanded)
            DataCell(record.model, Modifier.weight(1.8f), expanded)
            DataCell(formatInputWithCache(record), Modifier.weight(1.5f), expanded)
            DataCell("%,d".format(Locale.US, record.outputTokens), Modifier.weight(1.2f), expanded)
            DataCell(record.cost?.let(::formatUsageUsd) ?: stringResource(R.string.common_unknown), Modifier.weight(1.0f), expanded)
        }
    }
}

@Composable
private fun DataCell(text: String, modifier: Modifier, expanded: Boolean = false) {
    Text(text, modifier = modifier,
        fontSize = if (expanded) 13.sp else 11.sp,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center, maxLines = 1)
}

@Composable
private fun RawField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$label: ", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = inkMuted())
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Long.toLocalDate(zone: ZoneOffset): LocalDate = Instant.ofEpochMilli(this).atOffset(zone).toLocalDate()

private fun LocalDate.startOfDayMillis(zone: ZoneOffset): Long = atStartOfDay(zone).toInstant().toEpochMilli()

private fun formatInputWithCache(record: UsageRecord): String {
    val input = record.inputTokens + record.cacheReadTokens
    val cache = record.cacheReadTokens
    return if (cache > 0) "%,d(%,d)".format(Locale.US, input, cache)
    else "%,d".format(Locale.US, input)
}

private val utcShortTime = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.US)
private val fullRequestTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

private fun formatRecordTime(ts: Long, zone: ZoneOffset, short: Boolean = false): String =
    (if (short) utcShortTime else fullRequestTime).format(Instant.ofEpochMilli(ts).atOffset(zone))

@Composable
private fun recordOutcome(outcome: String?): String = when (outcome?.lowercase(Locale.ROOT)) {
    "succeeded", "success" -> stringResource(R.string.go_usage_success)
    "rejected" -> stringResource(R.string.go_usage_rejected)
    "cancelled", "canceled" -> stringResource(R.string.go_usage_cancelled)
    else -> outcome?.takeIf { it.isNotBlank() } ?: stringResource(R.string.common_unknown)
}

@Composable
private fun requestDuration(ms: Long?): String = if (ms == null) stringResource(R.string.common_unknown)
else stringResource(R.string.go_usage_milliseconds, ms)

@Composable
private fun GoRequestRow(record: UsageRecord, zone: ZoneOffset, onClick: () -> Unit) {
    val unknown = stringResource(R.string.common_unknown)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatRecordTime(record.timeCreated, zone), style = MaterialTheme.typography.bodySmall, color = inkMuted())
            Text(record.model.ifBlank { unknown }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            record.requestedModel?.takeIf { it.isNotBlank() && it != record.model }?.let {
                Text(stringResource(R.string.go_usage_requested_summary, it), style = MaterialTheme.typography.bodySmall, color = inkMuted())
            }
            Text("${recordOutcome(record.outcome)} · ${requestDuration(record.durationMs)}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RequestMetric(stringResource(R.string.go_usage_input), formatCount(record.inputTokens), Modifier.weight(1f))
                RequestMetric(stringResource(R.string.header_output), formatCount(record.outputTokens), Modifier.weight(1f))
                RequestMetric(stringResource(R.string.header_cost), record.cost?.let(::formatUsageUsd) ?: unknown, Modifier.weight(1.25f))
            }
        }
    }
}

@Composable
private fun RequestMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = inkMuted())
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GoRequestDetail(record: UsageRecord, zone: ZoneOffset, zoneLabel: String, onDismiss: () -> Unit) {
    val unknown = stringResource(R.string.common_unknown)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_usage_row_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                RequestField(stringResource(R.string.go_usage_started), "${formatRecordTime(record.timeCreated, zone)} $zoneLabel")
                if (record.timeUpdated > 0L) RequestField(stringResource(R.string.go_usage_finished), "${formatRecordTime(record.timeUpdated, zone)} $zoneLabel")
                RequestField(stringResource(R.string.header_model), record.model.ifBlank { unknown })
                record.requestedModel?.takeIf { it.isNotBlank() }?.let {
                    RequestField(stringResource(R.string.go_usage_requested_model), it)
                }
                if (record.provider.isNotBlank()) RequestField(stringResource(R.string.go_usage_provider), record.provider)
                RequestField(stringResource(R.string.go_usage_outcome), recordOutcome(record.outcome))
                RequestField(stringResource(R.string.go_usage_duration), requestDuration(record.durationMs))
                record.timeToFirstTokenMs?.let { RequestField(stringResource(R.string.go_usage_first_token), requestDuration(it)) }
                record.statusCode?.let { RequestField(stringResource(R.string.go_usage_http_status), it.toString()) }
                record.errorCode?.takeIf { it.isNotBlank() }?.let { RequestField(stringResource(R.string.go_usage_error_code), it) }
                RequestField(stringResource(R.string.usage_input_tokens), formatCount(record.inputTokens))
                RequestField(stringResource(R.string.usage_output_tokens), formatCount(record.outputTokens))
                RequestField(stringResource(R.string.usage_reasoning_tokens), formatCount(record.reasoningTokens))
                RequestField(stringResource(R.string.stat_cache_read), formatCount(record.cacheReadTokens))
                if (record.cacheWrite5mTokens != 0L) RequestField(stringResource(R.string.stat_cache_write_5m), formatCount(record.cacheWrite5mTokens))
                if (record.cacheWrite1hTokens != 0L) RequestField(stringResource(R.string.stat_cache_write_1h), formatCount(record.cacheWrite1hTokens))
                record.cacheWriteTokensUnspecified?.let { RequestField(stringResource(R.string.go_usage_cache_write_unspecified), formatCount(it)) }
                RequestField(stringResource(R.string.raw_cost_usd), record.cost?.let(::formatUsageUsd) ?: unknown)
                record.reasoningEffort?.takeIf { it.isNotBlank() }?.let { RequestField(stringResource(R.string.go_usage_reasoning_effort), it) }
                val region = listOfNotNull(record.city, record.country).filter(String::isNotBlank).joinToString(", ")
                if (region.isNotEmpty()) RequestField(stringResource(R.string.go_usage_region), region)
                if (record.sessionId.isNotBlank()) RequestField(stringResource(R.string.go_usage_session), record.sessionId)
                RequestField(stringResource(R.string.go_usage_request_id), record.id)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun RequestField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = inkMuted())
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LegacyRecordDetail(record: UsageRecord, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.raw_record_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                RawField("ID", record.id)
                RawField(stringResource(R.string.header_time), formatRecordTime(record.timeCreated, ZoneOffset.UTC))
                RawField(stringResource(R.string.header_model), record.model)
                RawField("Provider", record.provider)
                RawField(stringResource(R.string.usage_input_tokens), formatCount(record.inputTokens))
                RawField(stringResource(R.string.usage_output_tokens), formatCount(record.outputTokens))
                RawField(stringResource(R.string.usage_reasoning_tokens), formatCount(record.reasoningTokens))
                RawField(stringResource(R.string.stat_cache_read), formatCount(record.cacheReadTokens))
                RawField(stringResource(R.string.stat_cache_write_5m), formatCount(record.cacheWrite5mTokens))
                RawField(stringResource(R.string.stat_cache_write_1h), formatCount(record.cacheWrite1hTokens))
                RawField(stringResource(R.string.raw_cost_usd), record.cost?.let(::formatUsageUsd) ?: stringResource(R.string.common_unknown))
                RawField("KeyId", record.keyId)
                RawField("SessionId", record.sessionId)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

private fun formatCount(count: Long): String = "%,d".format(Locale.US, count)
