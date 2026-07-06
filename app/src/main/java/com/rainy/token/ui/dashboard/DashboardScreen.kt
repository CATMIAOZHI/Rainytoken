package com.rainy.token.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.ServiceIcon
import com.rainy.token.ui.components.StatusChip
import com.rainy.token.ui.components.StatusLevel
import com.rainy.token.ui.components.StatusStyle
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import com.rainy.token.ui.widget.OpenCodeGoWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仪表盘主页（雨晴风格重做版）。
 *
 * 视觉：
 *  - 顶部 TopAppBar 透明 + 渐变背景
 *  - 下拉刷新（PullToRefresh）触发 DashboardViewModel.refresh()
 *  - 顶栏刷新按钮亦可触发
 *  - 卡片：白底圆角 + 左侧服务图标 + 中间余额大数字 + 右侧状态 chip
 *  - 卡片底部展示"更新于 X 分钟前"或错误信息
 *  - 主数字加粗超大，视觉锚点
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenService: (ServiceType) -> Unit,
    onOpenUsageDetail: () -> Unit,
    onOpenCcgoUsageDetail: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cardOrder = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        val saved = context.getSharedPreferences(DASHBOARD_ORDER_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(DASHBOARD_ORDER_KEY, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        cardOrder.clear()
        cardOrder.addAll(saved)
    }

    // 全局刷新触发器——每次 dashboard 刷新完成后 +1，UsageStatsCard 据此同步用量数据
    var usageSyncTrigger by remember { mutableIntStateOf(0) }
    var lastRefreshing by remember { mutableStateOf(uiState.refreshing) }
    LaunchedEffect(uiState.refreshing) {
        // 仅当 refreshing 从 true → false 时触发（即 refresh() 真正完成了）
        if (lastRefreshing && !uiState.refreshing) {
            usageSyncTrigger++
        }
        lastRefreshing = uiState.refreshing
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "RainyToken",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "AI 余额一览",
                            style = MaterialTheme.typography.bodySmall,
                            color = inkMuted()
                        )
                    }
                },
                actions = {
                    // 添加小组件：优先 requestPinAppWidget，不支持则打开系统小组件选择器
                    IconButton(onClick = {
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val component = ComponentName(context, OpenCodeGoWidgetProvider::class.java)
                        if (appWidgetManager.isRequestPinAppWidgetSupported) {
                            appWidgetManager.requestPinAppWidget(component, null, null)
                        } else {
                            // MIUI 等桌面回退：直接打开小组件选择器
                            try {
                                val pickerIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                                }
                                context.startActivity(pickerIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "请在桌面长按 → 小组件 → 查找雨晴Token", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加小组件到桌面",
                            tint = StrawberryPink
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !uiState.refreshing
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = StrawberryPink
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
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
        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.cards.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = StrawberryPink)
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // 容器自身宽度 > 600dp 时开双列（自适应父容器，而非全局窗口）
                    val wideEnough = maxWidth > 600.dp
                    val contentPadding = if (wideEnough) 16.dp else 16.dp
                    val scrollState = rememberScrollState()
                    var viewportHeightPx by remember { mutableIntStateOf(1) }
                    var viewportTopInWindow by remember { mutableFloatStateOf(0f) }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportHeightPx = it.height.coerceAtLeast(1) }
                            .onGloballyPositioned { viewportTopInWindow = it.positionInWindow().y }
                            .verticalScroll(scrollState)
                            .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val items = rememberDashboardItems(
                            cards = uiState.cards,
                            order = cardOrder,
                            onOpenUsageDetail = onOpenUsageDetail,
                            onOpenCcgoUsageDetail = onOpenCcgoUsageDetail,
                            onOpenService = onOpenService,
                            refreshTrigger = usageSyncTrigger
                        )
                        DraggableDashboardCards(
                            items = items,
                            wideEnough = wideEnough,
                            scrollState = scrollState,
                            viewportHeightPx = viewportHeightPx,
                            viewportTopInWindow = viewportTopInWindow,
                            onOrderChanged = { newOrder ->
                                cardOrder.clear()
                                cardOrder.addAll(newOrder)
                                context.getSharedPreferences(DASHBOARD_ORDER_PREFS, android.content.Context.MODE_PRIVATE)
                                    .edit()
                                    .putString(DASHBOARD_ORDER_KEY, newOrder.joinToString(","))
                                    .apply()
                            }
                        )
                        // 底部 footer：填空白 + 提供版本号
                        DashboardFooter()
                    }
                }
            }
        }
    }
}

