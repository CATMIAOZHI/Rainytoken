package com.rainy.token.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言偏好管理（自建轻量方案，不引入 appcompat 依赖）。
 *
 * 语言值约定：
 * - `null`（未设置）= 跟随系统
 * - `"zh"` = 简体中文（匹配 values-b+zh+Hans，BCP-47 写法，覆盖中国大陆等简体地区）
 * - `"zh-Hant"` = 繁体中文（匹配 values-b+zh+Hant，BCP-47 写法，覆盖台/港/澳所有繁体地区）
 * - `"en"` = English（匹配默认 values/ 英文资源）
 *
 * 注：AGP 9 资源合并器不接受 values-zh-Hans/values-zh-Hant 目录名，故用等价的
 * BCP-47 写法 values-b+zh+Hans / values-b+zh+Hant；编译产物同为
 * language=zh + script=Hans/Hant 配置。
 *
 * 在 [Application] 与 [MainActivity] 的 attachBaseContext 中调用 [wrapContext]
 * 应用偏好；切换语言后由设置页保存偏好并 recreate Activity 触发重新解析。
 */
object LocaleManager {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LOCALE = "locale"

    /** 当前保存的语言代码；null = 跟随系统 */
    fun getLocaleCode(context: Context): String? {
        val code = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOCALE, null) ?: return null
        return code.takeIf { it == "zh" || it == "zh-Hant" || it == "en" }
    }

    /** 保存语言偏好；传 null 表示跟随系统 */
    fun saveLocale(context: Context, code: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, code)
            .apply()
    }

    /** 把 base context 包装上应用语言配置；未设置偏好时原样返回 */
    fun wrapContext(base: Context): Context {
        val code = getLocaleCode(base) ?: return base
        val locale = when (code) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "zh-Hant" -> Locale.forLanguageTag("zh-Hant")
            else -> Locale.ENGLISH
        }
        val config = Configuration(base.resources.configuration)
        config.setLocales(android.os.LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
