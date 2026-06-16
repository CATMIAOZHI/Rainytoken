package com.rainy.token.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.rainy.token.ui.theme.CherryPinkDeep
import com.rainy.token.ui.theme.CherryPinkLight
import com.rainy.token.ui.theme.DarkBackground
import com.rainy.token.ui.theme.DarkSurface
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 雨晴风格全局背景。
 *
 * Light 模式：樱粉渐变（#FFF0F5 → #FFD1DC，从左上到右下）
 * Dark 模式：暖深渐变（#1F1419 → #2A1F25）
 *
 * 放在 Scaffold 容器层，所有页面共享同一层渐变。
 */
@Composable
fun RainyBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val brush = if (dark) {
        Brush.verticalGradient(
            colors = listOf(DarkBackground, DarkSurface)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(CherryPinkLight, CherryPinkDeep)
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush)
    ) {
        content()
    }
}