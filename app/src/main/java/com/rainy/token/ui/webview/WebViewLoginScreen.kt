package com.rainy.token.ui.webview

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.asString

/**
 * 通用 WebView 登录容器。计划 4.1：
 *  - 开启 CookieManager + setAcceptThirdPartyCookies
 *  - 注入自定义 WebViewClient / WebChromeClient
 *  - `onPageFinished` 时通过 ViewModel 判定是否登录成功
 *  - 提供 fallback 手动确认按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewLoginScreen(
    service: ServiceType,
    onBack: () -> Unit,
    onLoginSucceeded: (ServiceType) -> Unit,
    viewModel: WebViewLoginViewModel = hiltViewModel()
) {
    LaunchedEffect(service) { viewModel.bind(service) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 登录成功自动回调
    LaunchedEffect(uiState.loginSucceeded) {
        if (uiState.loginSucceeded) {
            onLoginSucceeded(service)
        }
    }

    BackHandler(enabled = uiState.loginSucceeded.not()) {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_webview_login, service.displayName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            if (uiState.loginUrl.isNotEmpty()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            // Cookie 持久化（计划 4.1）
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true

                            // 注意：Google OAuth 会拒绝 WebView User-Agent（"此浏览器可能不安全"），
                            // 且 TLS fingerprinting 也会识别 WebView。此入口主要作为 fallback，
                            // 主流程走"手动粘贴 Cookie"路线。

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { viewModel.onPageFinished(it) }
                                }
                            }
                            webChromeClient = WebChromeClient()
                            loadUrl(uiState.loginUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.error_login_url_missing),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    // Fallback 手动确认（计划 4.1）
    if (uiState.pendingManualConfirm && !uiState.loginSucceeded) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissManualPrompt() },
            title = { Text(stringResource(R.string.dialog_login_done_title)) },
            text = {
                Text(
                    uiState.error?.asString() ?: stringResource(R.string.dialog_login_done_body)
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmLoginManually() }) {
                    Text(stringResource(R.string.action_login_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissManualPrompt() }) {
                    Text(stringResource(R.string.action_keep_waiting))
                }
            }
        )
    }
}