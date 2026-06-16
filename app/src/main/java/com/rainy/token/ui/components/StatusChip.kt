package com.rainy.token.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rainy.token.ui.theme.StatusBlue
import com.rainy.token.ui.theme.StatusGreen
import com.rainy.token.ui.theme.StatusOrange
import com.rainy.token.ui.theme.StatusRed

/**
 * 状态徽章。圆角胶囊 + 左侧小圆点 + 文字。
 *
 * 用于：
 *  - 仪表盘卡片右下角"正常/注意/错误"状态指示
 *  - 设置页凭据配置状态
 *  - 详情页 Stale / Fresh 状态
 */
enum class StatusLevel { OK, WARNING, ERROR, INFO }

data class StatusStyle(
    val label: String,
    val level: StatusLevel
)

@Composable
fun StatusChip(
    style: StatusStyle,
    modifier: Modifier = Modifier
) {
    val (dotColor, textColor) = when (style.level) {
        StatusLevel.OK -> StatusGreen to Color(0xFF1B5E20)
        StatusLevel.WARNING -> StatusOrange to Color(0xFF8A4A00)
        StatusLevel.ERROR -> StatusRed to Color(0xFF8B0033)
        StatusLevel.INFO -> StatusBlue to Color(0xFF0D47A1)
    }
    val bgColor = dotColor.copy(alpha = 0.12f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}