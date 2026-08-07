package com.rainy.token.ui.components

import java.util.Locale

/**
 * 共享格式化工具函数。
 *
 * 从 DashboardScreen / ServiceDetailScreen / WidgetProvider 提取，消除三处重复。
 *
 * 本地化策略：函数保持纯 JVM（不依赖 Context / Resources，可被单元测试直接调用），
 * 本地化标签通过 [DurationText] / 字符串参数注入。调用方（Compose / Widget）用
 * `context.getString(...)` 取得当前语言标签后传入；测试继续使用默认中文标签。
 */

/** 时长标签（用于 "X 天 Y 小时" 这类拼接）。 */
data class DurationText(
    val day: String,
    val hour: String,
    val minute: String,
    /** 无有效时长时展示的占位符 */
    val dash: String = "—"
)

/** 默认中文标签（保持与原实现输出完全一致，供单元测试与兜底使用）。 */
val ChineseDurationText = DurationText(day = "天", hour = "小时", minute = "分")

/** 格式化金额：整数无小数点，带小数保留 2 位（金额数值格式固定 Locale.US，避免小数点歧义） */
fun formatAmount(value: Double): String {
    return if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value)
}

/** 格式化重置倒计时（秒 → "X 天 Y 小时" / "X 小时 Y 分" / "Y 分"），无效返回 "—" */
fun formatResetInSec(sec: Long, text: DurationText = ChineseDurationText): String {
    if (sec <= 0) return text.dash
    val days = sec / 86400
    val hours = (sec % 86400) / 3600
    val minutes = (sec % 3600) / 60
    return when {
        days > 0 -> "$days ${text.day} $hours ${text.hour}"
        hours > 0 -> "$hours ${text.hour} $minutes ${text.minute}"
        else -> "$minutes ${text.minute}"
    }
}

/** Widget 版本：逻辑相同但空值返回空串（非 "—"），适配 RemoteViews 布局 */
fun formatResetForWidget(sec: Long, text: DurationText = ChineseDurationText): String {
    if (sec <= 0) return ""
    val days = sec / 86400
    val hours = (sec % 86400) / 3600
    val minutes = (sec % 3600) / 60
    return when {
        days > 0 -> "$days ${text.day} $hours ${text.hour}"
        hours > 0 -> "$hours ${text.hour} $minutes ${text.minute}"
        else -> "$minutes ${text.minute}"
    }
}

/** 标准化窗口标签：weekly/每周 → 每周标签，monthly/每月 → 每月标签，usage/用量 → 用量标签，其他原样返回 */
fun normalizeWindowLabel(
    label: String,
    weeklyLabel: String = "Weekly",
    monthlyLabel: String = "Monthly",
    usageLabel: String = "Usage"
): String = when (label.lowercase()) {
    "weekly", "每周" -> weeklyLabel
    "monthly", "每月" -> monthlyLabel
    "usage", "用量" -> usageLabel
    else -> label
}

/**
 * 判断窗口标签是否为 5h 窗口（与语言无关）：
 * - 匹配英文缩写（5h/5H）
 * - 匹配本地化标签（如 "5小时"/"5 小时"/"5小時"）
 * - 兼容历史中文数据（含 "5" 且含 "小时"）
 */
fun isFiveHourLabel(label: String, fiveHourLabel: String, fiveHourShort: String): Boolean {
    val normalized = label.trim()
    return normalized.equals(fiveHourShort, ignoreCase = true) ||
        normalized == fiveHourLabel ||
        (normalized.contains("5") && normalized.contains("小时")) ||
        (normalized.contains("5") && normalized.contains("小時"))
}