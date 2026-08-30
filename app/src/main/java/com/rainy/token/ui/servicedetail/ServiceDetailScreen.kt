package com.rainy.token.ui.servicedetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.data.repository.ModelUsageRow
import com.rainy.token.data.repository.OpenCodeGoRepository
import com.rainy.token.data.repository.WindowModelUsage
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.model.TriggerSummary
import com.rainy.token.domain.service.FetchMethod
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.ServiceIcon
import com.rainy.token.ui.components.StatusChip
import com.rainy.token.ui.components.StatusLevel
import com.rainy.token.ui.components.StatusStyle
import com.rainy.token.ui.components.asString
import com.rainy.token.ui.components.DurationText
import com.rainy.token.ui.components.formatAmount
import com.rainy.token.ui.components.formatResetInSec
import com.rainy.token.ui.components.isFiveHourLabel
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 服务详情页（雨晴风格重做版）。
 *
 * 视觉层级：
 *  - TopAppBar 透明 + 服务名
 *  - 主余额卡（大数字 + 副信息）
 *  - 服务特定详情：
 *      - DeepSeek：赠送/自费拆分
 *      - OpenCode Go：3 个窗口（rolling 5h / weekly / monthly）配额
 *  - 错误信息
 *  - 底部按钮区：刷新 / 重新登录 / 配置凭据
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceDetailScreen(
    service: ServiceType,
    onBack: () -> Unit,
    onConfigureCredential: (ServiceType) -> Unit,
    onStartWebViewLogin: (ServiceType) -> Unit,
    viewModel: ServiceDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(service) { viewModel.bind(service) }
    // 从凭据编辑页返回时重新读取凭据状态 + 缓存
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.reloadCredentialState()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val triggerState by viewModel.triggerState.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val modelsLoading by viewModel.modelsLoading.collectAsStateWithLifecycle()
    val config = ServiceConfigProvider.get(service)
    val isManualMode = config.method == FetchMethod.MANUAL

    // Codex / OCGO / Ollama 服务且有凭据时自动加载模型列表
    val supportsTrigger = service == ServiceType.CODEX || service == ServiceType.OPENCODE_GO || service == ServiceType.OLLAMA
    LaunchedEffect(service, uiState.hasCredential) {
        if (supportsTrigger && uiState.hasCredential) {
            viewModel.loadModels()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ServiceIcon(service = service, size = 32)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = service.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = StrawberryPink
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 主余额卡
            item { MainBalanceCard(state = uiState.state, service = service) }

            // 服务特定详情
            when (service) {
                ServiceType.DEEPSEEK -> {
                    item { DeepSeekBreakdownCard(uiState.state) }
                }
                ServiceType.OPENCODE_GO -> {
                    item { OpenCodeGoWindowsCard(uiState.state) }
                }
                ServiceType.COMMANDCODE_GO -> {
                    item { CommandCodeGoUsageCard(uiState.state) }
                }
                ServiceType.CODEX -> {
                item { CodexUsageCard(uiState.state) }
            }
                ServiceType.OLLAMA -> {
                    item { OllamaUsageCard(uiState.state) }
                }
            }

            // 错误信息（如有）
            (uiState.state as? State.Error)?.let { err ->
                item { ErrorCard(message = err.message.asString()) }
            }

            // 凭据状态条
            item {
                CredentialStatusRow(
                    hasCredential = uiState.hasCredential,
                    isManualMode = isManualMode
                )
            }

            // 操作按钮
            item {
                ActionButtons(
                    state = uiState.state,
                    hasCredential = uiState.hasCredential,
                    isManualMode = isManualMode,
                    service = service,
                    triggerState = triggerState,
                    models = models,
                    selectedModel = selectedModel,
                    modelsLoading = modelsLoading,
                    onRefresh = { viewModel.refresh() },
                    onConfigureCredential = { onConfigureCredential(service) },
                    onStartWebViewLogin = { onStartWebViewLogin(service) },
                    onTriggerUsage = { viewModel.triggerUsage() },
                    onSelectModel = { viewModel.selectModel(it) },
                    onRefreshModels = { viewModel.loadModels(force = true) }
                )
            }
        }
    }

    // 响应弹窗（成功和失败都弹）
    when (triggerState) {
        is TriggerState.Success -> ResponseDialog(
            title = stringResource(R.string.trigger_success_title),
            responseBody = buildTriggerSummaryText((triggerState as TriggerState.Success).summary),
            isError = false,
            onDismiss = { viewModel.dismissTrigger() }
        )
        is TriggerState.Error -> ResponseDialog(
            title = stringResource(R.string.trigger_result_title),
            responseBody = (triggerState as TriggerState.Error).let {
                stringResource(R.string.trigger_error_prefix, it.message.asString()) +
                    (it.responseBody?.let { body ->
                        "\n\n" + stringResource(R.string.trigger_response_body, body)
                    } ?: "")
            },
            isError = true,
            onDismiss = { viewModel.dismissTrigger() }
        )
        else -> {}
    }
}

