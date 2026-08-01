package com.rainy.token.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.rainy.token.R
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink

/**
 * APP 使用小技巧列表。
 * Dashboard 卡片上方随机展示一条，设置页可查看全部。
 *
 * 文案全部改为字符串资源 ID（@StringRes），由 UI 层按当前语言环境解析，
 * 保证英文系统下自动显示英文翻译。
 */
object AppTips {

    data class Tip(
        @StringRes val titleRes: Int,
        @StringRes val hintRes: Int,
        @StringRes val detailRes: Int
    )

    val tips: List<Tip> = listOf(
        Tip(R.string.tip_1_title, R.string.tip_1_hint, R.string.tip_1_detail),
        Tip(R.string.tip_2_title, R.string.tip_2_hint, R.string.tip_2_detail),
        Tip(R.string.tip_3_title, R.string.tip_3_hint, R.string.tip_3_detail),
        Tip(R.string.tip_4_title, R.string.tip_4_hint, R.string.tip_4_detail),
        Tip(R.string.tip_5_title, R.string.tip_5_hint, R.string.tip_5_detail),
        Tip(R.string.tip_6_title, R.string.tip_6_hint, R.string.tip_6_detail),
        Tip(R.string.tip_7_title, R.string.tip_7_hint, R.string.tip_7_detail),
        Tip(R.string.tip_8_title, R.string.tip_8_hint, R.string.tip_8_detail),
        Tip(R.string.tip_9_title, R.string.tip_9_hint, R.string.tip_9_detail),
        Tip(R.string.tip_10_title, R.string.tip_10_hint, R.string.tip_10_detail),
        Tip(R.string.tip_11_title, R.string.tip_11_hint, R.string.tip_11_detail),
        Tip(R.string.tip_12_title, R.string.tip_12_hint, R.string.tip_12_detail),
        Tip(R.string.tip_13_title, R.string.tip_13_hint, R.string.tip_13_detail),
    )

    /** 首页轮换用的一句话文案（资源 ID 形式）。 */
    val shortHintRes: List<Int> = tips.map { it.hintRes }

    /** 随机取一条短文案的资源 ID。 */
    fun randomHintRes(): Int = shortHintRes.random()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_tips),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = StrawberryPink
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(AppTips.tips) { idx, tip ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${idx + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StrawberryPink
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(tip.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(tip.detailRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkMuted
                        )
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}