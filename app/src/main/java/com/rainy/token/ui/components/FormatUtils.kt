package com.rainy.token.ui.components

import java.util.Locale

/**
 * 共享格式化工具函数。
 *
 * 从 DashboardScreen / ServiceDetailScreen / WidgetProvider 提取，消除三处重复。
 */

/** 格式化金额：整数无小数点，带小数保留 2 位 */
fun formatAmount(value: Double): String {
    return if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value)
}

/** 格式化重置倒计时（秒 → "X 天 Y 小时" / "X 小时 Y 分" / "Y 分"），无效返回 "—" */
fun formatResetInSec(sec: Long): String {
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

/** Widget 版本：逻辑相同但空值返回空串（非 "—"），适配 RemoteViews 布局 */
fun formatResetForWidget(sec: Long): String {
    if (sec <= 0) return ""
    val days = sec / 86400
    val hours = (sec % 86400) / 3600
    val minutes = (sec % 3600) / 60
    return when {
        days > 0 -> "$days 天 $hours 小时"
        hours > 0 -> "$hours 小时 $minutes 分"
        else -> "$minutes 分"
    }
}

/** 标准化窗口标签：weekly → 每周，其他原样返回 */
fun normalizeWindowLabel(label: String): String = when (label.lowercase()) {
    "weekly" -> "每周"
    else -> label
}