/**
 * CommandCode Go 专属：月度用量卡 + 窗口进度条。
 *
 * 数据从 balance.extras 中的 monthlyRemaining / monthlyTotal / fiveHour / weekly 取，
 * 展示月度已用/总量 + 5h + 每周窗口进度条。
 * 窗口顺序：5小时 → 本周 → 本月（最底部为月度总览）。
 */
@Composable
private fun CommandCodeGoUsageCard(state: State) {
    val balance = when (state) {
        is State.Fresh -> state.data
        is State.Stale -> state.data
        is State.Error -> state.cached
        else -> null
    }
    val extras = balance?.extras ?: return
    val monthlyRemaining = extras["monthlyRemaining"]?.toDoubleOrNull() ?: return
    val monthlyTotal = extras["monthlyTotal"]?.toDoubleOrNull()
    val purchased = extras["purchasedCredits"]?.toDoubleOrNull() ?: 0.0
    val planName = extras["planName"].orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(
                    R.string.usage_window_title,
                    if (planName.isNotBlank()) " · $planName" else ""
                ),
                style = MaterialTheme.typography.labelLarge,
                color = inkMuted()
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 1. 5h 窗口（最上面）
            val fiveHourUsed = extras["fiveHour.used"]?.toDoubleOrNull()
            val fiveHourCap = extras["fiveHour.cap"]?.toDoubleOrNull()
            if (fiveHourUsed != null && fiveHourCap != null && fiveHourCap > 0) {
                val pct = ((fiveHourUsed / fiveHourCap) * 100).toFloat().coerceIn(0f, 100f)
                UsageWindowRow(
                    label = stringResource(R.string.window_5h_rolling),
                    pct = pct,
                    resetInSec = extras["fiveHour.resetInSec"]?.toLongOrNull()
                )
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 2. 每周窗口
            val weeklyUsed = extras["weekly.used"]?.toDoubleOrNull()
            val weeklyCap = extras["weekly.cap"]?.toDoubleOrNull()
            if (weeklyUsed != null && weeklyCap != null && weeklyCap > 0) {
                val pct = ((weeklyUsed / weeklyCap) * 100).toFloat().coerceIn(0f, 100f)
                UsageWindowRow(
                    label = stringResource(R.string.window_weekly),
                    pct = pct,
                    resetInSec = extras["weekly.resetInSec"]?.toLongOrNull()
                )
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 3. 本月（最下面）
            if (monthlyTotal != null && monthlyTotal > 0) {
                val used = monthlyTotal - monthlyRemaining
                val pct = ((used / monthlyTotal) * 100).toFloat().coerceIn(0f, 100f)
                UsageWindowRow(
                    label = stringResource(R.string.window_monthly),
                    pct = pct,
                    // 仓库已把账单周期结束转成剩余秒写入 monthly.resetInSec（billingPeriodEnd 是 epoch 毫秒，不能按 ISO 解析）
                    resetInSec = extras["monthly.resetInSec"]?.toLongOrNull()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.usage_spent_total,
                        formatAmount(used),
                        formatAmount(monthlyTotal)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted()
                )
            } else {
                Text(
                    text = stringResource(R.string.usage_remaining, formatAmount(monthlyRemaining)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // 额外充值
            if (purchased > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                BreakdownRow(stringResource(R.string.usage_extra_credits), purchased, "$")
            }
        }
    }
}

/**
 * 尝试从 ISO8601 时间戳计算剩余秒数。
 */
private fun parseIsoDuration(isoStr: String): Long? {
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val end = sdf.parse(isoStr.take(19))?.time ?: return null
        maxOf(0L, (end - System.currentTimeMillis()) / 1000)
    } catch (_: Exception) { null }
}

/**
 * OpenCode Go 专属：3 个窗口（rolling 5h / weekly / monthly）独立用量卡。
 *
 * 数据从 balance.extras["rolling.pct"] / ["weekly.pct"] / ["monthly.pct"] 取，
 * 进度条颜色按 % 自动切换：< 50% 草莓粉，50-80% 暖橙，> 80% 玫红。
 * 每个窗口下方展示模型级用量（_server 接口，像 Ollama 一样按模型列出）。
 */
@Composable
private fun OpenCodeGoWindowsCard(state: State) {
    val balance = when (state) {
        is State.Fresh -> state.data
        is State.Stale -> state.data
        is State.Error -> state.cached
        else -> null
    }
    val extras = balance?.extras ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.usage_window_title_plain),
                style = MaterialTheme.typography.labelLarge,
                color = inkMuted()
            )
            Spacer(modifier = Modifier.height(12.dp))
            UsageWindowRow(
                label = stringResource(R.string.window_5h_rolling),
                pct = extras["rolling.pct"]?.toFloatOrNull(),
                resetInSec = extras["rolling.resetInSec"]?.toLongOrNull()
            )
            val rollingWin = parseModelUsage(extras["rolling.models"])
            if (!rollingWin.rows.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                ModelUsageList(rows = rollingWin.rows.orEmpty(), windowLimit = rollingWin.limit)
            }
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            UsageWindowRow(
                label = stringResource(R.string.window_weekly),
                pct = extras["weekly.pct"]?.toFloatOrNull(),
                resetInSec = extras["weekly.resetInSec"]?.toLongOrNull()
            )
            val weeklyWin = parseModelUsage(extras["weekly.models"])
            if (!weeklyWin.rows.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                ModelUsageList(rows = weeklyWin.rows.orEmpty(), windowLimit = weeklyWin.limit)
            }
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            UsageWindowRow(
                label = stringResource(R.string.window_monthly),
                pct = extras["monthly.pct"]?.toFloatOrNull(),
                resetInSec = extras["monthly.resetInSec"]?.toLongOrNull()
            )
            val monthlyWin = parseModelUsage(extras["monthly.models"])
            if (!monthlyWin.rows.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                ModelUsageList(rows = monthlyWin.rows.orEmpty(), windowLimit = monthlyWin.limit)
            }
        }
    }
}

