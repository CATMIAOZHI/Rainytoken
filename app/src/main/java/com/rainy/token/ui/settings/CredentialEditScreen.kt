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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.rainy.token.ui.components.resolve
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
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
    onStartCodexOAuth: () -> Unit = {},
    viewModel: CredentialEditViewModel = hiltViewModel()
) {
    LaunchedEffect(service) { viewModel.bind(service) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showCookieHelp by remember { mutableStateOf(false) }
    var showGoHelp by remember { mutableStateOf(false) }
    var showOpenCcgoHelp by remember { mutableStateOf(false) }
    var showCodexHelp by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg.resolve(context))
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(service.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (uiState.hasExisting) {
                        IconButton(onClick = { viewModel.deleteCredential() }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
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
                        onTestAndSave = viewModel::testAndSaveCommandCodeGo,
                        onShowHelp = { showOpenCcgoHelp = true }
                    )
                } else if (service == ServiceType.CODEX) {
                    CodexAuthJsonForm(
                        authJson = uiState.codexAuthJson,
                        hasExisting = uiState.hasExisting,
                        onAuthJsonChange = viewModel::updateCodexAuthJson,
                        onSave = viewModel::saveCodexAuthJson,
                        onShowHelp = { showCodexHelp = true },
                        onStartOAuth = onStartCodexOAuth
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
                    // Ollama：用户粘贴完整 Cookie 字符串
                        if (service == ServiceType.OLLAMA) {
                            OllamaCookieForm(
                                cookie = uiState.ollamaCookie,
                                loginUrl = uiState.loginUrl,
                                hasExisting = uiState.hasExisting,
                                triggerApiKey = uiState.triggerApiKey,
                                onCookieChange = viewModel::updateOllamaCookie,
                                onSave = { viewModel.saveOllamaCredential() },
                                onTestAndSave = { viewModel.testAndSaveOllama() },
                                onCopyLoginUrl = { copyToClipboard(context, uiState.loginUrl) },
                                onOpenLoginUrl = { openInBrowser(context, uiState.loginUrl) },
                                onApiKeyChange = viewModel::updateTriggerApiKey,
                                onSaveApiKey = { viewModel.saveTriggerApiKey() }
                            )
                        } else if (service == ServiceType.OPENCODE_GO) {
                            OpenCodeGoForm(
                                authCookie = uiState.authCookie,
                                workspaceId = uiState.workspaceId,
                                loginUrl = uiState.loginUrl,
                                hasExisting = uiState.hasExisting,
                                triggerApiKey = uiState.triggerApiKey,
                                onAuthCookieChange = viewModel::updateAuthCookie,
                                onWorkspaceIdChange = viewModel::updateWorkspaceId,
                                onSave = { viewModel.saveOpenCodeGoSession() },
                                onTestAndSave = { viewModel.testAndSaveOpenCodeGo() },
                                onImportFromClipboard = { viewModel.importFromClipboard(context) },
                                onCopyLoginUrl = { copyToClipboard(context, uiState.loginUrl) },
                                onOpenLoginUrl = { openInBrowser(context, uiState.loginUrl) },
                                onShowHelp = { showGoHelp = true },
                                onApiKeyChange = viewModel::updateTriggerApiKey,
                                onSaveApiKey = { viewModel.saveTriggerApiKey() }
                            )
                        } else {
                        // 通用 WebView 抓取已移除（半成品功能，凭据无法正确映射到 Repository 字段）
                        // 保留手动 Cookie 粘贴作为 fallback
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
                        if (uiState.hasExisting) {
                            Text(
                                text = stringResource(R.string.credential_configured_cookies, uiState.cookieCount),
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
            title = { Text(stringResource(R.string.help_cookie_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.help_cookie_1))
                    Text(stringResource(R.string.help_cookie_2))
                    Text(stringResource(R.string.help_cookie_3))
                    Text(stringResource(R.string.help_cookie_4))
                    Text(stringResource(R.string.help_cookie_5))
                }
            },
            confirmButton = {
                TextButton(onClick = { showCookieHelp = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        )
    }

    if (showGoHelp) {
        AlertDialog(
            onDismissRequest = { showGoHelp = false },
            title = { Text(stringResource(R.string.help_ocgo_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.help_ocgo_1))
                    Text(stringResource(R.string.help_ocgo_2))
                    Text(stringResource(R.string.help_ocgo_3))
                    Text(stringResource(R.string.help_ocgo_4))
                    Text(stringResource(R.string.help_ocgo_5))
                    Text(stringResource(R.string.help_ocgo_6))
                    Text(stringResource(R.string.help_ocgo_7))
                    Text(stringResource(R.string.help_ocgo_8))
                    Text(stringResource(R.string.help_ocgo_note))
                }
            },
            confirmButton = {
                TextButton(onClick = { showGoHelp = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        )
    }

    if (showOpenCcgoHelp) {
        AlertDialog(
            onDismissRequest = { showOpenCcgoHelp = false },
            title = { Text(stringResource(R.string.help_ccgo_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.help_ccgo_1))
                    Text("")
                    Text(stringResource(R.string.help_ccgo_2))
                    Text(stringResource(R.string.help_ccgo_3))
                    Text("")
                    Text(stringResource(R.string.help_ccgo_4))
                    Text(stringResource(R.string.help_ccgo_5))
                    Text(stringResource(R.string.help_ccgo_6))
                    Text(stringResource(R.string.help_ccgo_7))
                    Text("")
                    Text(stringResource(R.string.help_ccgo_note))
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenCcgoHelp = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        )
    }

    if (showCodexHelp) {
        AlertDialog(
            onDismissRequest = { showCodexHelp = false },
            title = { Text(stringResource(R.string.help_codex_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.help_codex_1))
                    Text(stringResource(R.string.help_codex_2))
                    Text("")
                    Text(stringResource(R.string.help_codex_3))
                    Text(stringResource(R.string.help_codex_4))
                    Text("")
                    Text(stringResource(R.string.help_codex_5))
                    Text(stringResource(R.string.help_codex_6))
                    Text("")
                    Text(stringResource(R.string.help_codex_7))
                    Text(stringResource(R.string.help_codex_8))
                    Text(stringResource(R.string.help_codex_9))
                    Text(stringResource(R.string.help_codex_10))
                    Text(stringResource(R.string.help_codex_11))
                    Text(stringResource(R.string.help_codex_12))
                    Text("")
                    Text(stringResource(R.string.help_codex_note))
                    Text(stringResource(R.string.help_codex_note2))
                }
            },
            confirmButton = {
                TextButton(onClick = { showCodexHelp = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        )
    }
}

@Composable
private fun OllamaCookieForm(
    cookie: String,
    loginUrl: String,
    hasExisting: Boolean,
    triggerApiKey: String,
    onCookieChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestAndSave: () -> Unit,
    onCopyLoginUrl: () -> Unit,
    onOpenLoginUrl: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSaveApiKey: () -> Unit
) {
    Text(text = stringResource(R.string.credential_title_ollama), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.credential_hint_ollama),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = cookie,
        onValueChange = onCookieChange,
        label = { Text(stringResource(R.string.field_cookie_string)) },
        placeholder = { Text("aid=xxx; __Secure-session=yyy") },
        singleLine = false,
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            enabled = cookie.isNotBlank()
        ) {
            Text(text = if (hasExisting) stringResource(R.string.credential_update) else stringResource(R.string.credential_save))
        }
        OutlinedButton(
            onClick = onTestAndSave,
            modifier = Modifier.weight(1f),
            enabled = cookie.isNotBlank()
        ) {
            Text(text = stringResource(R.string.action_test_and_save))
        }
    }
    OutlinedButton(
        onClick = onOpenLoginUrl,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(R.string.action_open_ollama_settings))
    }
    TextButton(onClick = onCopyLoginUrl, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_copy_login_url))
    }

    // ── 一键激活用量 API Key ──
    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        text = stringResource(R.string.activate_api_key_optional),
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        text = stringResource(R.string.hint_ollama_trigger_key),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = triggerApiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(R.string.field_api_key)) },
        placeholder = { Text(stringResource(R.string.placeholder_ollama_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedButton(
        onClick = onSaveApiKey,
        modifier = Modifier.fillMaxWidth(),
        enabled = triggerApiKey.isNotBlank()
    ) {
        Text(stringResource(R.string.action_save_api_key))
    }
}

@Composable
private fun OpenCodeGoForm(
    authCookie: String,
    workspaceId: String,
    loginUrl: String,
    hasExisting: Boolean,
    triggerApiKey: String,
    onAuthCookieChange: (String) -> Unit,
    onWorkspaceIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestAndSave: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onCopyLoginUrl: () -> Unit,
    onOpenLoginUrl: () -> Unit,
    onShowHelp: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSaveApiKey: () -> Unit
) {
    Text(text = stringResource(R.string.credential_title_opencode_go), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.credential_hint_opencode_go),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedButton(onClick = onImportFromClipboard, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_import_from_clipboard))
    }
    OutlinedTextField(
        value = workspaceId,
        onValueChange = onWorkspaceIdChange,
        label = { Text(stringResource(R.string.field_workspace_id)) },
        placeholder = { Text(stringResource(R.string.placeholder_workspace_id)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = authCookie,
        onValueChange = onAuthCookieChange,
        label = { Text(stringResource(R.string.field_auth_cookie_value)) },
        placeholder = { Text(stringResource(R.string.placeholder_auth_cookie)) },
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
        Text(text = if (hasExisting) stringResource(R.string.credential_update) else stringResource(R.string.credential_save))
    }
    OutlinedButton(
        onClick = onTestAndSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = authCookie.isNotBlank() && workspaceId.isNotBlank()
    ) {
        Text(text = stringResource(R.string.action_test_and_save))
    }
    OutlinedButton(onClick = onShowHelp, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_how_get_two_values))
    }
    OutlinedButton(
        onClick = onOpenLoginUrl,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(R.string.action_open_login_entry))
    }
    TextButton(onClick = onCopyLoginUrl, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_copy_login_url))
    }

    // ── 一键激活用量 API Key ──
    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        text = stringResource(R.string.activate_api_key_optional),
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        text = stringResource(R.string.hint_opencode_trigger_key),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = triggerApiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(R.string.field_api_key)) },
        placeholder = { Text("opencode-xxx") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedButton(
        onClick = onSaveApiKey,
        modifier = Modifier.fillMaxWidth(),
        enabled = triggerApiKey.isNotBlank()
    ) {
        Text(stringResource(R.string.action_save_api_key))
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
    onTestAndSave: () -> Unit,
    onShowHelp: () -> Unit
) {
    Text(text = stringResource(R.string.credential_title_ccgo), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.credential_hint_ccgo),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(R.string.field_api_key)) },
        placeholder = { Text(stringResource(R.string.placeholder_api_key_from_settings)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = cookieInput,
        onValueChange = onCookieChange,
        label = { Text(stringResource(R.string.field_session_cookie_optional)) },
        placeholder = { Text(stringResource(R.string.placeholder_session_cookie)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(if (hasExisting) stringResource(R.string.credential_update) else stringResource(R.string.credential_save))
    }
    OutlinedButton(
        onClick = onTestAndSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = apiKey.isNotBlank()
    ) {
        Text(text = stringResource(R.string.action_test_and_save))
    }
    OutlinedButton(onClick = onShowHelp, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_how_get_api_key_cookie))
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
    Text(text = stringResource(R.string.api_key_form_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.api_key_form_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(R.string.field_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Password
        ),
        modifier = Modifier.fillMaxWidth()
    )
Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (hasExisting) stringResource(R.string.action_update) else stringResource(R.string.action_save))
        }
        OutlinedButton(
            onClick = onTestAndSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKey.isNotBlank()
        ) {
            Text(text = stringResource(R.string.action_test_and_save))
        }
    }

@Composable
private fun CodexAuthJsonForm(
    authJson: String,
    hasExisting: Boolean,
    onAuthJsonChange: (String) -> Unit,
    onSave: () -> Unit,
    onShowHelp: () -> Unit,
    onStartOAuth: () -> Unit = {}
) {
    Text(text = stringResource(R.string.credential_title_codex), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.credential_hint_codex),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Button(onClick = onStartOAuth, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_oauth_login))
    }
    Text(
        text = stringResource(R.string.or_manual_import),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
    OutlinedTextField(
        value = authJson,
        onValueChange = onAuthJsonChange,
        label = { Text(stringResource(R.string.field_auth_json)) },
        placeholder = { Text(stringResource(R.string.placeholder_auth_json)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 6
    )
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(if (hasExisting) stringResource(R.string.credential_update) else stringResource(R.string.credential_import_save))
    }
    OutlinedButton(onClick = onShowHelp, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_how_get_auth_json))
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
    Text(text = stringResource(R.string.manual_cookie_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.manual_cookie_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    OutlinedTextField(
        value = cookieValue,
        onValueChange = onCookieChange,
        label = { Text(stringResource(R.string.field_cookie_string)) },
        placeholder = { Text("name1=value1; name2=value2; ...") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        minLines = 4
    )
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_save_cookie))
    }
    OutlinedButton(onClick = onShowHelp, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.help_cookie_title))
    }
    OutlinedButton(
        onClick = onOpenLoginUrl,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(R.string.action_open_login_entry))
    }
    TextButton(onClick = onCopyLoginUrl, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.action_copy_login_url))
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