private const val DASHBOARD_ORDER_PREFS = "dashboard_card_order"
private const val DASHBOARD_ORDER_KEY = "order"
private const val USAGE_OCGO_CARD_ID = "usage:opencode_go"
private const val USAGE_CCGO_CARD_ID = "usage:commandcode_go"
private const val DASHBOARD_CARD_SPACING_DP = 12

private data class DashboardHomeItem(
    val id: String,
    val content: @Composable () -> Unit
)

@Composable
private fun rememberDashboardItems(
    cards: List<DashboardCardUi>,
    order: List<String>,
    onOpenUsageDetail: () -> Unit,
    onOpenCcgoUsageDetail: () -> Unit,
    onOpenService: (ServiceType) -> Unit,
    refreshTrigger: Int
): List<DashboardHomeItem> {
    val defaultItems = buildList {
        add(DashboardHomeItem(USAGE_OCGO_CARD_ID) {
            UsageStatsCard(onOpenDetail = onOpenUsageDetail, refreshTrigger = refreshTrigger)
        })
        add(DashboardHomeItem(USAGE_CCGO_CARD_ID) {
            CommandCodeUsageStatsCard(onOpenDetail = onOpenCcgoUsageDetail, refreshTrigger = refreshTrigger)
        })
        cards.forEach { card ->
            add(DashboardHomeItem("service:${card.service.storageKey}") {
                DashboardCard(
                    card = card,
                    onClick = { onOpenService(card.service) },
                    onOpenUsageDetail = when (card.service) {
                        ServiceType.OPENCODE_GO -> onOpenUsageDetail
                        ServiceType.COMMANDCODE_GO -> onOpenCcgoUsageDetail
                        else -> null
                    }
                )
            })
        }
    }
    val itemById = defaultItems.associateBy { it.id }
    val ordered = order.mapNotNull { itemById[it] }
    return ordered + defaultItems.filterNot { item -> ordered.any { it.id == item.id } }
}

