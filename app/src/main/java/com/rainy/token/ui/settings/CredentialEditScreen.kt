package com.rainy.token.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.domain.service.ServiceType

/**
 * 凭据编辑页。
 *
 * - REST API 服务（DeepSeek）：API Key 表单
 * - WebView 类服务（OpenCode Go）：
 *     - **主路径**：手动粘贴 Cookie（避免 Google OAuth 拦截 WebView）
 *     - **备用路径**：WebView 登录（保留但会提示 Google OAuth 不让过）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialEditScreen(
    service: ServiceType,
    onBack: () -> Unit,
    onStartWebViewLogin: (ServiceType) -> Unit,
    onWebViewLoginSuccess: (ServiceType) -> Unit,
    viewModel: CredentialEditViewModel = hiltViewModel()
) {
    LaunchedEffect(service) { viewModel.bind(service) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showCookieHelp by remember { mutableStateOf(false) }
    var showGoHelp by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(service.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (uiState.hasExisting) {
                        IconButton(onClick = { viewModel.deleteCredential() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } }
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.isApiKeyService) {
                if (service == ServiceType.COMMANDCODE_GO) {
                    CommandCodeGoForm(
                        apiKey = uiState.apiKey,
                        cookieInput = uiState.cookieInput,
                        hasExisting = uiState.hasExisting,
                        onApiKeyChange = viewModel::updateApiKey,
                        onCookieChange = viewModel::updateCookieInput,
                        onSave = viewModel::saveCommandCodeGoCredential,
                        onTestAndSave = viewModel::testAndSaveCommandCodeGo
                    )
                } else {
                    ApiKeyForm(
                        apiKey = uiState.apiKey,
                        hasExisting = uiState.hasExisting,
                        onApiKeyChange = viewModel::updateApiKey,
                        onSave = viewModel::saveApiKey,
                        onTestAndSave = viewModel::testAndSaveApiKey
                    )
                }
                } else {
                    // OpenCode Go：用户粘贴 auth cookie + workspaceId（自动抓取）
                        if (service == ServiceType.OPENCODE_GO) {
                            OpenCodeGoForm(
                                authCookie = uiState.authCookie,
                                workspaceId = uiState.workspaceId,
                                loginUrl = uiState.loginUrl,
                                hasExisting = uiState.hasExisting,
                                onAuthCookieChange = viewModel::updateAuthCookie,
                                onWorkspaceIdChange = viewModel::updateWorkspaceId,
                                onSave = { viewModel.saveOpenCodeGoSession() },
                                onTestAndSave = { viewModel.testAndSaveOpenCodeGo() },
                                onImportFromClipboard = { viewModel.importFromClipboard(context) },
                                onCopyLoginUrl = { copyToClipboard(context, uiState.loginUrl) },
                                onOpenLoginUrl = { openInBrowser(context, uiState.loginUrl) },
                                onShowHelp = { showGoHelp = true }
                            )
                        } else {
                        // 通用 WebView 抓取 / 手动模式
                        ManualCookieForm(
                            cookieValue = uiState.cookieInput,
                            onCookieChange = viewModel::updateCookieInput,
                            onSave = { viewModel.saveCookies() },
                            onCopyLoginUrl = {
                                copyToClipboard(context, uiState.loginUrl)
                            },
                            onOpenLoginUrl = {
                                openInBrowser(context, uiState.loginUrl)
                            },
                            onShowHelp = { showCookieHelp = true }
                        )
                        OutlinedButton(
                            onClick = { onStartWebViewLogin(service) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "尝试 WebView 登录（备用）")
                        }
                        if (uiState.hasExisting) {
                            Text(
                                text = "✓ 已配置登录态（${uiState.cookieCount} 个 Cookie）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCookieHelp) {
        AlertDialog(
            onDismissRequest = { showCookieHelp = false },
            title = { Text("如何获取 Cookie？") },
            text = {
                Column {
                    Text("1. 在桌面 Chrome 打开下方登录入口")
                    Text("2. 用 GitHub / Google 登录（不会被拦截）")
                    Text("3. 登录后按 F12 → Application → Cookies")
                    Text("4. 复制该站点下的所有 Cookie 粘贴到上面输入框")
                    Text("格式：name1=value1; name2=value2")
                }
            },
            confirmButton = {
                TextButton(onClick = { showCookieHelp = false }) {
                    Text("我知道了")
                }
            }
        )
    }

    if (showGoHelp) {
        AlertDialog(
            onDismissRequest = { showGoHelp = false },
            title = { Text("如何获取 OpenCode Go 凭据？") },
            text = {
                Column {
                    Text("1. 桌面 Chrome 打开 opencode.ai/auth")
                    Text("2. 用 GitHub / Google 登录（Google 不会拦截 Chrome）")
                    Text("3. 登录后跳转到 dashboard，URL 形如：")
                    Text("   https://opencode.ai/workspace/{id}/go")
                    Text("4. 复制 URL 中的 {id} 段 → workspaceId")
                    Text("5. F12 → Application → Cookies")
                    Text("6. 找到域名 opencode.ai 下名为 auth 的 Cookie")
                    Text("7. 复制它的 Value 字段 → auth cookie")
                    Text("注意：只填 auth 一个 Cookie 即可，不要整个 Cookie 字符串")
                }
            },
            confirmButton = {
                TextButton(onClick = { showGoHelp = false }) {
                    Text("我知道了")
                }
            }
        )
    }
}

@Composable
private fun OpenCodeGoForm(
    authCookie: String,
    workspaceId: String,
    loginUrl: String,
    hasExisting: Boolean,
    onAuthCookieChange: (String) -> Unit,
    onWorkspaceIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestAndSave: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onCopyLoginUrl: () -> Unit,
    onOpenLoginUrl: () -> Unit,
    onShowHelp: () -> Unit
) {
    Text(text = "OpenCode Go 凭据", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "需要 auth cookie + workspaceId 两个值。APP 会用 OkHttp 抓 dashboard 并自动解析 5h/周/月 三档用量。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedButton(onClick = onImportFromClipboard, modifier = Modifier.fillMaxWidth()) {
        Text(text = "从剪贴板自动导入")
    }
    OutlinedTextField(
        value = workspaceId,
        onValueChange = onWorkspaceIdChange,
        label = { Text("Workspace ID") },
        placeholder = { Text("dashboard URL 中的 {id} 段") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = authCookie,
        onValueChange = onAuthCookieChange,
        label = { Text("auth Cookie 值") },
        placeholder = { Text("F12 → Cookies → opencode.ai → auth 的 Value") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = authCookie.isNotBlank() && workspaceId.isNotBlank()
    ) {
        Text(text = if (hasExisting) "更新凭据" else "保存凭据")
    }
    OutlinedButton(
        onClick = onTestAndSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = authCookie.isNotBlank() && workspaceId.isNotBlank()
    ) {
        Text(text = "测试并保存")
    }
    OutlinedButton(onClick = onShowHelp, modifier = Modifier.fillMaxWidth()) {
        Text(text = "如何获取这两个值？")
    }
    OutlinedButton(
        onClick = onOpenLoginUrl,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "在浏览器中打开登录入口")
    }
    TextButton(onClick = onCopyLoginUrl, modifier = Modifier.fillMaxWidth()) {
        Text(text = "复制登录 URL 到剪贴板")
    }
}

@Composable
private fun CommandCodeGoForm(
    apiKey: String,
    cookieInput: String,
    hasExisting: Boolean,
    onApiKeyChange: (String) -> Unit,
    onCookieChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestAndSave: () -> Unit
) {
    Text(text = "CommandCode Go 凭据", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "需要 API Key（拉余额）。Session Cookie（可选）在浏览器 DevTools 复制，用于拉用量记录。不填也可正常查看余额。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("API Key") },
        placeholder = { Text("从 Studio → API Keys 生成") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = cookieInput,
        onValueChange = onCookieChange,
        label = { Text("Session Cookie") },
        placeholder = { Text("Secure-commandcode_prod_.session_token=xxx; Secure-commandcode_prod_.session_data=xxx") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(if (hasExisting) "更新凭据" else "保存凭据")
    }
    OutlinedButton(
        onClick = onTestAndSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = apiKey.isNotBlank()
    ) {
        Text(text = "测试并保存")
    }
}

@Composable
private fun ApiKeyForm(
    apiKey: String,
    hasExisting: Boolean,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestAndSave: () -> Unit
) {
    Text(text = "请输入 API Key", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "凭据会使用 Android Keystore (AES-256 GCM) 加密后存储到本机 DataStore。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("API Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Password
        ),
        modifier = Modifier.fillMaxWidth()
    )
Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (hasExisting) "更新" else "保存")
        }
        OutlinedButton(
            onClick = onTestAndSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKey.isNotBlank()
        ) {
            Text(text = "测试并保存")
        }
    }

@Composable
private fun ManualCookieForm(
    cookieValue: String,
    onCookieChange: (String) -> Unit,
    onSave: () -> Unit,
    onCopyLoginUrl: () -> Unit,
    onOpenLoginUrl: () -> Unit,
    onShowHelp: () -> Unit
) {
    Text(text = "粘贴登录态", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Google OAuth 会拒绝 APP 内置 WebView，所以请用浏览器登录后把 Cookie 粘到这里。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = cookieValue,
        onValueChange = onCookieChange,
        label = { Text("Cookie 字符串") },
        placeholder = { Text("name1=value1; name2=value2; ...") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        minLines = 4
    )
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(text = "保存 Cookie")
    }
    OutlinedButton(onClick = onShowHelp, modifier = Modifier.fillMaxWidth()) {
        Text(text = "如何获取 Cookie？")
    }
    OutlinedButton(
        onClick = onOpenLoginUrl,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "在浏览器中打开登录入口")
    }
    TextButton(onClick = onCopyLoginUrl, modifier = Modifier.fillMaxWidth()) {
        Text(text = "复制登录 URL 到剪贴板")
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("login_url", text))
}

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}