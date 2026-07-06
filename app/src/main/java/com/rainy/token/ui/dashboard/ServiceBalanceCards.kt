package com.rainy.token.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.StatusStyle
import com.rainy.token.ui.components.StatusLevel
import com.rainy.token.ui.components.formatAmount
import com.rainy.token.ui.components.formatResetInSec
import com.rainy.token.ui.components.normalizeWindowLabel
import com.rainy.token.ui.theme.StrawberryPink
import com.rainy.token.ui.theme.StatusOrange
import com.rainy.token.ui.theme.inkMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Service-specific balance Composables ──

@Composable
internal fun BalanceMainArea(card: DashboardCardUi) {
    val balance = card.displayBalance
    when {
        card.credentialState == CredentialStatus.State.NOT_CONFIGURED -> {
            Text(
                text = "—",
                style = MaterialTheme.typography.displayMedium,
                color = inkMuted()
            )
            Text(
                text = "点击配置凭据",
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
        }
        balance == null -> {
            Text(
                text = "—",
                style = MaterialTheme.typography.displayMedium,
                color = inkMuted()
            )
            Text(
                text = "下拉刷新",
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
        }
        card.service == ServiceType.OPENCODE_GO -> {
            OpenCodeGoMainBalance(balance)
            Spacer(modifier = Modifier.height(12.dp))
            OpenCodeGoUsageWindows(balance)
        }
        card.service == ServiceType.COMMANDCODE_GO -> {
            CommandCodeGoMainBalance(balance)
            Spacer(modifier = Modifier.height(12.dp))
            CommandCodeGoUsageWindows(balance)
        }
        card.service == ServiceType.CODEX -> {
            CodexMainBalance(balance)
            Spacer(modifier = Modifier.height(12.dp))
            CodexUsageWindows(balance)
        }
        card.service == ServiceType.OLLAMA -> {
            OllamaMainBalance(balance)
            Spacer(modifier = Modifier.height(12.dp))
            OllamaUsageWindows(balance)
        }
        else -> {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatAmount(balance.amount),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = balance.unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = inkMuted(),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            if (!balance.isAvailable) {
                Text(
                    text = "服务当前不可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            balance.monthlySpent?.let { spent ->
                Text(
                    text = "本月已用 ${formatAmount(spent)}${balance.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted()
                )
            }
        }
    }
}

@Composable
internal fun OpenCodeGoMainBalance(balance: ServiceBalance) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatAmount(balance.amount),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "%",
            style = MaterialTheme.typography.titleLarge,
            color = inkMuted(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "5h 用量",
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

@Composable
internal fun OpenCodeGoUsageWindows(balance: ServiceBalance) {
    val windows = listOf(
        Triple("5 小时", balance.extras["rolling.pct"]?.toIntOrNull(), balance.extras["rolling.resetInSec"]?.toLongOrNull()),
        Triple("本周",   balance.extras["weekly.pct"]?.toIntOrNull(),   balance.extras["weekly.resetInSec"]?.toLongOrNull()),
        Triple("本月",   balance.extras["monthly.pct"]?.toIntOrNull(),  balance.extras["monthly.resetInSec"]?.toLongOrNull())
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        windows.forEach { (label, pct, resetSec) ->
            if (pct != null) {
                CompactUsageRow(label = label, pct = pct, resetInSec = resetSec)
            } else {
                CompactUsageRowEmpty(label = label, resetInSec = resetSec)
            }
        }
    }
}

@Composable
internal fun CommandCodeGoMainBalance(balance: ServiceBalance) {
    val total = balance.totalQuota
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatAmount(balance.amount),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "$",
            style = MaterialTheme.typography.titleLarge,
            color = inkMuted(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "剩余",
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (total != null && total > 0) {
            val used = total - balance.amount
            Text(
                text = " · 已用 ${formatAmount(used)} / 共 ${formatAmount(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted(),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }
}

@Composable
internal fun CommandCodeGoUsageWindows(balance: ServiceBalance) {
    val extras = balance.extras
    fun calcPct(used: Double?, cap: Double?): Int? {
        if (used == null || cap == null || cap <= 0) return null
        return ((used / cap) * 100).toInt().coerceIn(0, 100)
    }
    val windows = listOf(
        Triple("5 小时", calcPct(extras["fiveHour.used"]?.toDoubleOrNull(), extras["fiveHour.cap"]?.toDoubleOrNull()), extras["fiveHour.resetInSec"]?.toLongOrNull()),
        Triple("本周",   calcPct(extras["weekly.used"]?.toDoubleOrNull(), extras["weekly.cap"]?.toDoubleOrNull()),   extras["weekly.resetInSec"]?.toLongOrNull()),
        Triple("本月",   calcPct(balance.monthlySpent, balance.totalQuota), extras["monthly.resetInSec"]?.toLongOrNull())
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        windows.forEach { (label, pct, resetSec) ->
            if (pct != null) {
                CompactUsageRow(label = label, pct = pct, resetInSec = resetSec)
            } else {
                CompactUsageRowEmpty(label = label, resetInSec = resetSec)
            }
        }
    }
}

@Composable
internal fun CodexMainBalance(balance: ServiceBalance) {
    val plan = balance.extras["plan"]?.let {
        when (it) { "plus" -> "Plus"; "pro" -> "Pro"; "free" -> "Free"; else -> it.replaceFirstChar { c -> c.uppercaseChar() } }
    } ?: "—"
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatAmount(balance.amount),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "%",
            style = MaterialTheme.typography.titleLarge,
            color = inkMuted(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "5h 剩余",
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "· $plan",
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

@Composable
internal fun CodexUsageWindows(balance: ServiceBalance) {
    val extras = balance.extras
    val windowCount = extras.keys
        .mapNotNull { key -> key.removePrefix("window_").substringBefore('.').toIntOrNull() }
        .distinct()
        .maxOrNull()?.plus(1) ?: 0

    val windows = (0 until windowCount).map { i ->
        val label = normalizeWindowLabel(extras["window_$i.label"] ?: "Usage")
        val remainingPct = extras["window_$i.remainingPct"]?.toIntOrNull()
        val resetAt = extras["window_$i.resetAt"]?.toLongOrNull()?.takeIf { it > 0 }
        Triple(label, remainingPct, resetAt)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (windows.isEmpty()) {
            CompactUsageRowEmpty(label = "Usage", resetInSec = null)
        } else {
            windows.forEach { (label, remainingPct, resetAt) ->
                if (remainingPct != null) {
                    val usedPct = (100 - remainingPct).coerceIn(0, 100)
                    CompactUsageRow(label = label, pct = usedPct, resetInSec = resetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                } else {
                    CompactUsageRowEmpty(label = label, resetInSec = resetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                }
            }
        }
    }
}

@Composable
internal fun OllamaMainBalance(balance: ServiceBalance) {
    val plan = balance.extras["plan"]?.let {
        when (it.lowercase()) { "pro" -> "Pro"; "max" -> "Max"; "free" -> "Free"; else -> it }
    } ?: "—"
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatAmount(balance.amount),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "%",
            style = MaterialTheme.typography.titleLarge,
            color = inkMuted(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "5h 已用",
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "· $plan",
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

@Composable
internal fun OllamaUsageWindows(balance: ServiceBalance) {
    val extras = balance.extras
    val sessionPct = extras["session.pct"]?.toFloatOrNull()
    val weeklyPct = extras["weekly.pct"]?.toFloatOrNull()
    val sessionResetAt = extras["session.resetAt"]?.toLongOrNull()
    val weeklyResetAt = extras["weekly.resetAt"]?.toLongOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sessionPct != null) {
            CompactUsageRow(
                label = "5h",
                pct = sessionPct.toInt().coerceIn(0, 100),
                resetInSec = sessionResetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 }
            )
        } else {
            CompactUsageRowEmpty(label = "5h", resetInSec = null)
        }
        if (weeklyPct != null) {
            CompactUsageRow(
                label = "每周",
                pct = weeklyPct.toInt().coerceIn(0, 100),
                resetInSec = weeklyResetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 }
            )
        } else {
            CompactUsageRowEmpty(label = "每周", resetInSec = null)
        }
    }
}

// ── Shared UI components ──

@Composable
internal fun CompactUsageRowEmpty(label: String, resetInSec: Long?) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted(),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "—",
                style = MaterialTheme.typography.bodyMedium,
                color = inkMuted()
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = inkMuted().copy(alpha = 0.3f),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Butt
        )
        if (resetInSec != null && resetInSec > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatResetInSec(resetInSec)}后重置",
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
        }
    }
}

@Composable
internal fun CompactUsageRow(label: String, pct: Int, resetInSec: Long?) {
    val pctValue = pct.coerceIn(0, 100).toFloat()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted(),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    pct >= 80 -> MaterialTheme.colorScheme.error
                    pct >= 50 -> StatusOrange
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { pctValue / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = when {
                pctValue >= 80f -> MaterialTheme.colorScheme.error
                pctValue >= 50f -> StatusOrange
                else -> StrawberryPink
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Butt
        )
        if (resetInSec != null && resetInSec > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatResetInSec(resetInSec)}后重置",
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
        }
    }
}

// ── Dashboard utility functions ──

internal fun DashboardCardUi.statusBadgeStyle(): StatusStyle = when {
    credentialState == CredentialStatus.State.NOT_CONFIGURED ->
        StatusStyle("未配置", StatusLevel.WARNING)
    lastFetchError != null ->
        StatusStyle("刷新失败", StatusLevel.ERROR)
    credentialState == CredentialStatus.State.EXPIRED ->
        StatusStyle("已过期", StatusLevel.ERROR)
    credentialState == CredentialStatus.State.WARNING ->
        StatusStyle("需重登", StatusLevel.WARNING)
    cachedBalance == null ->
        StatusStyle("待获取", StatusLevel.INFO)
    else ->
        StatusStyle("正常", StatusLevel.OK)
}

internal fun secondaryLine(card: DashboardCardUi): String = when (card.service) {
    ServiceType.DEEPSEEK -> "REST API · ¥"
    ServiceType.OPENCODE_GO -> "WebView 抓取 · 5h 配额"
    ServiceType.COMMANDCODE_GO -> "JSON API · $"
    ServiceType.CODEX -> "ChatGPT Plus · Codex 额度"
    ServiceType.OLLAMA -> "Cookie 抓取 · Cloud 配额"
}

internal fun footerText(card: DashboardCardUi): String {
    if (card.lastFetchError != null) {
        val msg = card.lastFetchError.take(60)
        return "⚠ $msg${if (card.lastFetchError.length > 60) "…" else ""}"
    }
    val fetchedAt = card.cachedBalance?.fetchedAt ?: return "从未获取"
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val now = System.currentTimeMillis()
    val diffMin = (now - fetchedAt) / 60_000
    val timeStr = sdf.format(Date(fetchedAt))
    return when {
        diffMin < 1 -> "刚刚更新"
        diffMin < 60 -> "$diffMin 分钟前更新"
        diffMin < 1440 -> "${diffMin / 60} 小时前更新"
        else -> "$timeStr 更新"
    }
}