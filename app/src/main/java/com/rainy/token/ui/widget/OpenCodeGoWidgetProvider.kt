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
 * OpenCode Go 桌面小组件。
 *
 * 显示 3 个用量窗口（5h / 本周 / 本月）的百分比 + 进度条 + 重置倒计时。
 * 数据来自 [BalanceCache]（DataStore），不触发网络请求。
 */
class OpenCodeGoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
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
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

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
                    val cached = cache.get(ServiceType.OPENCODE_GO)
                    if (cached != null) {
                        val bal = cached.balance
                        val extras = bal.extras

                        populateRow(views, R.id.row1_pct, R.id.row1_bar, R.id.row1_reset,
                            pct = extras["rolling.pct"]?.toIntOrNull(),
                            resetSec = extras["rolling.resetInSec"]?.toLongOrNull())
                        populateRow(views, R.id.row2_pct, R.id.row2_bar, R.id.row2_reset,
                            pct = extras["weekly.pct"]?.toIntOrNull(),
                            resetSec = extras["weekly.resetInSec"]?.toLongOrNull())
                        populateRow(views, R.id.row3_pct, R.id.row3_bar, R.id.row3_reset,
                            pct = extras["monthly.pct"]?.toIntOrNull(),
                            resetSec = extras["monthly.resetInSec"]?.toLongOrNull())

                        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        val timeText = "更新 " + sdf.format(Date(cached.fetchedAt))
                        views.setTextViewText(R.id.widget_updated, timeText)
} else {
                        setEmptyState(views)
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
        /**
         * APP 内刷新后主动更新 Widget。
         */
        fun notifyDataChanged(context: Context) {
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