/**
 * 解析 extras 中模型级用量 JSON（[OpenCodeGoRepository] 序列化的 WindowModelUsage）。
 * 返回整个窗口对象（含 limit，供配额计算）；解析失败返回空对象（UI 隐藏列表）。
 */
private fun parseModelUsage(raw: String?): WindowModelUsage {
    if (raw.isNullOrBlank()) return WindowModelUsage()
    return try {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<WindowModelUsage>(raw)
    } catch (_: Exception) {
        WindowModelUsage()
    }
}

/**
 * 模型级用量列表（Ollama 风格）：模型名 + 用量金额 + 占比 + 配额。
 * 每行显示模型显示名、已用金额（$XX.XX）、占窗口百分比与窗口配额（limit ÷ multiplier）。
 * estimated 行（用量为估算值）在模型名后加 "~" 标识。
 * windowLimit 缺失时隐藏配额列（用量与占比照常显示）。
 */
@Composable
private fun ModelUsageList(rows: List<ModelUsageRow>, windowLimit: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.msg_model_usage),
            style = MaterialTheme.typography.labelSmall,
            color = inkMuted()
        )
        rows.sortedByDescending { it.contributionPercent }.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.name.ifBlank { row.model } + if (row.estimated) " ~" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (windowLimit != null && windowLimit > 0) {
                    Text(
                        text = stringResource(
                            R.string.msg_model_quota,
                            formatUsd(OpenCodeGoRepository.windowQuotaRaw(windowLimit, row.multiplier))
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = inkMuted(),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = "$" + formatUsd(row.cost),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = StrawberryPink,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = String.format(Locale.US, "%.1f%%", row.contributionPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 把 1e-8 美元整数（cost/quotaCost）格式化为美元字符串。
 * 例：52650887 → "0.53"；1002607535 → "10.03"。
 * 小额（<$0.01）自动提升精度避免显示 "$0.00"：11310 → "0.0001"。
 */
private fun formatUsd(rawCost: Long): String {
    val dollars = rawCost / 100_000_000.0
    return if (dollars > 0.0 && dollars < 0.01) {
        String.format(Locale.US, "%.4f", dollars)
    } else {
        String.format(Locale.US, "%.2f", dollars)
    }
}

@Composable
private fun UsageWindowRow(label: String, pct: Float?, resetInSec: Long?, decimals: Int = 0) {
    val durationText = DurationText(
        day = stringResource(R.string.format_day),
        hour = stringResource(R.string.format_hour),
        minute = stringResource(R.string.format_minute)
    )
    val pctValue = (pct ?: 0f).coerceIn(0f, 100f)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = inkMuted(),
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "%.${decimals}f", pctValue),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = inkMuted(),
                    modifier = Modifier.padding(bottom = 2.dp, start = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { pctValue / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = when {
                pctValue >= 80f -> MaterialTheme.colorScheme.error
                pctValue >= 50f -> com.rainy.token.ui.theme.StatusOrange
                else -> StrawberryPink
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        if (resetInSec != null && resetInSec > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.service_reset_in, formatResetInSec(resetInSec, durationText)),
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
        }
    }
}

/**
 * Codex 专属：用量窗口进度卡。
 *
 * 数据从 balance.extras 中的 window_*.label / remainingPct / resetAt 解析，
 * 展示每个窗口的已用百分比与重置倒计时。
 */
@Composable
private fun CodexUsageCard(state: State) {
    val balance = when (state) {
        is State.Fresh -> state.data
        is State.Stale -> state.data
        is State.Error -> state.cached
        else -> null
    }
    val extras = balance?.extras ?: return
    val plan = extras["plan"].orEmpty()
    val allWindows = remember(extras) { extractCodexWindows(extras) }
    if (allWindows.isEmpty()) return
    // 主模型窗口占据主视觉；Spark 独立限额收进底部次要小节
    val windows = allWindows.filter { it.group == CodexWindowGroup.CODEX }
    val sparkWindows = allWindows.filter { it.group == CodexWindowGroup.SPARK }

    // 判断是否有 5h 窗口（与语言无关：匹配 "5h"/"5H" 或本地化标签；仅看主模型窗口）
    val has5h = windows.any {
        isFiveHourLabel(it.rawLabel, stringResource(R.string.window_5h), stringResource(R.string.window_5h_short))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.usage_window_title_plain),
                    style = MaterialTheme.typography.labelLarge,
                    color = inkMuted()
                )
                Spacer(modifier = Modifier.weight(1f))
                if (plan.isNotBlank()) {
                    Text(
                        text = plan,
                        style = MaterialTheme.typography.labelLarge,
                        color = StrawberryPink,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 没有 5h 窗口时，在顶部插入空槽位
            if (!has5h) {
                UsageWindowRow(
                    label = stringResource(R.string.window_5h),
                    pct = null,
                    resetInSec = null,
                    decimals = 2
                )
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(14.dp))
            }

            windows.forEachIndexed { index, window ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(14.dp))
                }
                val resetInSec = window.resetAt?.let {
                    (it - System.currentTimeMillis()) / 1000
                }?.takeIf { it > 0 }
                UsageWindowRow(
                    label = codexWindowLabel(window.rawLabel),
                    pct = window.usedPct,
                    resetInSec = resetInSec,
                    decimals = 2
                )
            }

            // Spark 独立限额（次要通道）：分隔线 + 小标题 + 紧凑细条，视觉权重远低于主模型
            if (sparkWindows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.window_spark),
                    style = MaterialTheme.typography.labelMedium,
                    color = inkMuted()
                )
                Spacer(modifier = Modifier.height(8.dp))
                sparkWindows.forEach { window ->
                    val resetInSec = window.resetAt?.let {
                        (it - System.currentTimeMillis()) / 1000
                    }?.takeIf { it > 0 }
                    SparkUsageRow(
                        label = codexWindowLabel(window.rawLabel),
                        pct = window.usedPct,
                        resetInSec = resetInSec
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * Spark 次要限额的紧凑行：小字号 + 细进度条，视觉权重明显低于主模型的 [UsageWindowRow]。
 */
@Composable
private fun SparkUsageRow(label: String, pct: Float, resetInSec: Long?) {
    val durationText = DurationText(
        day = stringResource(R.string.format_day),
        hour = stringResource(R.string.format_hour),
        minute = stringResource(R.string.format_minute)
    )
    val pctValue = pct.coerceIn(0f, 100f)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = String.format(Locale.US, "%.1f", pctValue),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "%",
                style = MaterialTheme.typography.labelSmall,
                color = inkMuted(),
                modifier = Modifier.padding(start = 1.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { pctValue / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(1.25.dp)),
            color = when {
                pctValue >= 80f -> MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                else -> StrawberryPink.copy(alpha = 0.55f)
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        if (resetInSec != null && resetInSec > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.service_reset_in, formatResetInSec(resetInSec, durationText)),
                style = MaterialTheme.typography.labelSmall,
                color = inkMuted()
            )
        }
    }
}

private enum class CodexWindowGroup { CODEX, SPARK }

private data class CodexWindow(
    val rawLabel: String,
    val usedPct: Float,
    val resetAt: Long?,
    val group: CodexWindowGroup = CodexWindowGroup.CODEX
)

private fun extractCodexWindows(extras: Map<String, String>): List<CodexWindow> {
    val result = mutableListOf<CodexWindow>()
    var i = 0
    while (true) {
        val rawLabel = extras["window_${i}.label"] ?: break
        val remaining = extras["window_${i}.remainingPct"]?.toFloatOrNull() ?: 0f
        val resetAt = extras["window_${i}.resetAt"]?.toLongOrNull()?.takeIf { it > 0 }
        val usedPct = (100f - remaining).coerceIn(0f, 100f)
        val group = if (extras["window_${i}.group"] == "SPARK") CodexWindowGroup.SPARK else CodexWindowGroup.CODEX
        result.add(CodexWindow(rawLabel, usedPct, resetAt, group))
        i++
    }
    return result
}

@Composable
private fun codexWindowLabel(raw: String): String = when (raw.lowercase()) {
    "5h" -> stringResource(R.string.window_5h)
    "7d", "weekly", "每周", "每週" -> stringResource(R.string.window_weekly)
    "30d", "monthly", "每月" -> stringResource(R.string.window_monthly)
    "usage" -> stringResource(R.string.window_usage)
    else -> raw
}

/**
 * Ollama 专属：5h + 每周用量窗口卡 + 模型级调用次数。
 */
@Composable
private fun OllamaUsageCard(state: State) {
    val balance = when (state) {
        is State.Fresh -> state.data
        is State.Stale -> state.data
        is State.Error -> state.cached
        else -> null
    }
    val extras = balance?.extras ?: return
    val plan = extras["plan"]?.takeIf { it.isNotBlank() } ?: "—"

    val sessionModels = parseModelRequests(extras["session.models"])
    val weeklyModels = parseModelRequests(extras["weekly.models"])

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.usage_window_title_plain),
                    style = MaterialTheme.typography.labelLarge,
                    color = inkMuted()
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = plan,
                    style = MaterialTheme.typography.labelLarge,
                    color = StrawberryPink,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            UsageWindowRow(
                label = stringResource(R.string.window_5h),
                pct = extras["session.pct"]?.toFloatOrNull(),
                resetInSec = extras["session.resetAt"]?.toLongOrNull()?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 },
                decimals = 2
            )
            if (sessionModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                ModelRequestList(models = sessionModels)
            }
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            UsageWindowRow(
                label = stringResource(R.string.window_every_week),
                pct = extras["weekly.pct"]?.toFloatOrNull(),
                resetInSec = extras["weekly.resetAt"]?.toLongOrNull()?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 },
                decimals = 2
            )
            if (weeklyModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                ModelRequestList(models = weeklyModels)
            }
        }
    }
}

