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
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.rainy.token.R
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.StatusStyle
import com.rainy.token.ui.components.StatusLevel
import com.rainy.token.ui.components.DurationText
import com.rainy.token.ui.components.UiText
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
                text = stringResource(R.string.service_click_to_configure),
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
                text = stringResource(R.string.service_pull_to_refresh),
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
                    text = stringResource(R.string.service_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            balance.monthlySpent?.let { spent ->
                Text(
                    text = stringResource(R.string.service_monthly_used, formatAmount(spent), balance.unit),
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
            text = stringResource(R.string.window_5h_usage),
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

@Composable
internal fun OpenCodeGoUsageWindows(balance: ServiceBalance) {
    val windows = listOf(
        Triple(stringResource(R.string.window_5h), balance.extras["rolling.pct"]?.toIntOrNull(), balance.extras["rolling.resetInSec"]?.toLongOrNull()),
        Triple(stringResource(R.string.window_weekly),   balance.extras["weekly.pct"]?.toIntOrNull(),   balance.extras["weekly.resetInSec"]?.toLongOrNull()),
        Triple(stringResource(R.string.window_monthly),   balance.extras["monthly.pct"]?.toIntOrNull(),  balance.extras["monthly.resetInSec"]?.toLongOrNull())
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
            text = stringResource(R.string.service_remaining),
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (total != null && total > 0) {
            val used = total - balance.amount
            Text(
                text = stringResource(R.string.service_used_total, formatAmount(used), formatAmount(total)),
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
        Triple(stringResource(R.string.window_5h), calcPct(extras["fiveHour.used"]?.toDoubleOrNull(), extras["fiveHour.cap"]?.toDoubleOrNull()), extras["fiveHour.resetInSec"]?.toLongOrNull()),
        Triple(stringResource(R.string.window_weekly),   calcPct(extras["weekly.used"]?.toDoubleOrNull(), extras["weekly.cap"]?.toDoubleOrNull()),   extras["weekly.resetInSec"]?.toLongOrNull()),
        Triple(stringResource(R.string.window_monthly),   calcPct(balance.monthlySpent, balance.totalQuota), extras["monthly.resetInSec"]?.toLongOrNull())
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
    val primaryLabel = formatCodexPrimaryLabel(
        balance.extras["primary.label"],
        weeklyLabel = stringResource(R.string.window_every_week),
        monthlyLabel = stringResource(R.string.window_every_month),
        usageLabel = stringResource(R.string.window_usage)
    )
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
            text = stringResource(R.string.service_used_label, primaryLabel),
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.service_plan_suffix, plan),
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

/**
 * 把 Codex API 返回的 primary label 转成本地化短标签。
 * 默认中文标签保持测试兼容；UI 调用方传入 stringResource 结果。
 */
internal fun formatCodexPrimaryLabel(
    raw: String?,
    weeklyLabel: String = "每周",
    monthlyLabel: String = "每月",
    usageLabel: String = "用量"
): String = when (raw?.lowercase()) {
    "5h" -> "5h"
    "7d", "每周", weeklyLabel -> weeklyLabel
    "30d", "每月", monthlyLabel -> monthlyLabel
    "usage" -> usageLabel
    null -> "5h"
    else -> raw ?: "5h"
}

@Composable
internal fun CodexUsageWindows(balance: ServiceBalance) {
    val extras = balance.extras
    val windowCount = extras.keys
        .mapNotNull { key -> key.removePrefix("window_").substringBefore('.').toIntOrNull() }
        .distinct()
        .maxOrNull()?.plus(1) ?: 0

    val weeklyLabel = stringResource(R.string.window_every_week)
    val monthlyLabel = stringResource(R.string.window_every_month)
    val windows = (0 until windowCount).map { i ->
        val label = normalizeWindowLabel(
            extras["window_$i.label"] ?: "Usage",
            weeklyLabel = weeklyLabel,
            monthlyLabel = monthlyLabel
        )
        val remainingPct = extras["window_$i.remainingPct"]?.toIntOrNull()
        val resetAt = extras["window_$i.resetAt"]?.toLongOrNull()?.takeIf { it > 0 }
        Triple(label, remainingPct, resetAt)
    }

    // 判断是否有 5h 窗口（用于保留空槽位）
    val fiveHourLabel = stringResource(R.string.window_5h)
    val has5h = windows.any { it.first.contains("5") && it.first.contains("小时") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (windows.isEmpty()) {
            CompactUsageRowEmpty(label = "Usage", resetInSec = null)
        } else {
            // 如果没有 5h 窗口，在顶部插入一个空的 5h 槽位（保留位置，等恢复后自动填充）
            if (!has5h) {
                CompactUsageRowEmpty(label = fiveHourLabel, resetInSec = null)
            }
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
            text = stringResource(R.string.service_5h_used),
            style = MaterialTheme.typography.titleMedium,
            color = inkMuted(),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.service_plan_suffix, plan),
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
    val weeklyLabel = stringResource(R.string.window_every_week)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sessionPct != null) {
            CompactUsageRow(
                label = stringResource(R.string.window_5h_short),
                pct = sessionPct.toInt().coerceIn(0, 100),
                resetInSec = sessionResetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 }
            )
        } else {
            CompactUsageRowEmpty(label = stringResource(R.string.window_5h_short), resetInSec = null)
        }
        if (weeklyPct != null) {
            CompactUsageRow(
                label = weeklyLabel,
                pct = weeklyPct.toInt().coerceIn(0, 100),
                resetInSec = weeklyResetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 }
            )
        } else {
            CompactUsageRowEmpty(label = weeklyLabel, resetInSec = null)
        }
    }
}

// ── Shared UI components ──

@Composable
internal fun CompactUsageRowEmpty(label: String, resetInSec: Long?) {
    val durationText = rememberDurationText()
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
                text = stringResource(R.string.service_reset_in, formatResetInSec(resetInSec, durationText)),
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
        }
    }
}

@Composable
internal fun CompactUsageRow(label: String, pct: Int, resetInSec: Long?) {
    val durationText = rememberDurationText()
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
                text = stringResource(R.string.service_reset_in, formatResetInSec(resetInSec, durationText)),
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
        }
    }
}

