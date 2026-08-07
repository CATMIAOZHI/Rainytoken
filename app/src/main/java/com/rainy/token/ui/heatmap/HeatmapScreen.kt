package com.rainy.token.ui.heatmap

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.ui.theme.inkMuted
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Token 活动热力图页面。
 *
 * Scaffold + TopAppBar 布局，包含视图切换器（每日/每周/累计）和 HeatmapCanvas。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(
    onBack: () -> Unit,
    viewModel: HeatmapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 应用内语言（LocaleManager 只覆写配置、不改变 Locale.getDefault()，故从配置取）
    val activeLocale = LocalConfiguration.current.locales[0]

    // ── 浮层状态 ──
    var selectedDay: HeatmapDayData? by remember { mutableStateOf(null) }
    var selectedWeek: HeatmapWeekData? by remember { mutableStateOf(null) }
    // 浮层锚点：被点击格子中心在窗口中的位置
    var popupAnchor by remember { mutableStateOf<IntOffset?>(null) }
    // 浮层自身尺寸（用于锚点偏移计算）
    var popupSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // ── i18n 字符串 ──
    val noDataText = stringResource(R.string.heatmap_no_data)
    val lessText = stringResource(R.string.heatmap_legend_less)
    val moreText = stringResource(R.string.heatmap_legend_more)

    // ── 年份选择器状态 ──
    var yearMenuExpanded by remember { mutableStateOf(false) }
    val selectYearDesc = stringResource(R.string.heatmap_select_year)

    // ── 个人资料状态（昵称/邮箱/头像路径，SharedPreferences 持久化）──
    val context = LocalContext.current
    val profilePrefs = remember { context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE) }
    var nickname by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var avatarPath by remember { mutableStateOf<String?>(null) }
    // 换头像后路径不变（固定文件名），用版本号强制触发头像重载
    var avatarVersion by remember { mutableStateOf(0) }
    var showProfileEdit by remember { mutableStateOf(false) }
    var avatarMenuVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        nickname = profilePrefs.getString(KEY_NICKNAME, "").orEmpty()
        email = profilePrefs.getString(KEY_EMAIL, "").orEmpty()
        avatarPath = profilePrefs.getString(KEY_AVATAR_PATH, null)
    }
    // 系统相册选图 → 复制到应用私有目录（content URI 授权是临时的，必须保存持久化副本）
    // 复制在 IO 线程执行（相册原图可达数 MB，避免阻塞主线程）；
    // 先写临时文件再 rename 覆盖，失败时不破坏已有头像
    val scope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = File(context.filesDir, AVATAR_FILE_NAME)
                    val tmp = File(context.filesDir, AVATAR_FILE_NAME + ".tmp")
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("cannot open picked image")
                    input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        error("cannot persist avatar")
                    }
                    target.absolutePath
                }
            }.onSuccess { path ->
                avatarPath = path
                avatarVersion++ // 路径相同（固定文件名），必须递增版本号触发头像重载
                profilePrefs.edit().putString(KEY_AVATAR_PATH, path).apply()
            }.onFailure { tmp ->
                File(context.filesDir, AVATAR_FILE_NAME + ".tmp").delete()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.heatmap_title))
                        if (state.availableYears.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            // 年份下拉选择器
                            Text(
                                text = "${state.selectedYear} ▾",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { yearMenuExpanded = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                    .semantics { contentDescription = "$selectYearDesc: ${state.selectedYear}" }
                            )
                            DropdownMenu(
                                expanded = yearMenuExpanded,
                                onDismissRequest = { yearMenuExpanded = false }
                            ) {
                                state.availableYears.sortedDescending().forEach { year ->
                                    DropdownMenuItem(
                                        text = { Text("$year") },
                                        onClick = {
                                            yearMenuExpanded = false
                                            viewModel.setYear(year)
                                            // 切换年份时关闭浮层
                                            selectedDay = null
                                            selectedWeek = null
                                            popupAnchor = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── 个人资料卡片（页面最顶部：自定义头像 + 昵称/邮箱）──
            ProfileCard(
                nickname = nickname,
                email = email,
                avatarPath = avatarPath,
                avatarVersion = avatarVersion,
                onEdit = { showProfileEdit = true },
                onAvatarClick = { avatarMenuVisible = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )

            // ── 活动洞察卡片（个人资料下方：总请求次数 + 最多请求时段 Top3）──
            InsightsCard(
                totalRequests = state.insights.totalRequests,
                topHours = state.insights.topHours,
                loading = state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )

            // ── 视图切换器 ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
            ) {
                HeatmapViewMode.entries.forEach { mode ->
                    val label = when (mode) {
                        HeatmapViewMode.DAILY -> stringResource(R.string.heatmap_view_daily)
                        HeatmapViewMode.WEEKLY -> stringResource(R.string.heatmap_view_weekly)
                        HeatmapViewMode.CUMULATIVE -> stringResource(R.string.heatmap_view_cumulative)
                    }
                    val isSelected = state.viewMode == mode
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else inkMuted(),
                        modifier = Modifier.clickable {
                            viewModel.setViewMode(mode)
                            // 切换视图时关闭浮层
                            selectedDay = null
                            selectedWeek = null
                            popupAnchor = null
                        }
                    )
                }
            }

            // ── 年度统计（按所选年份，切换年份跟随变化）──
            // loading 期间显示占位，避免全 0 造成"无数据"假象
            val dash = "–"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatItem(
                        label = stringResource(R.string.heatmap_stats_total),
                        value = if (state.loading) dash else formatTokenChinese(state.stats.totalTokens, activeLocale),
                        modifier = Modifier.weight(1f),
                    )
                    StatItem(
                        label = stringResource(R.string.heatmap_stats_peak),
                        value = if (state.loading) dash else formatTokenChinese(state.stats.peakTokens, activeLocale),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatItem(
                        label = stringResource(R.string.heatmap_stats_current_streak),
                        value = if (state.loading) {
                            dash
                        } else {
                            pluralStringResource(R.plurals.heatmap_stats_days, state.stats.currentStreak, state.stats.currentStreak)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    StatItem(
                        label = stringResource(R.string.heatmap_stats_max_streak),
                        value = if (state.loading) {
                            dash
                        } else {
                            pluralStringResource(R.plurals.heatmap_stats_days, state.stats.maxStreak, state.stats.maxStreak)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── 热力图 Canvas（带 Crossfade 淡入淡出动画）──
            Crossfade(
                targetState = state.viewMode,
                animationSpec = tween(150),
                label = "heatmap_view"
            ) { mode ->
                // 用 mode 参数构造视图快照：过渡期间新旧两个分支渲染各自模式，
                // 否则两者都读最新 viewMode，动画会变成空操作
                // 无障碍描述也在分支内按 mode 计算，避免淡出分支播报错误的模式
                val desc = if (mode == HeatmapViewMode.WEEKLY) {
                    stringResource(R.string.heatmap_accessibility_desc_weekly, state.weeklyData.size)
                } else {
                    stringResource(R.string.heatmap_accessibility_desc, state.dailyData.size)
                }
                HeatmapCanvas(
                    state = state.copy(viewMode = mode),
                    noDataText = noDataText,
                    lessText = lessText,
                    moreText = moreText,
                    accessibilityDesc = desc,
                    selectedWeek = selectedWeek,
                    onDayClick = { dayData, anchor ->
                        // 再次点击同一格子则关闭浮层（0 token 的天也可点击查看）
                        if (selectedDay == dayData) {
                            selectedDay = null
                            popupAnchor = null
                        } else {
                            selectedDay = dayData
                            selectedWeek = null
                            popupAnchor = anchor
                        }
                    },
                    onWeekClick = { weekData, anchor ->
                        if (selectedWeek == weekData) {
                            selectedWeek = null
                            popupAnchor = null
                        } else {
                            selectedWeek = weekData
                            selectedDay = null
                            popupAnchor = anchor
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ── 个人资料编辑对话框（昵称/邮箱均可完全自定义，留空=清除）──
    if (showProfileEdit) {
        var editNickname by remember { mutableStateOf(nickname) }
        var editEmail by remember { mutableStateOf(email) }
        ProfileEditDialog(
            nickname = editNickname,
            email = editEmail,
            onNicknameChange = { editNickname = it },
            onEmailChange = { editEmail = it },
            onSave = {
                nickname = editNickname
                email = editEmail
                profilePrefs.edit()
                    .putString(KEY_NICKNAME, editNickname)
                    .putString(KEY_EMAIL, editEmail)
                    .apply()
                showProfileEdit = false
            },
            onDismiss = { showProfileEdit = false },
        )
    }

    // ── 头像操作对话框（更换/移除）──
    if (avatarMenuVisible) {
        AlertDialog(
            onDismissRequest = { avatarMenuVisible = false },
            title = { Text(stringResource(R.string.heatmap_profile_avatar_change)) },
            text = {
                Column {
                    TextButton(onClick = {
                        avatarMenuVisible = false
                        avatarPicker.launch("image/*")
                    }) {
                        Text(stringResource(R.string.heatmap_profile_avatar_pick))
                    }
                    if (avatarPath != null) {
                        TextButton(onClick = {
                            avatarMenuVisible = false
                            avatarPath?.let { runCatching { File(it).delete() } }
                            avatarPath = null
                            profilePrefs.edit().remove(KEY_AVATAR_PATH).apply()
                        }) {
                            Text(stringResource(R.string.heatmap_profile_avatar_remove))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { avatarMenuVisible = false }) {
                    Text(stringResource(R.string.heatmap_profile_cancel))
                }
            },
        )
    }

    // ── 浮层 Popup（锚定到被点击格子的中心位置）──
    val day = selectedDay
    val week = selectedWeek
    val anchor = popupAnchor
    if (day != null || week != null) {
        val useUtc8 = state.useUtc8
        val popupText = when {
            day != null -> stringResource(
                R.string.heatmap_day_used,
                formatDateChinese(day.dayTs, useUtc8, state.currentYear, activeLocale),
                formatTokenChinese(day.tokens, activeLocale),
            )
            week != null -> stringResource(
                R.string.heatmap_week_used,
                formatWeekRangeText(week, useUtc8, state.selectedYear, state.dailyData.lastOrNull()?.dayTs, activeLocale),
                formatTokenChinese(week.tokens, activeLocale),
            )
            else -> ""
        }
        // 浮层相对锚点的偏移：水平居中、优先显示在格子上方 8dp 处；
        // 上方空间不足时翻转到下方；x/y 均 clamp 在窗口内
        val gapPx = with(density) { 8.dp.toPx() }.roundToInt()
        val edgePaddingPx = with(density) { 8.dp.toPx() }.roundToInt()
        val configuration = LocalConfiguration.current
        val windowWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.roundToInt()
        val windowHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.roundToInt()
        val maxX = (windowWidthPx - popupSize.width - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        val maxY = (windowHeightPx - popupSize.height - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        val popupOffset = if (anchor != null) {
            val x = (anchor.x - popupSize.width / 2).coerceIn(edgePaddingPx, maxX)
            val y = if (anchor.y - popupSize.height - gapPx >= edgePaddingPx) {
                // 上方放得下：显示在格子上方
                (anchor.y - popupSize.height - gapPx).coerceIn(edgePaddingPx, maxY)
            } else {
                // 上方放不下：翻转到格子下方
                (anchor.y + gapPx).coerceIn(edgePaddingPx, maxY)
            }
            IntOffset(x, y)
        } else {
            IntOffset.Zero
        }
        Popup(
            alignment = Alignment.TopStart,
            offset = popupOffset,
            onDismissRequest = {
                selectedDay = null
                selectedWeek = null
                popupAnchor = null
            },
            // 不拦截图表交互：浮层不获取焦点，点击/滑动直接作用于图表，
            // 点击其他格子即切换浮层，再次点击同一格子关闭
            properties = PopupProperties(focusable = false)
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .onGloballyPositioned { popupSize = it.size }
            ) {
                Text(
                    text = popupText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

// ── 年度统计卡片（累计/峰值/当前连续/最长连续）──
@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 个人资料卡片（页面最顶部：自定义头像 + 昵称/邮箱）──
@Composable
private fun ProfileCard(
    nickname: String,
    email: String,
    avatarPath: String?,
    avatarVersion: Int,
    onEdit: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val emptyText = stringResource(R.string.heatmap_profile_empty)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.heatmap_profile_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(
                    avatarPath = avatarPath,
                    avatarVersion = avatarVersion,
                    nickname = nickname,
                    onClick = onAvatarClick,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = nickname.ifBlank { emptyText },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = email.ifBlank { emptyText },
                        style = MaterialTheme.typography.bodySmall,
                        color = inkMuted(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.heatmap_profile_edit))
                }
            }
        }
    }
}

// ── 自定义头像（圆形裁剪；未设置时显示昵称首字符占位）──
@Composable
private fun ProfileAvatar(
    avatarPath: String?,
    avatarVersion: Int,
    nickname: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 本地文件解码在 IO 线程；key=(路径,版本号)：换头像后路径不变，靠版本号触发重载
    // 先读尺寸再降采样解码（头像仅 56dp 显示，全尺寸解码相册大图会 OOM）
    val avatar by produceState<ImageBitmap?>(initialValue = null, avatarPath, avatarVersion) {
        value = avatarPath?.let { path ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val avatarDesc = stringResource(R.string.heatmap_profile_avatar_change)
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = avatarDesc },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = avatar
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 按 code point 取首字符，避免 emoji（代理对）被截成半个字符显示乱码
            val trimmed = nickname.trim()
            val initial = if (trimmed.isEmpty()) null else String(Character.toChars(trimmed.codePointAt(0)))
            if (initial != null) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = inkMuted(),
                )
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = inkMuted())
            }
        }
    }
}

// ── 个人资料编辑对话框（昵称/邮箱均可完全自定义）──
@Composable
private fun ProfileEditDialog(
    nickname: String,
    email: String,
    onNicknameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.heatmap_profile_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onNicknameChange,
                    label = { Text(stringResource(R.string.heatmap_profile_nickname)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = onEmailChange,
                    label = { Text(stringResource(R.string.heatmap_profile_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.heatmap_profile_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.heatmap_profile_cancel)) }
        },
    )
}

// ── 活动洞察卡片（总请求次数 + 最多请求时段 Top3，全量历史口径）──
@Composable
private fun InsightsCard(
    totalRequests: Int,
    topHours: List<Int>,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val dash = "–"
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.heatmap_insights_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.heatmap_insights_requests),
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (loading) {
                        dash
                    } else {
                        pluralStringResource(R.plurals.heatmap_insights_request_count, totalRequests, totalRequests)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.heatmap_insights_peak_hours),
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted(),
            )
            Spacer(Modifier.height(6.dp))
            // 固定 3 个时段槽位，不足的显示占位，避免布局跳动
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    val hour = topHours.getOrNull(i)
                    Text(
                        text = if (loading || hour == null) {
                            dash
                        } else {
                            stringResource(R.string.heatmap_insight_hour_range, hour, hour + 1)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

// ── 个人资料持久化 ──
private const val PROFILE_PREFS = "heatmap_profile"
private const val KEY_NICKNAME = "nickname"
private const val KEY_EMAIL = "email"
private const val KEY_AVATAR_PATH = "avatar_path"
private const val AVATAR_FILE_NAME = "heatmap_avatar.jpg"