/**
 * 解析 extras 中的模型调用次数字符串。
 * 格式: "model1,count1;model2,count2"
 */
private fun parseModelRequests(raw: String?): List<Pair<String, Int>> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(";").mapNotNull { entry ->
        val parts = entry.split(",")
        if (parts.size == 2) {
            val model = parts[0].trim()
            val count = parts[1].trim().toIntOrNull()
            if (model.isNotBlank() && count != null) model to count else null
        } else null
    }
}

/**
 * 模型级调用次数列表。
 * 每行显示模型名 + 调用次数，按次数降序排列。
 */
@Composable
private fun ModelRequestList(models: List<Pair<String, Int>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.msg_model_call_count),
            style = MaterialTheme.typography.labelSmall,
            color = inkMuted()
        )
        models.sortedByDescending { it.second }.forEach { (model, count) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = StrawberryPink
                )
            }
        }
    }
}

@Composable
private fun MainBalanceCard(state: State, service: ServiceType) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mainCardLabel(service),
                    style = MaterialTheme.typography.labelLarge,
                    color = inkMuted()
                )
                Spacer(modifier = Modifier.weight(1f))
                StatusChip(style = stateToChip(state))
            }
            Spacer(modifier = Modifier.height(12.dp))
            when (state) {
                is State.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = StrawberryPink) }
                }
                is State.Fresh -> BalanceBigNumber(state.data)
                is State.Stale -> {
                    BalanceBigNumber(state.data)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.msg_stale_cache, formatTime(state.lastFetchedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is State.Error -> {
                    BalanceBigNumber(state.cached)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.msg_showing_last_balance),
                        style = MaterialTheme.typography.bodySmall,
                        color = inkMuted()
                    )
                }
                is State.ManualModeHint -> {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.displayMedium,
                        color = inkMuted()
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceBigNumber(balance: ServiceBalance?) {
    if (balance == null) {
        Text(
            text = "—",
            style = MaterialTheme.typography.displayLarge,
            color = inkMuted()
        )
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatAmount(balance.amount),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = balance.unit,
            style = MaterialTheme.typography.titleLarge,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
    if (!balance.isAvailable) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.service_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    balance.monthlySpent?.let { spent ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.msg_monthly_spent, formatAmount(spent), balance.unit),
            style = MaterialTheme.typography.bodyMedium,
            color = inkMuted()
        )
    }
}

