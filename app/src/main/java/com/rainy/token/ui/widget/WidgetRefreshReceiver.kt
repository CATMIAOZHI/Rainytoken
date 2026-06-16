package com.rainy.token.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 接收 Widget 刷新按钮广播，后台静默刷新 DeepSeek + OpenCode Go。
 * 不依赖 @AndroidEntryPoint，通过 EntryPoints 获取 Hilt 依赖。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext, WidgetRefreshEntryPoint::class.java
        )
        val useCase = entryPoint.refreshBalanceUseCase()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val goResult = useCase(ServiceType.OPENCODE_GO)
                val dsResult = useCase(ServiceType.DEEPSEEK)
                if (goResult.isSuccess || dsResult.isSuccess) {
                    OpenCodeGoWidgetProvider.notifyDataChanged(appContext)
                }
            } catch (_: Exception) {
                // 静默，Widget 保留旧数据
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.rainy.token.action.WIDGET_REFRESH"

        fun createIntent(context: Context): Intent =
            Intent(context, WidgetRefreshReceiver::class.java).apply { action = ACTION }
    }
}