@Composable
private fun DraggableDashboardCards(
    items: List<DashboardHomeItem>,
    wideEnough: Boolean,
    scrollState: ScrollState,
    viewportHeightPx: Int,
    viewportTopInWindow: Float,
    onOrderChanged: (List<String>) -> Unit
) {
    val columns = if (wideEnough) 2 else 1
    val gestureKey = remember(items) { items.joinToString("|") { it.id } }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val displayOrder = remember { mutableStateListOf<String>() }
    LaunchedEffect(gestureKey) {
        if (draggingId == null) {
            displayOrder.clear()
            displayOrder.addAll(items.map { it.id })
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val spacingPx = with(density) { DASHBOARD_CARD_SPACING_DP.dp.toPx() }
    val edgeScrollZonePx = with(density) { 96.dp.toPx() }
    val maxAutoScrollPx = with(density) { 26.dp.toPx() }
    var visualDragOffsetX by remember { mutableFloatStateOf(0f) }
    var visualDragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragStartCenterXInWindow by remember { mutableFloatStateOf(-1f) }
    var dragStartCenterYInWindow by remember { mutableFloatStateOf(-1f) }
    var dragCenterYInViewport by remember { mutableFloatStateOf(-1f) }
    var cardTopInViewport by remember { mutableFloatStateOf(0f) }
    var cardWidthPx by remember { mutableIntStateOf(1) }
    var cardHeightPx by remember { mutableIntStateOf(1) }
    var dragFromIndex by remember { mutableIntStateOf(-1) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    val itemCenterById = remember { HashMap<String, androidx.compose.ui.geometry.Offset>() }

    fun autoScrollDelta(): Float {
        if (dragCenterYInViewport < 0f || scrollState.maxValue <= 0) return 0f
        val topZoneEnd = viewportTopInWindow + edgeScrollZonePx
        val bottomZoneStart = viewportTopInWindow + viewportHeightPx - edgeScrollZonePx
        return when {
            dragCenterYInViewport < topZoneEnd -> {
                -maxAutoScrollPx * ((topZoneEnd - dragCenterYInViewport) / edgeScrollZonePx).coerceIn(0.2f, 1f)
            }
            dragCenterYInViewport > bottomZoneStart -> {
                maxAutoScrollPx * ((dragCenterYInViewport - bottomZoneStart) / edgeScrollZonePx).coerceIn(0.2f, 1f)
            }
            else -> 0f
        }
    }

    fun updateDragTargetIndex() {
        val id = draggingId ?: return
        if (dragFromIndex < 0 || dragStartCenterXInWindow < 0f || dragStartCenterYInWindow < 0f) return

        val dragCenter = androidx.compose.ui.geometry.Offset(
            x = dragStartCenterXInWindow + visualDragOffsetX,
            y = dragStartCenterYInWindow + visualDragOffsetY
        )
        val targetId = displayOrder
            .asSequence()
            .filter { it != id }
            .minByOrNull { otherId ->
                val center = itemCenterById[otherId] ?: return@minByOrNull Float.MAX_VALUE
                val dx = center.x - dragCenter.x
                val dy = center.y - dragCenter.y
                dx * dx + dy * dy
            }
            ?: return
        val targetCenter = itemCenterById[targetId] ?: return
        val activationDistance = minOf(cardWidthPx, cardHeightPx) * 0.45f
        val dx = targetCenter.x - dragCenter.x
        val dy = targetCenter.y - dragCenter.y
        dragTargetIndex = if (dx * dx + dy * dy <= activationDistance * activationDistance) {
            displayOrder.indexOf(targetId).takeIf { it >= 0 } ?: dragFromIndex
        } else {
            dragFromIndex
        }
    }

    fun settleDraggedItem() {
        val id = draggingId ?: return
        if (dragFromIndex < 0 || dragTargetIndex < 0 || dragFromIndex == dragTargetIndex) return
        val currentIndex = displayOrder.indexOf(id)
        if (currentIndex < 0) return
        displayOrder.add(dragTargetIndex.coerceIn(0, displayOrder.lastIndex), displayOrder.removeAt(currentIndex))
    }

    fun displacementFor(index: Int): IntOffset {
        if (draggingId == null || dragFromIndex < 0 || dragTargetIndex < 0 || dragFromIndex == dragTargetIndex) {
            return IntOffset.Zero
        }
        val targetIndex = dragTargetIndex.coerceIn(0, displayOrder.lastIndex)
        val displacedIndex = when {
            dragFromIndex < targetIndex && index in (dragFromIndex + 1)..targetIndex -> index - 1
            dragFromIndex > targetIndex && index in targetIndex until dragFromIndex -> index + 1
            else -> index
        }
        if (displacedIndex == index) return IntOffset.Zero
        val cellWidth = cardWidthPx + spacingPx
        val cellHeight = cardHeightPx + spacingPx
        val fromColumn = index % columns
        val fromRow = index / columns
        val toColumn = displacedIndex % columns
        val toRow = displacedIndex / columns
        return IntOffset(
            x = ((toColumn - fromColumn) * cellWidth).roundToInt(),
            y = ((toRow - fromRow) * cellHeight).roundToInt()
        )
    }

    LaunchedEffect(draggingId, dragCenterYInViewport, viewportHeightPx, viewportTopInWindow) {
        while (draggingId != null) {
            val delta = autoScrollDelta()
            if (delta != 0f) {
                val before = scrollState.value
                scrollState.scrollBy(delta)
                val consumed = scrollState.value - before
                visualDragOffsetY += consumed
                dragStartCenterYInWindow -= consumed
                itemCenterById.keys.toList().forEach { id ->
                    itemCenterById[id] = itemCenterById[id]?.let { center ->
                        center.copy(y = center.y - consumed)
                    } ?: return@forEach
                }
                dragCenterYInViewport = dragStartCenterYInWindow + visualDragOffsetY
                updateDragTargetIndex()
            }
            delay(16L)
        }
    }

    val itemById = items.associateBy { it.id }
    val displayedItems = displayOrder.mapNotNull { itemById[it] }.ifEmpty { items }

    displayedItems.chunked(columns).forEachIndexed { rowIndex, rowItems ->
        val rowContainsDraggingItem = draggingId != null && rowItems.any { it.id == draggingId }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(if (rowContainsDraggingItem) 10f else 0f),
            horizontalArrangement = Arrangement.spacedBy(DASHBOARD_CARD_SPACING_DP.dp)
        ) {
            rowItems.forEachIndexed { columnIndex, item ->
                key(item.id) {
                val itemIndex = rowIndex * columns + columnIndex
                val isDragging = draggingId == item.id
                val itemDisplacement = if (isDragging) IntOffset.Zero else displacementFor(itemIndex)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged {
                            cardWidthPx = it.width.coerceAtLeast(1)
                            cardHeightPx = it.height.coerceAtLeast(1)
                        }
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInWindow()
                            val size = coordinates.size
                            if (draggingId == null) {
                                itemCenterById[item.id] = androidx.compose.ui.geometry.Offset(
                                    x = position.x + size.width / 2f,
                                    y = position.y + size.height / 2f
                                )
                            }
                            if (isDragging) {
                                cardTopInViewport = position.y
                            }
                        }
                        .offset {
                            if (isDragging) {
                                IntOffset(visualDragOffsetX.roundToInt(), visualDragOffsetY.roundToInt())
                            } else {
                                itemDisplacement
                            }
                        }
                        .zIndex(if (isDragging) 20f else 0f)
                        .alpha(if (isDragging) 0.92f else 1f)
                        .pointerInput(gestureKey) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = item.id
                                    visualDragOffsetX = 0f
                                    visualDragOffsetY = 0f
                                    dragFromIndex = displayOrder.indexOf(item.id)
                                    dragTargetIndex = dragFromIndex
                                    val center = itemCenterById[item.id]
                                    dragStartCenterXInWindow = center?.x ?: -1f
                                    dragStartCenterYInWindow = center?.y ?: -1f
                                    dragCenterYInViewport = (center?.y ?: cardTopInViewport + cardHeightPx / 2f)
                                },
                                onDragEnd = {
                                    settleDraggedItem()
                                    onOrderChanged(displayOrder.toList())
                                    draggingId = null
                                    visualDragOffsetX = 0f
                                    visualDragOffsetY = 0f
                                    dragStartCenterXInWindow = -1f
                                    dragStartCenterYInWindow = -1f
                                    dragFromIndex = -1
                                    dragTargetIndex = -1
                                    dragCenterYInViewport = -1f
                                },
                                onDragCancel = {
                                    displayOrder.clear()
                                    displayOrder.addAll(items.map { it.id })
                                    draggingId = null
                                    visualDragOffsetX = 0f
                                    visualDragOffsetY = 0f
                                    dragStartCenterXInWindow = -1f
                                    dragStartCenterYInWindow = -1f
                                    dragFromIndex = -1
                                    dragTargetIndex = -1
                                    dragCenterYInViewport = -1f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    visualDragOffsetX += dragAmount.x
                                    visualDragOffsetY += dragAmount.y
                                    dragCenterYInViewport = dragStartCenterYInWindow + visualDragOffsetY
                                    updateDragTargetIndex()
                                }
                            )
                        }
                ) {
                    item.content()
                }
                }
            }
            repeat(columns - rowItems.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DashboardCard(card: DashboardCardUi, onClick: () -> Unit, onOpenUsageDetail: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ─── 顶部：图标 + 名称 + 状态 chip ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceIcon(service = card.service, size = 44)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.service.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = secondaryLine(card),
                        style = MaterialTheme.typography.bodySmall,
                        color = inkMuted()
                    )
                }
                StatusChip(style = card.statusBadgeStyle())
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ─── 主体：服务特定的主信息 ───
            BalanceMainArea(card)

            // 用量服务卡片底部：用量详情入口（仅当有凭证时显示）
            if (onOpenUsageDetail != null && card.credentialState != com.rainy.token.domain.model.CredentialStatus.State.NOT_CONFIGURED) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenUsageDetail) {
                    Text("查看用量详情", color = StrawberryPink)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = StrawberryPink,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }

            // ─── 底部：更新时间 / 错误信息 ───
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = footerText(card),
                style = MaterialTheme.typography.bodySmall,
                color = if (card.lastFetchError != null)
                    MaterialTheme.colorScheme.error
                else
                    inkMuted()
            )
        }
    }
}

/**
 * 仪表盘底部 footer：填空白 + 显示版本号。
 * 容器透明无边框，让卡片列表与背景融合自然。
 */
@Composable
private fun DashboardFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RainyToken · v1.0",
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "本地加密存储，不上传任何数据",
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted()
        )
    }
}