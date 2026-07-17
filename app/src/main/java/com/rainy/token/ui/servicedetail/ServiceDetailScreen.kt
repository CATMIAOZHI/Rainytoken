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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.FetchMethod
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.ServiceIcon
import com.rainy.token.ui.components.StatusChip
import com.rainy.token.ui.components.StatusLevel
import com.rainy.token.ui.components.StatusStyle
import com.rainy.token.ui.components.formatAmount
import com.rainy.token.ui.components.formatResetInSec
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val triggerState by viewModel.triggerState.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val modelsLoading by viewModel.modelsLoading.collectAsStateWithLifecycle()
    val config = ServiceConfigProvider.get(service)
    val isManualMode = config.method == FetchMethod.MANUAL

    // Codex 服务且有凭据时自动加载模型列表
    LaunchedEffect(service, uiState.hasCredential) {
        if (service == ServiceType.CODEX && uiState.hasCredential) {
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
                            contentDescription = "返回",
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
                item { ErrorCard(message = err.message) }
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
                    onTriggerUsage = { viewModel.triggerCodexUsage() },
                    onSelectModel = { viewModel.selectModel(it) },
                    onRefreshModels = { viewModel.loadModels(force = true) }
                )
            }
        }
    }

    // 响应弹窗（成功和失败都弹）
    when (triggerState) {
        is TriggerState.Success -> ResponseDialog(
            title = "✓ 用量已激活",
            responseBody = (triggerState as TriggerState.Success).responseBody,
            isError = false,
            onDismiss = { viewModel.dismissTrigger() }
        )
        is TriggerState.Error -> ResponseDialog(
            title = "请求结果",
            responseBody = (triggerState as TriggerState.Error).let { 
                "错误: ${it.message}" + (it.responseBody?.let { body -> "\n\n响应内容:\n$body" } ?: "")
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
                text = "用量窗口${if (planName.isNotBlank()) " · $planName" else ""}",
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
                    label = "5 小时滚动",
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
                    label = "本周",
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
                    label = "本月",
                    pct = pct,
                    resetInSec = extras["billingPeriodEnd"]?.let { parseIsoDuration(it) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已用 \$${formatAmount(used)} / 共 \$${formatAmount(monthlyTotal)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted()
                )
            } else {
                Text(
                    text = "剩余 \$${formatAmount(monthlyRemaining)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // 额外充值
            if (purchased > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                BreakdownRow("额外充值", purchased, "$")
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
                text = "用量窗口",
                style = MaterialTheme.typography.labelLarge,
                color = inkMuted()
            )
            Spacer(modifier = Modifier.height(12.dp))
            UsageWindowRow(
                label = "5 小时滚动",
                pct = extras["rolling.pct"]?.toFloatOrNull(),
                resetInSec = extras["rolling.resetInSec"]?.toLongOrNull()
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            UsageWindowRow(
                label = "本周",
                pct = extras["weekly.pct"]?.toFloatOrNull(),
                resetInSec = extras["weekly.resetInSec"]?.toLongOrNull()
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            UsageWindowRow(
                label = "本月",
                pct = extras["monthly.pct"]?.toFloatOrNull(),
                resetInSec = extras["monthly.resetInSec"]?.toLongOrNull()
            )
        }
    }
}

@Composable
private fun UsageWindowRow(label: String, pct: Float?, resetInSec: Long?, decimals: Int = 0) {
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
                text = "${formatResetInSec(resetInSec)}后重置",
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
    val windows = remember(extras) { extractCodexWindows(extras) }
    if (windows.isEmpty()) return

    // 判断是否有 5h 窗口
    val has5h = windows.any { it.label.contains("5") && it.label.contains("小时") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "用量窗口",
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
                    label = "5 小时",
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
                    label = window.label,
                    pct = window.usedPct,
                    resetInSec = resetInSec,
                    decimals = 2
                )
            }
        }
    }
}

private data class CodexWindow(
    val label: String,
    val usedPct: Float,
    val resetAt: Long?
)

private fun extractCodexWindows(extras: Map<String, String>): List<CodexWindow> {
    val result = mutableListOf<CodexWindow>()
    var i = 0
    while (true) {
        val rawLabel = extras["window_${i}.label"] ?: break
        val remaining = extras["window_${i}.remainingPct"]?.toFloatOrNull() ?: 0f
        val resetAt = extras["window_${i}.resetAt"]?.toLongOrNull()?.takeIf { it > 0 }
        val usedPct = (100f - remaining).coerceIn(0f, 100f)
        result.add(CodexWindow(formatCodexWindowLabel(rawLabel), usedPct, resetAt))
        i++
    }
    return result
}

private fun formatCodexWindowLabel(raw: String): String = when (raw.lowercase()) {
    "5h" -> "5 小时"
    "7d", "每周" -> "本周"
    "30d", "每月" -> "本月"
    else -> raw
}

/**
 * Ollama Pro 专属：5h + 每周用量窗口卡 + 模型级调用次数。
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
    val plan = extras["plan"] ?: "—"

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
                    text = "用量窗口",
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
                label = "5 小时",
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
                label = "每周",
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
            text = "模型调用次数",
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
                        text = "缓存数据 · ${formatTime(state.lastFetchedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is State.Error -> {
                    BalanceBigNumber(state.cached)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "显示的是上次成功获取的余额",
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
            text = "服务当前不可用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    balance.monthlySpent?.let { spent ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "本月消费 ${formatAmount(spent)}${balance.unit}",
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
                text = "余额构成",
                style = MaterialTheme.typography.labelLarge,
                color = inkMuted()
            )
            Spacer(modifier = Modifier.height(12.dp))
            BreakdownRow("赠送余额", granted, balance!!.unit)
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            BreakdownRow("自费充值", toppedUp, balance.unit)
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
                text = "刷新失败",
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
        isManualMode -> StatusStyle("手动输入模式", StatusLevel.INFO)
        hasCredential -> StatusStyle("凭据已配置", StatusLevel.OK)
        else -> StatusStyle("未配置凭据", StatusLevel.ERROR)
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
        // Codex 一键激活用量区域
        if (service == ServiceType.CODEX && hasCredential && !isManualMode) {
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
                    Text("加载模型列表...", style = MaterialTheme.typography.bodySmall, color = inkMuted())
                }
            } else if (models.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择模型",
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
                            contentDescription = "刷新模型列表",
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
                        Text("激活中...")
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
                        Text("重试激活用量")
                    }
                    Text(
                        text = triggerState.message,
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
                        Text("⚡ 一键激活用量")
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
                Text("刷新余额")
            }
            OutlinedButton(
                onClick = onStartWebViewLogin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新登录")
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
                Text("配置凭据")
            }
        }
    }
}

private fun mainCardLabel(service: ServiceType): String = when (service) {
    ServiceType.DEEPSEEK -> "当前余额"
    ServiceType.OPENCODE_GO -> "5h 实时用量"
    ServiceType.COMMANDCODE_GO -> "月度余额"
    ServiceType.CODEX -> "用量"
    ServiceType.OLLAMA -> "Session 用量"
}

private fun stateToChip(state: State): StatusStyle = when (state) {
    is State.Loading -> StatusStyle("加载中", StatusLevel.INFO)
    is State.Fresh -> StatusStyle("最新", StatusLevel.OK)
    is State.Stale -> StatusStyle("缓存", StatusLevel.WARNING)
    is State.Error -> StatusStyle("失败", StatusLevel.ERROR)
    is State.ManualModeHint -> StatusStyle("待输入", StatusLevel.WARNING)
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
    val display = selectedModel ?: "请选择模型"

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
                    text = if (isError) "错误详情：" else "API 响应内容：",
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
                    Text("复制")
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StrawberryPink,
                        contentColor = Color.White
                    )
                ) {
                    Text("关闭")
                }
            }
        }
    )
}