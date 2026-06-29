package com.rainy.token.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rainy.token.data.repository.CommandCodeUsageRepository
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.rememberWindowSizeClass
import com.rainy.token.ui.dashboard.DashboardScreen
import com.rainy.token.ui.dashboard.UsageChartViewModel
import com.rainy.token.ui.dashboard.UsageDataScreen
import com.rainy.token.ui.dashboard.UsageDataViewModel
import com.rainy.token.ui.dashboard.UsageDetailScreen
import com.rainy.token.ui.dashboard.UsageOverviewScreen
import com.rainy.token.ui.dashboard.UsageViewModel
import com.rainy.token.ui.servicedetail.ServiceDetailScreen
import com.rainy.token.ui.settings.CredentialEditScreen
import com.rainy.token.ui.settings.SettingsScreen
import com.rainy.token.ui.theme.inkMuted
import com.rainy.token.ui.theme.StrawberryPink
import com.rainy.token.ui.webview.WebViewLoginScreen

/**
 * 应用导航图。
 *
 * 自适应策略：
 *  - **Compact**（< 600dp，手机）：单栈 NavHost。guardedPop 时间戳围栏（200ms）防连点栈损坏，
 *    popExit=fadeOut(1ms)+popEnter=None 消除过渡动画与连点 pop 的竞态，enter/exit 保留默认动画。
 *  - **Expanded**（≥ 840dp，平板横屏）：左侧 Dashboard + 右侧 when 分支原子切换，
 *    用量子路由用局部 NavHost。
 */
object Routes {
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val CREDENTIAL_EDIT = "credential_edit/{type}"
    fun credentialEdit(type: ServiceType) = "credential_edit/${type.name}"
    const val WEBVIEW_LOGIN = "webview_login/{type}"
    fun webviewLogin(type: ServiceType) = "webview_login/${type.name}"
    const val SERVICE_DETAIL = "service/{type}"
    fun serviceDetail(type: ServiceType) = "service/${type.name}"
    const val USAGE_DETAIL = "usage_detail"
    const val USAGE_OVERVIEW = "usage_overview"
    const val USAGE_DATA = "usage_data"
    const val CCGO_USAGE_DETAIL = "ccgo_usage_detail"
    const val CCGO_USAGE_OVERVIEW = "ccgo_usage_overview"
    const val CCGO_USAGE_DATA = "ccgo_usage_data"
}

private fun parseServiceType(typeName: String?): ServiceType =
    typeName?.let {
        ServiceType.fromStorageKey(it) ?: runCatching { ServiceType.valueOf(it) }.getOrNull()
    } ?: ServiceType.DEEPSEEK

private sealed class DetailPane {
    object Empty : DetailPane()
    data class ServiceDetail(val type: ServiceType) : DetailPane()
    object OCGOUsage : DetailPane()
    object CCGOUsage : DetailPane()
    object Settings : DetailPane()
}

@Composable
fun RainyTokenNavHost() {
    val windowSize = rememberWindowSizeClass()
    val isExpanded = windowSize.widthSizeClass ==
        androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Expanded

    if (isExpanded) {
        ExpandedLayout()
    } else {
        CompactNavHost()
    }
}

// ═══════════════════════════════════════════════
// Compact：单栈 NavHost —— guardedPop 围栏 + popExit/popEnter=None
// ═══════════════════════════════════════════════

/**
 * 非 Compose State 的时间戳围栏。
 *
 * 为什么不用 mutableStateOf：
 *   mutableStateOf 写入会触发 CompactNavHost → NavHost 重组。若重组发生在
 *   AnimatedContent 过渡动画期间，会干扰内部 MutableTransitionState 状态机，
 *   导致旧 composable 已移除、新 composable 进入动画被中断后未正确启动 → 空白页。
 *
 * 连点保护：cooldownMs 内只放行一次 pop。popBackStack() 返回 false 时 reset。
 */
private class PopGuard(
    private val cooldownMs: Long = 200
) {
    private var lastPopTime: Long = 0L

    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPopTime < cooldownMs) return false
        lastPopTime = now
        return true
    }

    fun reset() {
        lastPopTime = 0L
    }
}