/**
 * DeepSeek 专属：赠送 / 自费拆分卡。
 */
@Composable
private fun DeepSeekBreakdownCard(state: State) {
    val balance = when (state) {
        is State.Fresh -> state.data
        is State.Stale -> state.data
        is State.Error -> state.cached
        else -> null
    }
    val extras = balance?.extras ?: return
    val granted = extras["grantedBalance"]?.toDoubleOrNull() ?: 0.0
    val toppedUp = extras["toppedUpBalance"]?.toDoubleOrNull() ?: 0.0
    if (granted == 0.0 && toppedUp == 0.0) return  // 没有拆分数据就跳过

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.msg_balance_breakdown),
                style = MaterialTheme.typography.labelLarge,
                color = inkMuted()
            )
            Spacer(modifier = Modifier.height(12.dp))
            BreakdownRow(stringResource(R.string.msg_granted_balance), granted, balance!!.unit)
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            BreakdownRow(stringResource(R.string.msg_topped_up_balance), toppedUp, balance.unit)
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: Double, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = inkMuted(),
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatAmount(value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.bodyMedium,
                color = inkMuted(),
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.status_refresh_failed),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CredentialStatusRow(hasCredential: Boolean, isManualMode: Boolean) {
    val style = when {
        isManualMode -> StatusStyle(R.string.status_manual_mode, StatusLevel.INFO)
        hasCredential -> StatusStyle(R.string.status_credential_configured, StatusLevel.OK)
        else -> StatusStyle(R.string.status_not_configured_cred, StatusLevel.ERROR)
    }
    StatusChip(style = style)
}