/** 当前语言环境的时长标签（"天 / 小时 / 分"）。 */
@Composable
private fun rememberDurationText(): DurationText = DurationText(
    day = stringResource(R.string.format_day),
    hour = stringResource(R.string.format_hour),
    minute = stringResource(R.string.format_minute)
)

// ── Dashboard utility functions ──

internal fun DashboardCardUi.statusBadgeStyle(): StatusStyle = when {
    credentialState == CredentialStatus.State.NOT_CONFIGURED ->
        StatusStyle(R.string.status_not_configured, StatusLevel.WARNING)
    lastFetchError != null ->
        StatusStyle(R.string.status_refresh_failed, StatusLevel.ERROR)
    credentialState == CredentialStatus.State.EXPIRED ->
        StatusStyle(R.string.status_expired, StatusLevel.ERROR)
    credentialState == CredentialStatus.State.WARNING ->
        StatusStyle(R.string.status_relogin, StatusLevel.WARNING)
    cachedBalance == null ->
        StatusStyle(R.string.status_waiting, StatusLevel.INFO)
    else ->
        StatusStyle(R.string.status_normal, StatusLevel.OK)
}

/** 服务副标题（资源 ID，由 UI 层解析）。 */
@StringRes
internal fun secondaryLineRes(card: DashboardCardUi): Int = when (card.service) {
    ServiceType.DEEPSEEK -> R.string.service_desc_deepseek
    ServiceType.OPENCODE_GO -> R.string.service_desc_opencode_go
    ServiceType.COMMANDCODE_GO -> R.string.service_desc_commandcode_go
    ServiceType.CODEX -> R.string.service_desc_codex
    ServiceType.OLLAMA -> R.string.service_desc_ollama
}

/**
 * 底部更新时间/错误文案（UiText 形式，UI 层按当前语言解析）。
 * 错误信息由 ViewModel 按 RepositoryError 类型映射为本地化资源。
 */

/** 截断 UiText 到最长 60 字符（仅对 Dynamic 原始文本生效；Resource 长度由资源控制）。 */
private fun truncateUiText(text: UiText): UiText = when (text) {
    is UiText.Dynamic -> UiText.Dynamic(text.value.take(60) + if (text.value.length > 60) "…" else "")
    else -> text
}

internal fun footerText(card: DashboardCardUi): UiText {
    val error = card.lastFetchError
    if (error != null) {
        // 截断保护：错误信息最长展示 60 字符（以 UiText 形式保留，UI 层按当前语言解析）
        return UiText.Resource(R.string.dashboard_error_prefix, listOf(truncateUiText(error)))
    }
    val fetchedAt = card.cachedBalance?.fetchedAt
        ?: return UiText.Resource(R.string.footer_never_fetched)
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val now = System.currentTimeMillis()
    val diffMin = (now - fetchedAt) / 60_000
    val timeStr = sdf.format(Date(fetchedAt))
    return when {
        diffMin < 1 -> UiText.Resource(R.string.footer_just_updated)
        diffMin < 60 -> UiText.Resource(R.string.footer_minutes_ago, listOf(diffMin))
        diffMin < 1440 -> UiText.Resource(R.string.footer_hours_ago, listOf(diffMin / 60))
        else -> UiText.Resource(R.string.footer_updated_at, listOf(timeStr))
    }
}