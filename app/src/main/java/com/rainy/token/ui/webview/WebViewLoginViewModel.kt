package com.rainy.token.ui.webview

import androidx.lifecycle.ViewModel
import com.rainy.token.data.repository.WebViewSessionSaver
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * WebView 登录容器 ViewModel。计划 4.1：
 *  - 持有目标 URL（来自 ServiceConfig）
 *  - 提供 [onLoginSuccess] 回调：抓 Cookie → 加密存 SecureStorage
 *  - 跟踪"用户手动确认登录"状态（N 秒超时未识别成功时显示按钮）
 */
@HiltViewModel
class WebViewLoginViewModel @Inject constructor(
    private val sessionSaver: WebViewSessionSaver
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebViewLoginUiState())
    val uiState: StateFlow<WebViewLoginUiState> = _uiState.asStateFlow()

    fun bind(service: ServiceType) {
        val config = ServiceConfigProvider.get(service)
        _uiState.update {
            it.copy(
                service = service,
                loginUrl = config.loginUrl
            )
        }
    }

    /**
     * 成功 URL 模式匹配（计划 4.1：精确匹配 + fallback）。
     *
     * 默认启发：URL 跳转到非登录域 + 路径不包含 /auth/login/signin 等关键词，
     * 即认为登录完成。具体规则各服务可在 [ServiceConfigProvider] 扩展。
     */
    fun onPageFinished(url: String) {
        val current = _uiState.value
        if (current.loginSucceeded) return
        if (looksLikeLoggedInPage(url)) {
            saveSession(url)
        } else {
            _uiState.update { it.copy(pendingManualConfirm = true) }
        }
    }

    fun confirmLoginManually() {
        val url = _uiState.value.loginUrl
        saveSession(url)
    }

    fun dismissManualPrompt() {
        _uiState.update { it.copy(pendingManualConfirm = false) }
    }

    private fun saveSession(url: String) {
        val service = _uiState.value.service ?: return
        val saved = sessionSaver.saveFromCookieManager(service = service, url = url)
        if (saved != null) {
            _uiState.update {
                it.copy(
                    loginSucceeded = true,
                    savedSession = saved,
                    pendingManualConfirm = false
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    error = "未抓到 Cookie，请确认已登录",
                    pendingManualConfirm = true
                )
            }
        }
    }

    private fun looksLikeLoggedInPage(url: String): Boolean {
        val u = url.lowercase()
        // 简单启发：URL 不再包含登录路径关键字
        val loginKeywords = listOf("/auth", "/login", "/signin", "/oauth")
        return loginKeywords.none { u.contains(it) }
    }
}

data class WebViewLoginUiState(
    val service: ServiceType? = null,
    val loginUrl: String = "",
    val loginSucceeded: Boolean = false,
    val savedSession: Credential.SessionCredential? = null,
    val pendingManualConfirm: Boolean = false,
    val error: String? = null
)