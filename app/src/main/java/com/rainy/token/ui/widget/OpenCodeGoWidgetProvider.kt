package com.rainy.token.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.RemoteViews
import com.rainy.token.MainActivity
import com.rainy.token.R
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.balanceCacheDataStore
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenCode Go 桌面小组件（MIUI Widget）。
 *
 * 显示 3 个用量窗口（5h / 本周 / 本月）的百分比 + 进度条 + 重置倒计时 + DeepSeek 余额。
 *
 * 刷新路径：
 * - MIUI 曝光刷新：用户划到负一屏 → miui.appwidget.action.APPWIDGET_UPDATE → onReceive → onUpdate
 * - 标准定时刷新：系统 30min 定时 → android.appwidget.action.APPWIDGET_UPDATE → onUpdate
 * - 手动 ↻ 按钮：PendingIntent 直通 WidgetRefreshReceiver → 网络请求 → notifyDataChanged
 * - APP 内刷新：notifyDataChanged() → 广播 ACTION_APPWIDGET_UPDATE → onUpdate
 * - 缓存为空/过期时 onUpdate 自动触发后台刷新
 */
class OpenCodeGoWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SWITCH_SERVICE -> {
                switchDisplayService(context)
                notifyDataChanged(context)
            }
            "miui.appwidget.action.APPWIDGET_UPDATE" -> {
                val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                if (appWidgetIds != null) {
                    onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        var selectedHasCachedData = false

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_opencode_go)

            // 点击 Widget → 打开 APP
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_content, pendingIntent)

            // 刷新按钮 → 后台广播刷新
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 1, WidgetRefreshReceiver.createIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)

            val switchPendingIntent = PendingIntent.getBroadcast(
                context, 2, Intent(context, OpenCodeGoWidgetProvider::class.java).apply { action = ACTION_SWITCH_SERVICE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_switch, switchPendingIntent)

            // 读缓存并填充数据
            runBlocking {
                try {
                    val dataStore = context.applicationContext.balanceCacheDataStore
                    val cache = BalanceCache(dataStore)
                    val selectedService = currentDisplayService(context)
                    views.setTextViewText(R.id.widget_switch, shortName(selectedService))
                    views.setTextViewText(R.id.widget_service_title, "${selectedService.displayName} · 额度")
                    views.setImageViewResource(R.id.widget_logo, widgetLogo(selectedService))
                    val cached = cache.get(selectedService)
                    if (cached != null) {
                        selectedHasCachedData = true
                        populateServiceRows(views, selectedService, cached.balance)

                        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        val timeText = "更新 " + sdf.format(Date(cached.fetchedAt))
                        views.setTextViewText(R.id.widget_updated, timeText)
                    } else {
                        setEmptyState(views)
                        views.setTextViewText(R.id.widget_switch, shortName(selectedService))
                        views.setTextViewText(R.id.widget_service_title, "${selectedService.displayName} · 额度")
                    }

                    // DeepSeek 余额
                    val dsCached = cache.get(ServiceType.DEEPSEEK)
                    if (dsCached != null && dsCached.balance.amount > 0) {
                        val dsBal = dsCached.balance
                        val dsText = dsBal.unit + String.format("%.2f", dsBal.amount)
                        views.setTextViewText(R.id.widget_ds_amount, dsText)
                    } else {
                        views.setTextViewText(R.id.widget_ds_amount, "—")
                    }
                } catch (_: Exception) {
                    setEmptyState(views)
                }
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        // 自动刷新：缓存为空 或 超过冷却时间 → 触发后台刷新
        if (!selectedHasCachedData || shouldAutoRefresh(context)) {
            markAutoRefreshTime(context)
            context.sendBroadcast(WidgetRefreshReceiver.createIntent(context))
        }
    }

    private fun populateServiceRows(
        views: RemoteViews,
        service: ServiceType,
        balance: com.rainy.token.domain.model.ServiceBalance
    ) {
        val extras = balance.extras
        when (service) {
            ServiceType.OPENCODE_GO -> {
                setRowLabel(views, "5h", "本周", "本月")
                populateRow(views, R.id.row1_pct, R.id.row1_bar, R.id.row1_reset,
                    pct = extras["rolling.pct"]?.toIntOrNull(),
                    resetSec = extras["rolling.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row2_pct, R.id.row2_bar, R.id.row2_reset,
                    pct = extras["weekly.pct"]?.toIntOrNull(),
                    resetSec = extras["weekly.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row3_pct, R.id.row3_bar, R.id.row3_reset,
                    pct = extras["monthly.pct"]?.toIntOrNull(),
                    resetSec = extras["monthly.resetInSec"]?.toLongOrNull())
            }
            ServiceType.COMMANDCODE_GO -> {
                fun calcPct(used: Double?, cap: Double?): Int? {
                    if (used == null || cap == null || cap <= 0) return null
                    return ((used / cap) * 100).toInt().coerceIn(0, 100)
                }
                setRowLabel(views, "5h", "本周", "本月")
                populateRow(views, R.id.row1_pct, R.id.row1_bar, R.id.row1_reset,
                    pct = calcPct(extras["fiveHour.used"]?.toDoubleOrNull(), extras["fiveHour.cap"]?.toDoubleOrNull()),
                    resetSec = extras["fiveHour.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row2_pct, R.id.row2_bar, R.id.row2_reset,
                    pct = calcPct(extras["weekly.used"]?.toDoubleOrNull(), extras["weekly.cap"]?.toDoubleOrNull()),
                    resetSec = extras["weekly.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row3_pct, R.id.row3_bar, R.id.row3_reset,
                    pct = calcPct(balance.monthlySpent, balance.totalQuota),
                    resetSec = extras["monthly.resetInSec"]?.toLongOrNull())
            }
            ServiceType.CODEX -> {
                val windows = (0 until 3).map { index ->
                    val label = normalizeWindowLabel(extras["window_$index.label"] ?: if (index == 0) "5h" else "Usage")
                    val remaining = extras["window_$index.remainingPct"]?.toIntOrNull()
                    val resetAt = extras["window_$index.resetAt"]?.toLongOrNull()?.takeIf { it > 0 }
                    Triple(label, remaining?.let { (100 - it).coerceIn(0, 100) }, resetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                }
                setRowLabel(views, windows.getOrNull(0)?.first ?: "5h", windows.getOrNull(1)?.first ?: "周", windows.getOrNull(2)?.first ?: "月")
                listOf(
                    Triple(R.id.row1_pct, R.id.row1_bar, R.id.row1_reset),
                    Triple(R.id.row2_pct, R.id.row2_bar, R.id.row2_reset),
                    Triple(R.id.row3_pct, R.id.row3_bar, R.id.row3_reset)
                ).forEachIndexed { index, ids ->
                    val window = windows.getOrNull(index)
                    populateRow(views, ids.first, ids.second, ids.third, pct = window?.second, resetSec = window?.third)
                }
            }
            ServiceType.DEEPSEEK -> setEmptyState(views)
        }
    }

    private fun setRowLabel(views: RemoteViews, first: String, second: String, third: String) {
        views.setTextViewText(R.id.row1_label, first)
        views.setTextViewText(R.id.row2_label, second)
        views.setTextViewText(R.id.row3_label, third)
    }

    private fun normalizeWindowLabel(label: String): String = when (label.lowercase()) {
        "weekly" -> "每周"
        else -> label
    }

    private fun populateRow(
        views: RemoteViews,
        pctViewId: Int,
        barViewId: Int,
        resetViewId: Int,
        pct: Int?,
        resetSec: Long?
    ) {
        if (pct != null) {
            views.setTextViewText(pctViewId, "${pct}%")
            views.setProgressBar(barViewId, 100, pct.coerceIn(0, 100), false)
            setProgressColor(views, barViewId, pct)
        } else {
            views.setTextViewText(pctViewId, "—")
            views.setProgressBar(barViewId, 100, 0, false)
        }
        if (resetSec != null && resetSec > 0) {
            views.setTextViewText(resetViewId, formatReset(resetSec))
        } else {
            views.setTextViewText(resetViewId, "")
        }
    }

    /** 根据用量百分比动态改进度条颜色 */
    private fun setProgressColor(views: RemoteViews, barViewId: Int, pct: Int) {
        val color = when {
            pct >= 80 -> 0xFFE91E63.toInt()   // 玫红
            pct >= 50 -> 0xFFFFA726.toInt()   // 暖橙
            else -> 0xFFFF85A2.toInt()         // 草莓粉
        }
        views.setColorStateList(barViewId, "setProgressTintList", ColorStateList.valueOf(color))
    }

    private fun setEmptyState(views: RemoteViews) {
        views.setTextViewText(R.id.widget_updated, "暂无数据")
        for (pctId in listOf(R.id.row1_pct, R.id.row2_pct, R.id.row3_pct)) {
            views.setTextViewText(pctId, "—")
        }
        for (barId in listOf(R.id.row1_bar, R.id.row2_bar, R.id.row3_bar)) {
            views.setProgressBar(barId, 100, 0, false)
        }
        for (resetId in listOf(R.id.row1_reset, R.id.row2_reset, R.id.row3_reset)) {
            views.setTextViewText(resetId, "")
        }
        views.setTextViewText(R.id.widget_ds_amount, "—")
    }

    companion object {

        /** 两次自动刷新的最小间隔（5分钟），防止频繁网络请求 */
        private const val AUTO_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
        private const val PREFS_NAME = "widget_auto_refresh"
        private const val KEY_LAST_AUTO_REFRESH = "last_auto_refresh"
        private const val KEY_DISPLAY_SERVICE = "display_service"
        private const val ACTION_SWITCH_SERVICE = "com.rainy.token.action.WIDGET_SWITCH_SERVICE"
        private val DISPLAY_SERVICES = listOf(ServiceType.OPENCODE_GO, ServiceType.COMMANDCODE_GO, ServiceType.CODEX)

        private fun autoRefreshPrefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun shouldAutoRefresh(context: Context): Boolean {
            val lastRefresh = autoRefreshPrefs(context).getLong(KEY_LAST_AUTO_REFRESH, 0L)
            return System.currentTimeMillis() - lastRefresh > AUTO_REFRESH_COOLDOWN_MS
        }

        private fun markAutoRefreshTime(context: Context) {
            autoRefreshPrefs(context).edit()
                .putLong(KEY_LAST_AUTO_REFRESH, System.currentTimeMillis())
                .apply()
        }

        fun currentDisplayService(context: Context): ServiceType {
            val key = autoRefreshPrefs(context).getString(KEY_DISPLAY_SERVICE, ServiceType.OPENCODE_GO.storageKey)
            return DISPLAY_SERVICES.firstOrNull { it.storageKey == key } ?: ServiceType.OPENCODE_GO
        }

        private fun switchDisplayService(context: Context) {
            val current = currentDisplayService(context)
            val next = DISPLAY_SERVICES[(DISPLAY_SERVICES.indexOf(current).coerceAtLeast(0) + 1) % DISPLAY_SERVICES.size]
            autoRefreshPrefs(context).edit().putString(KEY_DISPLAY_SERVICE, next.storageKey).apply()
        }

        private fun shortName(service: ServiceType): String = when (service) {
            ServiceType.OPENCODE_GO -> "OCGO"
            ServiceType.COMMANDCODE_GO -> "CCGO"
            ServiceType.CODEX -> "Codex"
            ServiceType.DEEPSEEK -> "DS"
        }

        private fun widgetLogo(service: ServiceType): Int = when (service) {
            ServiceType.OPENCODE_GO, ServiceType.COMMANDCODE_GO -> R.drawable.ic_opencode_go_logo
            ServiceType.CODEX -> R.drawable.ic_codex_logo
            ServiceType.DEEPSEEK -> R.drawable.ic_deepseek_logo
        }

        /**
         * APP 内刷新后主动更新 Widget。
         * 同时更新自动刷新时间戳，避免后续 onUpdate() 重复触发网络请求。
         */
        fun notifyDataChanged(context: Context) {
            markAutoRefreshTime(context) // 重置冷却计时器
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, OpenCodeGoWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, OpenCodeGoWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        fun showRefreshing(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, OpenCodeGoWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(component)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_opencode_go)
                views.setTextViewText(R.id.widget_updated, "刷新中…")
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        }

        private fun formatReset(sec: Long): String {
            if (sec <= 0) return ""
            val days = sec / 86400
            val hours = (sec % 86400) / 3600
            val minutes = (sec % 3600) / 60
            return when {
                days > 0 -> "${days}d${if (hours > 0) "${hours}h" else ""}"
                hours > 0 -> "${hours}h${if (minutes > 0) "${minutes}m" else ""}"
                minutes > 0 -> "${minutes}m"
                else -> "<1m"
            }
        }
    }
}