@Composable
private fun ActionButtons(
    state: State,
    hasCredential: Boolean,
    isManualMode: Boolean,
    service: ServiceType,
    triggerState: TriggerState,
    models: List<String>,
    selectedModel: String?,
    modelsLoading: Boolean,
    onRefresh: () -> Unit,
    onConfigureCredential: () -> Unit,
    onStartWebViewLogin: () -> Unit,
    onTriggerUsage: () -> Unit,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 一键激活用量区域（Codex / OCGO / Ollama）
        val supportsTrigger = service == ServiceType.CODEX || service == ServiceType.OPENCODE_GO || service == ServiceType.OLLAMA
        if (supportsTrigger && hasCredential && !isManualMode) {
            // 模型选择器 + 刷新按钮
            if (modelsLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        color = StrawberryPink,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.msg_loading_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = inkMuted()
                    )
                }
            } else if (models.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.msg_select_model),
                        style = MaterialTheme.typography.labelMedium,
                        color = inkMuted()
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onRefreshModels,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh_models),
                            tint = StrawberryPink,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                ModelDropdown(
                    models = models,
                    selectedModel = selectedModel,
                    onSelect = onSelectModel
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 激活按钮
            when (triggerState) {
                TriggerState.Loading -> {
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StrawberryPink,
                            contentColor = Color.White
                        )
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.msg_triggering))
                    }
                }
                is TriggerState.Error -> {
                    OutlinedButton(
                        onClick = onTriggerUsage,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is State.Loading && selectedModel != null,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.action_retry_trigger))
                    }
                    Text(
                        text = triggerState.message.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                TriggerState.Idle -> {
                    OutlinedButton(
                        onClick = onTriggerUsage,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is State.Loading && selectedModel != null
                    ) {
                        Text(stringResource(R.string.action_trigger_usage))
                    }
                }
                is TriggerState.Success -> {
                    // Success 状态由弹窗展示，按钮不显示
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (hasCredential && !isManualMode) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is State.Loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StrawberryPink,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_refresh_balance))
            }
            OutlinedButton(
                onClick = onConfigureCredential,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_relogin))
            }
        } else if (!hasCredential) {
            Button(
                onClick = onConfigureCredential,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StrawberryPink,
                    contentColor = Color.White
                )
            ) {
                Text(stringResource(R.string.action_configure_credential))
            }
        }
    }
}

