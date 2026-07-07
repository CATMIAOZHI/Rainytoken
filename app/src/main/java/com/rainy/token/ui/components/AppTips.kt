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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink

/**
 * APP 使用小技巧列表。
 * Dashboard 卡片上方随机展示一条，设置页可查看全部。
 */
object AppTips {

    data class Tip(
        val title: String,
        val hint: String,
        val detail: String
    )

    val tips: List<Tip> = listOf(
        Tip("长按卡片拖拽排序", "长按首页卡片可以拖拽排序", "在首页长按任意服务卡片即可拖拽到想要的位置，排序会自动保存。"),
        Tip("小组件点左上角进 APP", "小组件左上角标识可打开 APP", "桌面小组件左上角 RainyToken › 标识可以点击打开应用，其它区域点击切换服务。"),
        Tip("图表点击查看详情", "点击图表柱子可查看时段详情", "在用量图表上点击或滑动手指，可查看对应时段的详细数值。"),
        Tip("图表标题可点击", "点击图表标题弹出按模型明细", "图表卡片标题（如「消耗金额」）点击后会弹出按模型拆分的明细。"),
        Tip("切换图表时区", "用量详情页可切换 UTC+0 / UTC+8", "用量详情页顶部可切换 UTC+0 / UTC+8，图表标签会跟随切换。"),
        Tip("CCGO 清除并重新同步", "CCGO 详情页可清除缓存重新同步", "CCGO 用量详情页右上角「清除」可删除本地缓存并重新拉取全部数据。"),
        Tip("查看用量详情", "服务卡片底部可进入用量图表", "有凭据的服务卡片底部会出现「查看用量详情」入口，点击进入图表统计。"),
        Tip("原始数据浏览", "用量详情页右上角可查看原始记录", "用量详情页右上角「详细数据」可查看每条原始调用记录，支持分页跳转。"),
        Tip("下拉刷新联动用量", "下拉刷新会同步余额和用量", "首页下拉刷新不仅刷新余额，还会自动同步 OCGO / CCGO 用量记录。"),
        Tip("凭据获取教程", "凭据页底部有获取教程", "凭据编辑页底部的「如何获取」按钮有针对每个服务的详细操作步骤。"),
        Tip("剪贴板自动导入", "Codex 支持从剪贴板一键导入", "Codex 凭据页支持「从剪贴板自动导入」，粘贴 auth.json 后一键填入。"),
        Tip("详细数据页码跳转", "原始数据页可输入页码直跳", "原始数据页底部可输入页码直接跳转，数据量大时不必逐页翻。"),
        Tip("小组件自动刷新", "划到小组件会自动刷新余额", "桌面小组件划到负一屏时会自动刷新余额，也可手动点 ↻ 按钮刷新。"),
    )

    /** 首页轮换用的一句话文案。 */
    val shortHints: List<String> = tips.map { it.hint }

    /** 随机取一条短文案。 */
    fun randomHint(): String = shortHints.random()
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
                        "使用小技巧",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "返回",
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
                                text = tip.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = tip.detail,
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