package com.rainy.token

import android.app.Application
import android.content.Context
import com.rainy.token.domain.usecase.UsageSyncCoordinator
import com.rainy.token.sync.UsageSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * RainyToken 入口 Application。
 *
 * @HiltAndroidApp 触发 Hilt 组件树的生成（SingletonComponent 等），
 * 整个 APP 的所有 @Inject 依赖都依赖它。
 */
@HiltAndroidApp
class RainyTokenApplication : Application() {

    @Inject
    lateinit var syncCoordinator: UsageSyncCoordinator

    override fun attachBaseContext(base: Context) {
        // 应用内语言偏好（跟随系统 / 中文 / English）在 Application 层生效，
        // 保证 appContext 与系统级回调（如小组件）之外的代码都使用所选语言。
        super.attachBaseContext(com.rainy.token.util.LocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // 后台用量同步兜底：按用户开关注册 / 取消 8 小时周期任务。
        // 服务端明细窗口约 24h，只要每天有一次成功同步就不会丢数据；
        // 真正的主力是前台进入页面时的自动同步，这里只负责「忘了打开 app」的情况。
        UsageSyncScheduler.apply(this, syncCoordinator.backgroundSyncEnabled)
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}