@Composable
private fun CompactNavHost() {
    val navController = rememberNavController()
    // 围栏：200ms 内只允许一次 pop；普通对象持有时间戳，不触发 NavHost 重组
    val popGuard = remember { PopGuard() }
    val guardedPop: () -> Unit = {
        // 起始页无 previousBackStackEntry，直接跳过，避免无意义的 popBackStack() 调用
        if (navController.previousBackStackEntry != null && popGuard.tryAcquire()) {
            // popBackStack() 返回 false = 已在起始页，reset 围栏不浪费冷却
            if (!navController.popBackStack()) {
                popGuard.reset()
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD,
        // popExit=fadeOut(1ms)：极短但非零的退出动画，确保 AnimatedContent 正确执行
        //   transition 结束帧来移除旧 composable。ExitTransition.None 是字面零帧，
        //   连续 pop 时上一帧的退出 composable 可能未从 layout 树中清除 → 幽灵残影。
        // popEnter=None：新 composable 瞬时出现，消除进入动画与连点 pop 的竞态
        //   （根因：默认 popEnter fadeIn(700ms) 时，第二次 pop 中断进入动画导致
        //    AnimatedContent 状态不一致 → 空白页）
        popExitTransition = { fadeOut(animationSpec = tween(1)) },
        popEnterTransition = { EnterTransition.None }
    ) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenService = { type -> navController.navigate(Routes.serviceDetail(type)) },
                onOpenUsageDetail = { navController.navigate(Routes.USAGE_DETAIL) },
                onOpenCcgoUsageDetail = { navController.navigate(Routes.CCGO_USAGE_DETAIL) }
            )
        }
        composable(Routes.USAGE_DETAIL) {
            UsageDetailScreen(
                onBack = guardedPop,
                onOpenOverview = { navController.navigate(Routes.USAGE_OVERVIEW) },
                onOpenData = { navController.navigate(Routes.USAGE_DATA) }
            )
        }
        composable(Routes.USAGE_OVERVIEW) {
            UsageOverviewScreen(
                onBack = guardedPop
            )
        }
        composable(Routes.USAGE_DATA) {
            UsageDataScreen(
                onBack = guardedPop
            )
        }
        composable(Routes.CCGO_USAGE_DETAIL) {
            val wid = CommandCodeUsageRepository.CCGO_WORKSPACE_ID
            val chartVm: UsageChartViewModel = hiltViewModel(key = "ccgo_chart_$wid")
            val usageVm: UsageViewModel = hiltViewModel(key = "ccgo_$wid")
            LaunchedEffect(Unit) {
                usageVm.setWorkspace(wid)
                chartVm.setWorkspace(wid)
            }
            UsageDetailScreen(
                onBack = guardedPop,
                onOpenOverview = { navController.navigate(Routes.CCGO_USAGE_OVERVIEW) },
                onOpenData = { navController.navigate(Routes.CCGO_USAGE_DATA) },
                viewModel = chartVm,
                clearViewModel = usageVm
            )
        }
        composable(Routes.CCGO_USAGE_OVERVIEW) {
            val key = "ccgo_${CommandCodeUsageRepository.CCGO_WORKSPACE_ID}"
            val ovVm: UsageViewModel = hiltViewModel(key = key)
            LaunchedEffect(Unit) { ovVm.setWorkspace(CommandCodeUsageRepository.CCGO_WORKSPACE_ID) }
            UsageOverviewScreen(
                onBack = guardedPop,
                viewModel = ovVm,
                autoLoad = false
            )
        }
        composable(Routes.CCGO_USAGE_DATA) {
            val key = "ccgo_${CommandCodeUsageRepository.CCGO_WORKSPACE_ID}"
            val dataVm: UsageDataViewModel = hiltViewModel(key = key)
            LaunchedEffect(Unit) { dataVm.setWorkspace(CommandCodeUsageRepository.CCGO_WORKSPACE_ID) }
            UsageDataScreen(
                onBack = guardedPop,
                viewModel = dataVm,
                autoLoad = false
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = guardedPop,
                onEditCredential = { type -> navController.navigate(Routes.credentialEdit(type)) }
            )
        }
        composable(
            route = Routes.CREDENTIAL_EDIT,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val type = parseServiceType(backStackEntry.arguments?.getString("type"))
            CredentialEditScreen(
                service = type,
                onBack = guardedPop,
                onStartWebViewLogin = { service -> navController.navigate(Routes.webviewLogin(service)) },
                onWebViewLoginSuccess = { }
            )
        }
        composable(
            route = Routes.WEBVIEW_LOGIN,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val type = parseServiceType(backStackEntry.arguments?.getString("type"))
            WebViewLoginScreen(
                service = type,
                onBack = guardedPop,
                onLoginSucceeded = { guardedPop() }
            )
        }
        composable(
            route = Routes.SERVICE_DETAIL,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val type = parseServiceType(backStackEntry.arguments?.getString("type"))
            ServiceDetailScreen(
                service = type,
                onBack = guardedPop,
                onConfigureCredential = { svc -> navController.navigate(Routes.credentialEdit(svc)) },
                onStartWebViewLogin = { svc -> navController.navigate(Routes.webviewLogin(svc)) }
            )
        }
    }
}

// ═══════════════════════════════════════════════
// Expanded：手动双窗格
// ═══════════════════════════════════════════════

