package com.rainy.token.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainy.token.R
import com.rainy.token.domain.service.ServiceType

/**
 * 服务图标。
 *
 * - DeepSeek：暂时还是 emoji 占位（无官方资源）
 * - OpenCode Go：使用从 https://opencode.ai/zh/go hero 区抓的官方 SVG logo
 *   （见 res/drawable/ic_opencode_go_logo.xml）
 *
 * 真实 logo 已经在外面留有白边衬底，圆形背景就省略了——直接展示 logo 本体。
 * 后续要补 DeepSeek / 其它服务：在 res/drawable 加 ic_<service>_logo.xml，
 * 然后在此 switch 加分支。
 */
@Composable
fun ServiceIcon(
    service: ServiceType,
    modifier: Modifier = Modifier,
    size: Int = 44
) {
    when (service) {
        ServiceType.OPENCODE_GO -> {
            // 真实 logo：原配色（#211E1E + #CFCECD），不需要圆形背景
            Box(
                modifier = modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSystemInDarkTheme()) Color(0xFF352329) else Color.White),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_opencode_go_logo),
                    contentDescription = "OpenCode Go",
                    modifier = Modifier.size((size * 0.85).dp, ((size * 0.85f * 30f / 54f)).dp)
                )
            }
        }
        ServiceType.COMMANDCODE_GO -> {
            // CommandCode Go：暂无官方 logo，用 ⚡ emoji 占位
            Box(
                modifier = modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2D3748)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = "⚡",
                    fontSize = (size * 0.5).sp
                )
            }
        }
        ServiceType.DEEPSEEK -> {
            // DeepSeek 官方无开放 logo 资源 —— 用蓝鲸 emoji 占位
            Box(
                modifier = modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF4A6CF7)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = "🐋",
                    fontSize = (size * 0.5).sp
                )
            }
        }
        ServiceType.CODEX -> {
            // ChatGPT 官方 favicon logo
            Box(
                modifier = modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSystemInDarkTheme()) Color(0xFF2D3748) else Color(0xFF10A37F)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_codex_logo),
                    contentDescription = "Codex / ChatGPT",
                    modifier = Modifier
                        .size((size * 0.6).dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        if (isSystemInDarkTheme()) Color.White else Color.White
                    )
                )
            }
        }
    }
}