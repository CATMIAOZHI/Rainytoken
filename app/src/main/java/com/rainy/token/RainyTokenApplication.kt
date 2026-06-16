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