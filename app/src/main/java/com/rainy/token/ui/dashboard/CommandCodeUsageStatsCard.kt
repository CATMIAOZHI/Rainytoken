package com.rainy.token.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.data.local.OverviewStats
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink
import java.util.Locale

/**
 * CommandCode Go 用量统计主卡片 —— 风格与 UsageStatsCard（OCGO）完全一致。
 * 仅展示核心指标：输入 Token（含 Cache）、输出 Token、总花费，
 * 外加同步按钮和"查看详情"入口。详细统计在 CCGO 专属详情页。
 */
@Composable
fun CommandCodeUsageStatsCard(
    onOpenDetail: () -> Unit,
    refreshTrigger: Int = 0
) {
    val wid = com.rainy.token.data.repository.CommandCodeUsageRepository.CCGO_WORKSPACE_ID
    val key = "ccgo_$wid"
    val viewModel: UsageViewModel = hiltViewModel(key = key)
    // 初始化 workspace，后续刷新自动走 CCGO 的 sync use case
    LaunchedEffect(Unit) {
        viewModel.setWorkspace(wid)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 全局刷新触发用量同步（跳过首次 0）
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) viewModel.sync()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ─── 头部：标题 + 同步按钮 ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CommandCode 用量",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (uiState.recordCount > 0) "${uiState.recordCount} 条记录" else "点击同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkMuted
                    )
                }
                if (uiState.syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        strokeWidth = 2.dp,
                        color = StrawberryPink
                    )
                } else {
                    IconButton(onClick = { viewModel.sync() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "同步用量数据",
                            tint = StrawberryPink
                        )
                    }
                }
            }

            // ─── 核心指标 ───
            uiState.overview?.let { overview ->
                Spacer(modifier = Modifier.height(12.dp))

                // 输入 Token = input + cacheRead
                val inputTotal = overview.inputTokens + overview.cacheReadTokens
                MetricRow("输入 Token", formatTokenCount(inputTotal))

                // 缓存子行
                if (overview.cacheReadTokens > 0 || overview.cacheWriteTokens > 0) {
                    val parts = mutableListOf<String>()
                    if (overview.cacheReadTokens > 0) parts.add("缓存读取 ${formatTokenCount(overview.cacheReadTokens)}")
                    if (overview.cacheWriteTokens > 0) parts.add("缓存写入 ${formatTokenCount(overview.cacheWriteTokens)}")
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkMuted,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 输出 Token + 总花费 同一排
                Row(Modifier.fillMaxWidth()) {
                    MetricRow("输出 Token", formatTokenCount(overview.outputTokens), Modifier.weight(1f))
                    MetricRow("总花费", "$${String.format(Locale.US, "%.4f", overview.totalCost / 100_000_000.0)}", Modifier.weight(1f))
                }
            }

            // ─── 空状态 ───
            if (uiState.overview == null && !uiState.syncing) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (uiState.loading) "加载中…" else "暂无数据，点击 🔄 同步",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkMuted
                )
            }

            // ─── 查看详情 ───
            if (uiState.overview != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenDetail) {
                    Text("查看详情", color = StrawberryPink)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = StrawberryPink,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }

            // ─── 同步结果反馈 ───
            if (uiState.lastSyncResult > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "✓ 新增 ${uiState.lastSyncResult} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = StrawberryPink
                )
            }
            uiState.lastSyncError?.let { err ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "✗ $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = InkMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = StrawberryPink
        )
    }
}

private fun formatTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> "${"%.1f".format(Locale.US, tokens / 1_000_000.0)}M"
        tokens >= 1_000 -> "${"%.1f".format(Locale.US, tokens / 1_000.0)}K"
        else -> "$tokens"
    }
}
