package com.rainy.token.ui.components

import android.app.Activity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 获取当前窗口大小等级，用于自适应布局（手机/平板/折叠屏）。
 *
 * 在各 Screen Composable 顶部调用：
 * ```
 * val windowSize = rememberWindowSizeClass()
 * when (windowSize.widthSizeClass) {
 *     WindowWidthSizeClass.Compact  → // 手机竖屏（宽度 < 600dp）
 *     WindowWidthSizeClass.Medium   → // 小平板/折叠屏展开（600-840dp）
 *     WindowWidthSizeClass.Expanded → // 大平板横屏（> 840dp）
 * }
 * ```
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val activity = LocalContext.current as Activity
    return calculateWindowSizeClass(activity)
}