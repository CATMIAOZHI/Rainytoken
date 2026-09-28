package com.rainy.token.data.local

import android.content.Context
import com.rainy.token.domain.service.ServiceType

/**
 * 服务显隐偏好：记录用户"隐藏不使用的服务商"的选择。
 *
 * 隐藏仅影响展示（仪表盘卡片、桌面小组件轮播与 DeepSeek 行），
 * 凭据、余额缓存与详情页均不动；取消隐藏后立即恢复显示。
 *
 * 用 SharedPreferences 而非 DataStore：桌面小组件的 RemoteViews 渲染
 * 路径需要同步读取（与 widget_auto_refresh 同模式），避免 runBlocking 阻塞。
 */
object ServiceVisibilityStore {

    private const val PREFS_NAME = "service_visibility"
    private const val KEY_HIDDEN = "hidden"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已隐藏服务的 storageKey 集合。返回副本，调用方可安全持有。 */
    fun hiddenKeys(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN, emptySet())?.toSet().orEmpty()

    /** 已隐藏服务集合（非法/历史遗留 key 自动忽略）。 */
    fun hiddenServices(context: Context): Set<ServiceType> =
        hiddenKeys(context).mapNotNull { ServiceType.fromStorageKey(it) }.toSet()

    fun isHidden(context: Context, type: ServiceType): Boolean =
        type.storageKey in hiddenKeys(context)

    /** 写入显隐状态。 */
    fun setHidden(context: Context, type: ServiceType, hidden: Boolean) {
        val next = hiddenKeys(context).toMutableSet()
        if (hidden) {
            next.add(type.storageKey)
        } else {
            next.remove(type.storageKey)
        }
        prefs(context).edit().putStringSet(KEY_HIDDEN, next).apply()
    }
}
