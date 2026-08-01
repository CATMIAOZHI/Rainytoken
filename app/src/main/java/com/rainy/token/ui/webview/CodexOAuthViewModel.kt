package com.rainy.token.ui.webview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.R
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.data.repository.CodexOAuthHelper
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

enum class CodexOAuthMode { WEBVIEW, HEADLESS }

/**
 * Codex OAuth PKCE 登录 ViewModel。
 *
 * 两种模式：
 *  - WEBVIEW：APP 内 WebView 打开授权页，拦截 localhost:1455 回调
 *  - HEADLESS：生成授权 URL 让用户复制到外部浏览器，登录后粘贴回调 URL 回来
 */
@HiltViewModel
class CodexOAuthViewModel @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodexOAuthUiState())
    val uiState: StateFlow<CodexOAuthUiState> = _uiState.asStateFlow()

    private val TAG = "CodexOAuth"

    fun start(mode: CodexOAuthMode = CodexOAuthMode.WEBVIEW) {
        val pkce = CodexOAuthHelper.generatePkce()
        val state = CodexOAuthHelper.generateState()
        val authUrl = CodexOAuthHelper.buildAuthUrl(pkce.codeChallenge, state)
        DebugLog.i(TAG, "OAuth 流程启动 (${mode.name})，构建授权 URL")
        _uiState.update {
            CodexOAuthUiState(
                mode = mode,
                authUrl = authUrl,
                codeVerifier = pkce.codeVerifier,
                expectedState = state
            )
        }
    }

    /**
     * WebView 拦截到 URL 变化时调用。
     * 如果 URL 匹配 callback，返回 true 表示已处理。
     */
    fun onUrlChanged(url: String?): Boolean {
        if (url == null) return false
        if (!url.startsWith(CodexOAuthHelper.CALLBACK_PREFIX)) return false
        handleCallbackUrl(url)
        return true
    }

    /**
     * 无头模式：用户粘贴回调 URL 后调用。
     */
    fun submitCallbackUrl(url: String) {
        handleCallbackUrl(url.trim())
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 处理回调 URL：解析 code/state，交换 token，保存凭据。
     */
    private fun handleCallbackUrl(url: String) {
        if (!url.startsWith(CodexOAuthHelper.CALLBACK_PREFIX)) {
            _uiState.update {
                it.copy(
                    error = UiText.Resource(
                        R.string.error_oauth_url_prefix,
                        listOf(CodexOAuthHelper.CALLBACK_PREFIX)
                    )
                )
            }
            return
        }

        val uri = android.net.Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val receivedState = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            val errorDesc = uri.getQueryParameter("error_description") ?: error
            DebugLog.e(TAG, "OAuth 授权失败: $errorDesc")
            _uiState.update {
                it.copy(error = UiText.Resource(R.string.error_oauth_auth_failed, listOf(errorDesc)))
            }
            return
        }

        if (receivedState != _uiState.value.expectedState) {
            DebugLog.e(TAG, "OAuth state 不匹配（CSRF 防护）")
            _uiState.update { it.copy(error = UiText.Resource(R.string.error_oauth_state_mismatch)) }
            return
        }

        if (code.isNullOrBlank()) {
            DebugLog.e(TAG, "OAuth 回调缺少 code 参数")
            _uiState.update { it.copy(error = UiText.Resource(R.string.error_oauth_missing_code)) }
            return
        }

        _uiState.update { it.copy(exchanging = true, error = null) }
        val verifier = _uiState.value.codeVerifier ?: return

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                CodexOAuthHelper.exchangeCode(okHttpClient, code, verifier)
            }

            if (result == null) {
                DebugLog.e(TAG, "token 交换失败")
                _uiState.update {
                    it.copy(
                        exchanging = false,
                        error = UiText.Resource(R.string.error_oauth_token_exchange)
                    )
                }
                return@launch
            }

            val accountId = CodexOAuthHelper.extractAccountId(result.idToken, result.accessToken)
            val expiresAt = System.currentTimeMillis() + result.expiresIn * 1000L

            DebugLog.i(TAG, "OAuth 登录成功，accountId=$accountId，expiresAt=$expiresAt")

            val cred = Credential.CodexCredential(
                service = ServiceType.CODEX,
                accessToken = result.accessToken,
                refreshToken = result.refreshToken ?: "",
                accountId = accountId ?: "",
                expiresAt = expiresAt,
                lastVerifiedAt = System.currentTimeMillis()
            )
            credentialRepository.save(cred)

            _uiState.update { it.copy(exchanging = false, loginSucceeded = true) }
        }
    }
}

data class CodexOAuthUiState(
    val mode: CodexOAuthMode = CodexOAuthMode.WEBVIEW,
    val authUrl: String = "",
    val codeVerifier: String? = null,
    val expectedState: String? = null,
    val exchanging: Boolean = false,
    val loginSucceeded: Boolean = false,
    val error: UiText? = null
)