package com.rainy.token.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.ServiceIcon
import com.rainy.token.ui.components.StatusChip
import com.rainy.token.ui.components.StatusLevel
import com.rainy.token.ui.components.StatusStyle
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.rainy.token.ui.widget.OpenCodeGoWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仪表盘主页（雨晴风格重做版）。
 *
 * 视觉：
 *  - 顶部 TopAppBar 透明 + 渐变背景
 *  - 下拉刷新（PullToRefresh）触发 DashboardViewModel.refresh()
 *  - 卡片：白底圆角 + 左侧服务图标 + 中间余额大数字 + 右侧状态 chip
 *  - 卡片底部展示"更新于 X 分钟前"或错误信息
 *  - 主数字加粗超大，视觉锚点
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenService: (ServiceType) -> Unit,
    onOpenUsageDetail: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 全局刷新触发器——每次 dashboard 刷新完成后 +1，UsageStatsCard 据此同步用量数据
    var usageSyncTrigger by remember { mutableIntStateOf(0) }
    var lastRefreshing by remember { mutableStateOf(uiState.refreshing) }
    LaunchedEffect(uiState.refreshing) {
        // 仅当 refreshing 从 true → false 时触发（即 refresh() 真正完成了）
        if (lastRefreshing && !uiState.refreshing) {
            usageSyncTrigger++
        }
        lastRefreshing = uiState.refreshing
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "RainyToken",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "AI 余额一览",
                            style = MaterialTheme.typography.bodySmall,
                            color = inkMuted()
                        )
                    }
                },
                actions = {
                    // 添加小组件：优先 requestPinAppWidget，不支持则打开系统小组件选择器
                    val context = LocalContext.current
                    IconButton(onClick = {
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val component = ComponentName(context, OpenCodeGoWidgetProvider::class.java)
                        if (appWidgetManager.isRequestPinAppWidgetSupported) {
                            appWidgetManager.requestPinAppWidget(component, null, null)
                        } else {
                            // MIUI 等桌面回退：直接打开小组件选择器
                            try {
                                val pickerIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                                }
                                context.startActivity(pickerIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "请在桌面长按 → 小组件 → 查找雨晴Token", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加小组件到桌面",
                            tint = StrawberryPink
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !uiState.refreshing
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = StrawberryPink
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
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
        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.cards.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = StrawberryPink)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 用量统计卡片（主页面，独立 ViewModel）
                    item { UsageStatsCard(onOpenDetail = onOpenUsageDetail, refreshTrigger = usageSyncTrigger) }
                    // 分隔
                    item {
                        Text(
                            text = "服务余额",
                            style = MaterialTheme.typography.labelMedium,
                            color = inkMuted(),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(uiState.cards, key = { it.service.name }) { card ->
                        DashboardCard(card = card, onClick = { onOpenService(card.service) })
                    }
                    // 底部 footer：填空白 + 提供版本号
                    item { DashboardFooter() }
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(card: DashboardCardUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ─── 顶部：图标 + 名称 + 状态 chip ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceIcon(service = card.service, size = 44)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.service.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = secondaryLine(card),
                        style = MaterialTheme.typography.bodySmall,
                        color = inkMuted()
                    )
                }
                StatusChip(style = card.statusBadgeStyle())
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ─── 主体：服务特定的主信息 ───
            BalanceMainArea(card)

            // ─── 底部：更新时间 / 错误信息 ───
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = footerText(card),
                style = MaterialTheme.typography.bodySmall,
                color = if (card.lastFetchError != null)
                    MaterialTheme.colorScheme.error
                else
                    inkMuted()
            )
        }
    }
}

@Composable
private fun BalanceMainArea(card: DashboardCardUi) {
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
            // OpenCode Go 卡片：3 个用量窗口平铺
            // 主大数字 = 5h 滚动用量
            OpenCodeGoMainBalance(balance)
            Spacer(modifier = Modifier.height(12.dp))
            // 3 个窗口进度条
            OpenCodeGoUsageWindows(balance)
        }
        else -> {
            // DeepSeek 等：主数字 + 单位
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
private fun OpenCodeGoMainBalance(balance: com.rainy.token.domain.model.ServiceBalance) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatAmount(balance.amount),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        // % 符号跟主数字走，字号略小
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
private fun OpenCodeGoUsageWindows(balance: com.rainy.token.domain.model.ServiceBalance) {
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
                // 数据未拿到 —— 不隐藏整行，显示"—"占位
                CompactUsageRowEmpty(label = label, resetInSec = resetSec)
            }
        }
    }
}

@Composable
private fun CompactUsageRowEmpty(label: String, resetInSec: Long?) {
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
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt
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
private fun CompactUsageRow(label: String, pct: Int, resetInSec: Long?) {
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
                    pct >= 50 -> com.rainy.token.ui.theme.StatusOrange
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
                pctValue >= 50f -> com.rainy.token.ui.theme.StatusOrange
                else -> StrawberryPink
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            // strokeCap = StrokeCap.Butt 让进度条右端不出现 Material 默认的小圆点
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt
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

private fun DashboardCardUi.statusBadgeStyle(): StatusStyle = when {
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

private fun secondaryLine(card: DashboardCardUi): String = when (card.service) {
    ServiceType.DEEPSEEK -> "REST API · ¥"
    ServiceType.OPENCODE_GO -> "WebView 抓取 · 5h 配额"
}

private fun footerText(card: DashboardCardUi): String {
    if (card.lastFetchError != null) {
        // 截短错误信息避免换行爆炸
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

private fun formatAmount(value: Double): String {
    // 整数显示无小数点；带小数的保留 2 位
    return if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value)
}

/**
 * 仪表盘底部 footer：填空白 + 显示版本号。
 * 容器透明无边框，让卡片列表与背景融合自然。
 */
@Composable
private fun DashboardFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RainyToken · v1.0",
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "本地加密存储，不上传任何数据",
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted()
        )
    }
}