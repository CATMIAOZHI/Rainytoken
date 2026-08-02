package com.rainy.token

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp

/**
 * RainyToken 入口 Application。
 *
 * @HiltAndroidApp 触发 Hilt 组件树的生成（SingletonComponent 等），
 * 整个 APP 的所有 @Inject 依赖都依赖它。
 */
@HiltAndroidApp
class RainyTokenApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // 应用内语言偏好（跟随系统 / 中文 / English）在 Application 层生效，
        // 保证 appContext 与系统级回调（如小组件）之外的代码都使用所选语言。
        super.attachBaseContext(com.rainy.token.util.LocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}