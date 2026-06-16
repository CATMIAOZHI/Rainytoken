package com.rainy.token.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 雨晴风格配色（RainyStyle Palette）。
 *
 * 视觉基调：元气樱粉 / 草莓粉主调，搭配纯白卡片与暖灰文本。
 * 状态色：草绿（正常）、暖橙（注意）、玫红（异常）。
 *
 * 背景使用樱粉渐变（#FFF0F5 → #FFD1DC），主元素采用草莓粉
 * （#FF85A2 / #FF6B8E），与白色形成层次。
 */

// ─── 品牌主色 ───
val CherryPinkLight = Color(0xFFFFF0F5)   // 樱粉浅（背景起始）
val CherryPinkDeep = Color(0xFFFFD1DC)    // 樱粉深（背景结束）
val StrawberryPink = Color(0xFFFF85A2)    // 草莓粉（主色 / 强调）
val StrawberryPinkDark = Color(0xFFFF6B8E)// 草莓粉深（按下态 / 主按钮）
val StrawberryPinkSoft = Color(0xFFFFB3C6)// 草莓粉柔（hover / 副按钮）

// ─── 中性色 ───
val PureWhite = Color(0xFFFFFFFF)
val SnowWhite = Color(0xFFFAF6F8)         // 卡片次级背景
val InkWarm = Color(0xFF3D2C35)           // 暖黑（主文本）
val InkMuted = Color(0xFF7A6B72)          // 暖灰（次要文本）
val InkOutline = Color(0xFFD9CFD3)        // 暖灰（分隔线 / 描边）

// ─── 状态色 ───
val StatusGreen = Color(0xFF66BB6A)       // 正常
val StatusOrange = Color(0xFFFFA726)      // 注意 / 需重登
val StatusRed = Color(0xFFE91E63)         // 错误 / 异常
val StatusBlue = Color(0xFF64B5F6)        // 信息（Stale 缓存）

// ─── 暗色模式 ───
val DarkBackground = Color(0xFF1F1419)    // 暖深背景
val DarkSurface = Color(0xFF2A1F25)       // 卡片
val DarkOnSurface = Color(0xFFEFE0E5)     // 卡片文本
val DarkPrimary = Color(0xFFFFB3C6)       // 暗色下主色更亮
