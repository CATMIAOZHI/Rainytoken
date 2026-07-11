package com.rainy.token.ui.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.ui.theme.StrawberryPink

/**
 * Codex OAuth PKCE 登录页面。
 *
 * 两种模式：
 *  - WEBVIEW：APP 内 WebView 打开 OpenAI 授权页，拦截 localhost:1455 回调
 *  - HEADLESS：显示授权 URL + 复制按钮 + 在浏览器打开 + 粘贴回调 URL 输入框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexOAuthScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: CodexOAuthViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.start(CodexOAuthMode.HEADLESS) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.loginSucceeded) {
        if (uiState.loginSucceeded) onSuccess()
    }

    BackHandler(enabled = !uiState.loginSucceeded) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codex OAuth 登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.exchanging -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = StrawberryPink)
                        Text(
                            "正在交换 Token...",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                uiState.error != null && uiState.mode == CodexOAuthMode.WEBVIEW -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            uiState.error!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = androidx.compose.ui.graphics.Color(0xFFE91E63)
                        )
                        TextButton(onClick = { viewModel.start(CodexOAuthMode.WEBVIEW) }) {
                            Text("重试", color = StrawberryPink)
                        }
                    }
                }

                // 无头模式：显示授权 URL + 粘贴回调
                uiState.mode == CodexOAuthMode.HEADLESS && uiState.authUrl.isNotEmpty() -> {
                    HeadlessOAuthContent(
                        authUrl = uiState.authUrl,
                        error = uiState.error,
                        onCopyUrl = { copyToClipboard(context, uiState.authUrl) },
                        onOpenInBrowser = { openInBrowser(context, uiState.authUrl) },
                        onSubmit = { url -> viewModel.submitCallbackUrl(url) },
                        onRetry = { viewModel.start(CodexOAuthMode.HEADLESS) }
                    )
                }

                // WebView 模式
                uiState.authUrl.isNotEmpty() -> {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString()
                                        if (url != null && viewModel.onUrlChanged(url)) {
                                            return true
                                        }
                                        return false
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                loadUrl(uiState.authUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = StrawberryPink)
                        Text(
                            "正在准备授权...",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

/**
 * 无头模式 UI：授权 URL + 操作按钮 + 回调 URL 粘贴输入框
 */
@Composable
private fun HeadlessOAuthContent(
    authUrl: String,
    error: String?,
    onCopyUrl: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onSubmit: (String) -> Unit,
    onRetry: () -> Unit
) {
    var callbackUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "无头模式登录",
            style = MaterialTheme.typography.titleMedium,
            color = StrawberryPink
        )
        Text(
            text = "1. 点击「在浏览器中打开」或复制下方链接到浏览器\n" +
                    "2. 在 OpenAI 页面完成登录和授权\n" +
                    "3. 浏览器会跳转到一个 localhost 地址（页面打不开是正常的）\n" +
                    "4. 复制浏览器地址栏的完整 URL 粘贴到下方输入框\n" +
                    "5. 点击「提交回调 URL」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        // 授权 URL 预览
        OutlinedTextField(
            value = authUrl,
            onValueChange = {},
            label = { Text("授权链接") },
            readOnly = true,
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenInBrowser,
                modifier = Modifier.weight(1f)
            ) {
                Text("在浏览器中打开")
            }
            OutlinedButton(
                onClick = onCopyUrl,
                modifier = Modifier.weight(1f)
            ) {
                Text("复制链接")
            }
        }

        Text(
            text = "── 登录后粘贴回调 URL ──",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = TextAlign.Center
        )

        OutlinedTextField(
            value = callbackUrl,
            onValueChange = { callbackUrl = it },
            label = { Text("回调 URL") },
            placeholder = { Text("http://localhost:1455/auth/callback?code=...") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onSubmit(callbackUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = callbackUrl.isNotBlank()
        ) {
            Text("提交回调 URL")
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE91E63),
                modifier = Modifier.padding(top = 4.dp)
            )
            TextButton(onClick = onRetry) {
                Text("重新生成授权链接", color = StrawberryPink)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("auth_url", text))
}

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}