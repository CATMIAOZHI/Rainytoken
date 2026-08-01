package com.rainy.token.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.RemoteViews
import com.rainy.token.MainActivity
import com.rainy.token.R
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.balanceCacheDataStore
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.normalizeWindowLabel
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

            // 署名动态设置：与行标签同源（跟随应用内语言），
            // 避免 XML 静态文本由宿主进程按系统语言渲染造成中英混搭
            views.setTextViewText(R.id.widget_brand, context.getString(R.string.widget_brand))

            // 点击左上角品牌 → 打开 APP；点击其它内容区域 → 切换服务；刷新按钮单独刷新。
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_wordmark, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_open_hint, openAppPendingIntent)

            val switchPendingIntent = PendingIntent.getBroadcast(
                context, 2, Intent(context, OpenCodeGoWidgetProvider::class.java).apply { action = ACTION_SWITCH_SERVICE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_content, switchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_switch, switchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_service_title, switchPendingIntent)

            // 刷新按钮 → 后台广播刷新
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 1, WidgetRefreshReceiver.createIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)

            // 读缓存并填充数据
            runBlocking {
                try {
                    val dataStore = context.applicationContext.balanceCacheDataStore
                    val cache = BalanceCache(dataStore)
                    val selectedService = currentDisplayService(context)
                    views.setTextViewText(R.id.widget_switch, shortName(selectedService))
                    views.setTextViewText(
                        R.id.widget_service_title,
                        context.getString(R.string.widget_service_quota, selectedService.displayName)
                    )
                    views.setImageViewResource(R.id.widget_logo, widgetLogo(selectedService))
                    // Ollama logo 是正方形，XML 默认 22x12 是给宽扁 logo 的
                    if (selectedService == ServiceType.OLLAMA) {
                        views.setViewLayoutWidth(R.id.widget_logo, 14f, TypedValue.COMPLEX_UNIT_DIP)
                        views.setViewLayoutHeight(R.id.widget_logo, 14f, TypedValue.COMPLEX_UNIT_DIP)
                    } else {
                        views.setViewLayoutWidth(R.id.widget_logo, 22f, TypedValue.COMPLEX_UNIT_DIP)
                        views.setViewLayoutHeight(R.id.widget_logo, 12f, TypedValue.COMPLEX_UNIT_DIP)
                    }
                    val cached = cache.get(selectedService)
                    if (cached != null) {
                        selectedHasCachedData = true
                        populateServiceRows(views, context, selectedService, cached.balance)

                        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        val timeText = sdf.format(Date(cached.fetchedAt))
                        views.setTextViewText(
                            R.id.widget_updated,
                            context.getString(R.string.widget_updated_at, timeText)
                        )
                    } else {
                        setEmptyState(views, context)
                        views.setTextViewText(R.id.widget_switch, shortName(selectedService))
                        views.setTextViewText(
                            R.id.widget_service_title,
                            context.getString(R.string.widget_service_quota, selectedService.displayName)
                        )
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
                    setEmptyState(views, context)
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
        context: Context,
        service: ServiceType,
        balance: com.rainy.token.domain.model.ServiceBalance
    ) {
        val extras = balance.extras
        when (service) {
            ServiceType.OPENCODE_GO -> {
                setRowLabel(views, context.getString(R.string.window_5h_short), context.getString(R.string.window_weekly), context.getString(R.string.window_monthly))
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
                setRowLabel(views, context.getString(R.string.window_5h_short), context.getString(R.string.window_weekly), context.getString(R.string.window_monthly))
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
                val windowCount = extras.keys
                    .mapNotNull { key -> key.removePrefix("window_").substringBefore('.').toIntOrNull() }
                    .distinct().maxOrNull()?.plus(1) ?: 0
                val weeklyLabel = context.getString(R.string.window_every_week)
                val monthlyLabel = context.getString(R.string.window_every_month)
                val windows = (0 until windowCount).map { index ->
                    val label = normalizeWindowLabel(
                        extras["window_$index.label"] ?: "Usage",
                        weeklyLabel = weeklyLabel,
                        monthlyLabel = monthlyLabel
                    )
                    val remaining = extras["window_$index.remainingPct"]?.toIntOrNull()
                    val resetAt = extras["window_$index.resetAt"]?.toLongOrNull()?.takeIf { it > 0 }
                    Triple(label, remaining?.let { (100 - it).coerceIn(0, 100) }, resetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                }
                // 判断是否有 5h 窗口
                val has5h = windows.any { it.first.contains("5") }
                // 始终保留 5h 槽位在第一行
                val row1 = if (!has5h) {
                    Triple("5h", null, null)
                } else {
                    windows.firstOrNull { it.first.contains("5") } ?: windows.getOrNull(0) ?: Triple("5h", null, null)
                }
                val otherWindows = windows.filter { it != row1 }
                val row2 = otherWindows.getOrNull(0) ?: Triple(weeklyLabel, null, null)
                val row3 = otherWindows.getOrNull(1) ?: Triple(monthlyLabel, null, null)
                setRowLabel(views, row1.first, row2.first, row3.first)
                listOf(
                    Triple(R.id.row1_pct, R.id.row1_bar, R.id.row1_reset),
                    Triple(R.id.row2_pct, R.id.row2_bar, R.id.row2_reset),
                    Triple(R.id.row3_pct, R.id.row3_bar, R.id.row3_reset)
                ).forEachIndexed { index, ids ->
                    val window = when (index) { 0 -> row1; 1 -> row2; else -> row3 }
                    populateRow(views, ids.first, ids.second, ids.third, pct = window.second, resetSec = window.third)
                }
            }
            ServiceType.DEEPSEEK -> setEmptyState(views, context)
            ServiceType.OLLAMA -> {
                setRowLabel(views, context.getString(R.string.window_5h_short), context.getString(R.string.window_every_week), "")
                populateRow(views, R.id.row1_pct, R.id.row1_bar, R.id.row1_reset,
                    pct = extras["session.pct"]?.toFloatOrNull()?.toInt(),
                    resetSec = extras["session.resetAt"]?.toLongOrNull()?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                populateRow(views, R.id.row2_pct, R.id.row2_bar, R.id.row2_reset,
                    pct = extras["weekly.pct"]?.toFloatOrNull()?.toInt(),
                    resetSec = extras["weekly.resetAt"]?.toLongOrNull()?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                // 第三行清空（Ollama 没有月度窗口）
                views.setTextViewText(R.id.row3_label, "")
                views.setTextViewText(R.id.row3_pct, "")
                views.setProgressBar(R.id.row3_bar, 100, 0, false)
                views.setTextViewText(R.id.row3_reset, "")
                // Plan 信息合并到标题行
                val plan = extras["plan"] ?: ""
                val titleText = if (plan.isNotEmpty()) {
                    context.getString(R.string.widget_quota_with_plan, service.displayName, plan)
                } else {
                    context.getString(R.string.widget_service_quota, service.displayName)
                }
                views.setTextViewText(R.id.widget_service_title, titleText)
            }
        }
    }

    private fun setRowLabel(views: RemoteViews, first: String, second: String, third: String) {
        views.setTextViewText(R.id.row1_label, first)
        views.setTextViewText(R.id.row2_label, second)
        views.setTextViewText(R.id.row3_label, third)
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

    private fun setEmptyState(views: RemoteViews, context: Context) {
        views.setTextViewText(R.id.widget_updated, context.getString(R.string.widget_no_data))
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
        private val DISPLAY_SERVICES = listOf(ServiceType.OPENCODE_GO, ServiceType.COMMANDCODE_GO, ServiceType.CODEX, ServiceType.OLLAMA)

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
            ServiceType.OLLAMA -> "Ollama"
        }

        private fun widgetLogo(service: ServiceType): Int = when (service) {
            ServiceType.OPENCODE_GO, ServiceType.COMMANDCODE_GO -> R.drawable.ic_opencode_go_logo_widget // PNG for RemoteViews compatibility
            ServiceType.CODEX -> R.drawable.ic_codex_logo_widget // PNG for RemoteViews compatibility
            ServiceType.DEEPSEEK -> R.drawable.ic_deepseek_logo
            ServiceType.OLLAMA -> R.drawable.ic_ollama_logo_widget
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
                views.setTextViewText(R.id.widget_updated, context.getString(R.string.widget_refreshing))
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