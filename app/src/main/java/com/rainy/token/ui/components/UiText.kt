package com.rainy.token.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 可本地化的文本抽象。
 *
 * ViewModel / Repository 层不直接持有本地化字符串，而是返回 [UiText]：
 * - [Resource]：引用字符串资源（可带格式化参数），由 UI 层按当前语言环境解析
 * - [Dynamic]：服务端返回的动态文本（未知内容，无法预翻译），原样透传
 *
 * 调试日志保持使用原始 [Throwable.message]（Repository 中文消息仅用于排查，不用于 UI 展示）。
 */
sealed interface UiText {

    data class Resource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText

    data class Dynamic(val value: String) : UiText
}

/** 在非 Composable 上下文（协程/回调/事件）中把 [UiText] 解析为字符串（嵌套 [UiText] 参数会递归扁平化）。 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> {
        val flatArgs = args.map { arg -> if (arg is UiText) arg.resolve(context) else arg }
        context.getString(resId, *flatArgs.toTypedArray())
    }
    is UiText.Dynamic -> value
}

/** 在 Composable 上下文中把 [UiText] 解析为当前语言环境的字符串（嵌套 [UiText] 参数会递归扁平化）。 */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> {
        val flatArgs = args.map { arg -> if (arg is UiText) arg.asString() else arg }
        stringResource(resId, *flatArgs.toTypedArray())
    }
    is UiText.Dynamic -> value
}
