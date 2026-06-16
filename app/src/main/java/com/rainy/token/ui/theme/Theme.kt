package com.rainy.token.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 雨晴风格主题。
 *
 * - **不使用** dynamicColor（系统主题色），固定使用雨晴樱粉品牌色。
 *   理由：dynamicColor 会让 APP 视觉随系统变化，破坏品牌一致性；
 *   玩家一眼看到 APP icon + 粉色调就知道是 RainyToken 系列。
 * - 暗色模式：暖深背景 + 亮草莓粉强调，跟 Light 模式同色系不同明度。
 */
private val LightColors = lightColorScheme(
    primary = StrawberryPink,
    onPrimary = PureWhite,
    primaryContainer = StrawberryPinkSoft,
    onPrimaryContainer = InkWarm,
    secondary = StrawberryPinkDark,
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFFFFE4EC),
    onSecondaryContainer = InkWarm,
    tertiary = StatusGreen,
    onTertiary = PureWhite,
    background = CherryPinkLight,
    onBackground = InkWarm,
    surface = PureWhite,
    onSurface = InkWarm,
    surfaceVariant = SnowWhite,
    onSurfaceVariant = InkMuted,
    outline = InkOutline,
    outlineVariant = Color(0xFFEFE0E5),
    error = StatusRed,
    onError = PureWhite
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,
    primaryContainer = StrawberryPinkDark,
    onPrimaryContainer = PureWhite,
    secondary = StrawberryPink,
    onSecondary = DarkBackground,
    secondaryContainer = Color(0xFF4A2E3A),
    onSecondaryContainer = DarkOnSurface,
    tertiary = StatusGreen,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF352329),
    onSurfaceVariant = Color(0xFFC9B8BE),
    outline = Color(0xFF4D3A42),
    outlineVariant = Color(0xFF3A2A30),
    error = Color(0xFFFF6B8E),
    onError = PureWhite
)

@Composable
fun RainyTokenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RainyTypography,
        content = content
    )
}