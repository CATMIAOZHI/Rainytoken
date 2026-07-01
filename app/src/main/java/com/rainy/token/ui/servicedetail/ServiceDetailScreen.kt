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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val config = ServiceConfigProvider.get(service)
    val isManualMode = config.method == FetchMethod.MANUAL

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
                    // Codex 详情：暂无专用详情卡片，用通用余额展示即可
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
                    onRefresh = { viewModel.refresh() },
                    onConfigureCredential = { onConfigureCredential(service) },
                    onStartWebViewLogin = { onStartWebViewLogin(service) }
                )
            }
        }
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
                val pct = ((fiveHourUsed / fiveHourCap) * 100).toInt().coerceIn(0, 100)
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
                val pct = ((weeklyUsed / weeklyCap) * 100).toInt().coerceIn(0, 100)
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
                val pct = ((used / monthlyTotal) * 100).toInt().coerceIn(0, 100)
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
                pct = extras["rolling.pct"]?.toIntOrNull(),
                resetInSec = extras["rolling.resetInSec"]?.toLongOrNull()
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            UsageWindowRow(
                label = "本周",
                pct = extras["weekly.pct"]?.toIntOrNull(),
                resetInSec = extras["weekly.resetInSec"]?.toLongOrNull()
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))
            UsageWindowRow(
                label = "本月",
                pct = extras["monthly.pct"]?.toIntOrNull(),
                resetInSec = extras["monthly.resetInSec"]?.toLongOrNull()
            )
        }
    }
}

@Composable
private fun UsageWindowRow(label: String, pct: Int?, resetInSec: Long?) {
    val pctValue = (pct ?: 0).coerceIn(0, 100).toFloat()
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
                    text = (pct ?: 0).toString(),
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

private fun formatResetInSec(sec: Long): String {
    if (sec <= 0) return "—"
    val days = sec / 86400
    val hours = (sec % 86400) / 3600
    val minutes = (sec % 3600) / 60
    return when {
        days > 0 -> "$days 天 $hours 小时"
        hours > 0 -> "$hours 小时 $minutes 分"
        else -> "$minutes 分"
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
    onRefresh: () -> Unit,
    onConfigureCredential: () -> Unit,
    onStartWebViewLogin: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    ServiceType.CODEX -> "5h 剩余额度"
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

private fun formatAmount(value: Double): String {
    return if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value)
}