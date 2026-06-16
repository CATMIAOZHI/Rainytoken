package com.rainy.token

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rainy.token.ui.RainyTokenNavHost
import com.rainy.token.ui.components.RainyBackground
import com.rainy.token.ui.theme.RainyTokenTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * APP 入口。
 *
 * 布局策略：
 *  - 外层只套 RainyTokenTheme（统一品牌色 + 字体）
 *  - 再套 RainyBackground（樱粉渐变背景，全局共享）
 *  - NavHost 在背景之上，每个页面自己用 Scaffold 处理 TopAppBar 和 padding
 *  - **不**在外层再套 Scaffold —— 避免双 Scaffold 嵌套的 padding 计算混乱
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RainyTokenTheme {
                RainyBackground {
                    RainyTokenNavHost()
                }
            }
        }
    }
}