@Composable
private fun ExpandedLayout() {
    var detailPane by remember { mutableStateOf<DetailPane>(DetailPane.Empty) }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxHeight()
        ) {
            DashboardScreen(
                onOpenSettings = { detailPane = DetailPane.Settings },
                onOpenService = { type -> detailPane = DetailPane.ServiceDetail(type) },
                onOpenUsageDetail = { detailPane = DetailPane.OCGOUsage },
                onOpenCcgoUsageDetail = { detailPane = DetailPane.CCGOUsage }
            )
        }

        VerticalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Box(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ExpandedDetailPane(
                pane = detailPane,
                onClose = { detailPane = DetailPane.Empty }
            )
        }
    }
}

@Composable
private fun ExpandedDetailPane(
    pane: DetailPane,
    onClose: () -> Unit
) {
    when (pane) {
        is DetailPane.Empty -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👈 选择服务查看详情", style = MaterialTheme.typography.titleMedium, color = inkMuted())
                    Text("点击左侧卡片即可", style = MaterialTheme.typography.bodySmall, color = inkMuted())
                }
            }
        }
        is DetailPane.ServiceDetail -> {
            ServiceDetailScreen(
                service = pane.type,
                onBack = onClose,
                onConfigureCredential = { onClose() },
                onStartWebViewLogin = { }
            )
        }
        is DetailPane.OCGOUsage -> {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "chart",
                popExitTransition = { ExitTransition.None }
            ) {
                composable("chart") {
                    UsageDetailScreen(
                        onBack = onClose,
                        onOpenOverview = { navController.navigate("overview") },
                        onOpenData = { navController.navigate("data") }
                    )
                }
                composable("overview") {
                    UsageOverviewScreen(onBack = { navController.popBackStack() }, autoLoad = true)
                }
                composable("data") {
                    UsageDataScreen(onBack = { navController.popBackStack() }, autoLoad = true)
                }
            }
        }
        is DetailPane.CCGOUsage -> {
            val navController = rememberNavController()
            val wid = CommandCodeUsageRepository.CCGO_WORKSPACE_ID
            val chartVm: UsageChartViewModel = hiltViewModel(key = "ccgo_chart_$wid")
            val usageVm: UsageViewModel = hiltViewModel(key = "ccgo_$wid")
            LaunchedEffect(Unit) {
                usageVm.setWorkspace(wid)
                chartVm.setWorkspace(wid)
            }
            NavHost(
                navController = navController,
                startDestination = "chart",
                popExitTransition = { ExitTransition.None }
            ) {
                composable("chart") {
                    UsageDetailScreen(
                        onBack = onClose,
                        onOpenOverview = { navController.navigate("overview") },
                        onOpenData = { navController.navigate("data") },
                        viewModel = chartVm,
                        clearViewModel = usageVm
                    )
                }
                composable("overview") {
                    val key = "ccgo_$wid"
                    val ovVm: UsageViewModel = hiltViewModel(key = key)
                    LaunchedEffect(Unit) { ovVm.setWorkspace(wid) }
                    UsageOverviewScreen(onBack = { navController.popBackStack() }, viewModel = ovVm, autoLoad = false)
                }
                composable("data") {
                    val key = "ccgo_$wid"
                    val dataVm: UsageDataViewModel = hiltViewModel(key = key)
                    LaunchedEffect(Unit) { dataVm.setWorkspace(wid) }
                    UsageDataScreen(onBack = { navController.popBackStack() }, viewModel = dataVm, autoLoad = false)
                }
            }
        }
        is DetailPane.Settings -> {
            val settingsNavController = rememberNavController()
            NavHost(
                navController = settingsNavController,
                startDestination = "settings_main",
                popExitTransition = { ExitTransition.None }
            ) {
                composable("settings_main") {
                    SettingsScreen(
                        onBack = onClose,
                        onEditCredential = { type ->
                            settingsNavController.navigate(Routes.credentialEdit(type))
                        }
                    )
                }
                composable(
                    route = Routes.CREDENTIAL_EDIT,
                    arguments = listOf(navArgument("type") { type = NavType.StringType })
                ) { entry ->
                    val type = parseServiceType(entry.arguments?.getString("type"))
                    CredentialEditScreen(
                        service = type,
                        onBack = { settingsNavController.popBackStack() },
                        onStartWebViewLogin = { svc ->
                            settingsNavController.navigate(Routes.webviewLogin(svc))
                        },
                        onWebViewLoginSuccess = { }
                    )
                }
                composable(
                    route = Routes.WEBVIEW_LOGIN,
                    arguments = listOf(navArgument("type") { type = NavType.StringType })
                ) { entry ->
                    val type = parseServiceType(entry.arguments?.getString("type"))
                    WebViewLoginScreen(
                        service = type,
                        onBack = { settingsNavController.popBackStack() },
                        onLoginSucceeded = { settingsNavController.popBackStack() }
                    )
                }
            }
        }
    }
}