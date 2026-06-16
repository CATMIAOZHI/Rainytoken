package com.rainy.token.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.dashboard.DashboardScreen
import com.rainy.token.ui.dashboard.UsageDataScreen
import com.rainy.token.ui.dashboard.UsageDetailScreen
import com.rainy.token.ui.dashboard.UsageOverviewScreen
import com.rainy.token.ui.servicedetail.ServiceDetailScreen
import com.rainy.token.ui.settings.CredentialEditScreen
import com.rainy.token.ui.settings.SettingsScreen
import com.rainy.token.ui.webview.WebViewLoginScreen

/**
 * 应用导航图。当前阶段 4 接入了完整路径：
 *   Dashboard → Settings → CredentialEdit → WebViewLogin
 *   Dashboard → ServiceDetail
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
}

private fun parseServiceType(typeName: String?): ServiceType =
    typeName?.let {
        ServiceType.fromStorageKey(it) ?: runCatching { ServiceType.valueOf(it) }.getOrNull()
    } ?: ServiceType.DEEPSEEK

@Composable
fun RainyTokenNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenService = { type -> navController.navigate(Routes.serviceDetail(type)) },
                onOpenUsageDetail = { navController.navigate(Routes.USAGE_DETAIL) }
            )
        }
        composable(Routes.USAGE_DETAIL) {
            UsageDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenOverview = { navController.navigate(Routes.USAGE_OVERVIEW) },
                onOpenData = { navController.navigate(Routes.USAGE_DATA) }
            )
        }
        composable(Routes.USAGE_OVERVIEW) {
            UsageOverviewScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.USAGE_DATA) {
            UsageDataScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
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
                onBack = { navController.popBackStack() },
                onStartWebViewLogin = { service -> navController.navigate(Routes.webviewLogin(service)) },
                onWebViewLoginSuccess = { /* 登录后回到编辑页，由 LaunchedEffect 触发刷新 */ }
            )
        }
        composable(
            route = Routes.WEBVIEW_LOGIN,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val type = parseServiceType(backStackEntry.arguments?.getString("type"))
            WebViewLoginScreen(
                service = type,
                onBack = { navController.popBackStack() },
                onLoginSucceeded = {
                    // 登录成功直接回退到 CredentialEdit 页
                    navController.popBackStack()
                }
            )
        }
        composable(
            route = Routes.SERVICE_DETAIL,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val type = parseServiceType(backStackEntry.arguments?.getString("type"))
            ServiceDetailScreen(
                service = type,
                onBack = { navController.popBackStack() },
                onConfigureCredential = { svc -> navController.navigate(Routes.credentialEdit(svc)) },
                onStartWebViewLogin = { svc -> navController.navigate(Routes.webviewLogin(svc)) }
            )
        }
    }
}