@Composable
private fun mainCardLabel(service: ServiceType): String = when (service) {
    ServiceType.DEEPSEEK -> stringResource(R.string.main_card_current_balance)
    ServiceType.OPENCODE_GO -> stringResource(R.string.main_card_5h_usage)
    ServiceType.COMMANDCODE_GO -> stringResource(R.string.main_card_monthly_balance)
    ServiceType.CODEX -> stringResource(R.string.window_usage)
    ServiceType.OLLAMA -> stringResource(R.string.main_card_session_usage)
}

private fun stateToChip(state: State): StatusStyle = when (state) {
    is State.Loading -> StatusStyle(R.string.status_loading, StatusLevel.INFO)
    is State.Fresh -> StatusStyle(R.string.status_fresh, StatusLevel.OK)
    is State.Stale -> StatusStyle(R.string.status_stale, StatusLevel.WARNING)
    is State.Error -> StatusStyle(R.string.status_failed, StatusLevel.ERROR)
    is State.ManualModeHint -> StatusStyle(R.string.status_pending_input, StatusLevel.WARNING)
}

private fun formatTime(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

// ── Codex 一键激活用量：模型选择器 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    models: List<String>,
    selectedModel: String?,
    onSelect: (String) -> Unit
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    val display = selectedModel ?: stringResource(R.string.msg_please_select_model)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ── Codex 一键激活用量：响应弹窗 ──

/** 把结构化的激活摘要组装为本地化文本（Repository 不再拼接展示文案）。 */
@Composable
private fun buildTriggerSummaryText(summary: TriggerSummary): String = buildString {
    appendLine(stringResource(R.string.trigger_success_header))
    appendLine(stringResource(R.string.trigger_success_model, summary.model))
    summary.responseId?.let { appendLine(stringResource(R.string.trigger_success_response_id, it)) }
    appendLine()
    appendLine(stringResource(R.string.trigger_reply_header))
    if (summary.parseFailed) {
        appendLine(stringResource(R.string.trigger_parse_failed))
    } else {
        appendLine(
            summary.reply?.ifEmpty { stringResource(R.string.trigger_reply_empty) }
                ?: stringResource(R.string.trigger_reply_missing)
        )
    }
    if (summary.inputTokens != null || summary.outputTokens != null) {
        appendLine()
        appendLine(
            if (summary.totalTokens != null) {
                stringResource(
                    R.string.trigger_usage_prompt_completion_total,
                    summary.inputTokens ?: "—",
                    summary.outputTokens ?: "—",
                    summary.totalTokens ?: "—"
                )
            } else {
                stringResource(
                    R.string.trigger_usage_input_output,
                    summary.inputTokens ?: "—",
                    summary.outputTokens ?: "—"
                )
            }
        )
    }
}.trim()

@Composable
private fun ResponseDialog(
    title: String,
    responseBody: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = if (isError) {
                        stringResource(R.string.trigger_error_detail)
                    } else {
                        stringResource(R.string.trigger_api_response)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else inkMuted()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 可复制的响应文本
                SelectionContainer {
                    Text(
                        text = responseBody.take(5000),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API Response", responseBody))
                }) {
                    Text(stringResource(R.string.action_copy))
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StrawberryPink,
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}