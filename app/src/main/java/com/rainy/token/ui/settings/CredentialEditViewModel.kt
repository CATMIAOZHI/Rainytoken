package com.rainy.token.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.R
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.domain.model.CookieEntry
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.FetchMethod
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import com.rainy.token.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 凭据编辑页 ViewModel。
 *
 * - REST API 服务：API Key 表单保存
 * - WebView 类服务：分两种子模式
 *     - OpenCode Go：用户粘贴 `auth cookie` + `workspaceId`（自动抓取）
 */
@HiltViewModel
class CredentialEditViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val refreshBalanceUseCaseProvider: Provider<RefreshBalanceUseCase>
) : ViewModel() {

    private val _uiState = MutableStateFlow(CredentialEditUiState())
    val uiState: StateFlow<CredentialEditUiState> = _uiState.asStateFlow()

    private var serviceType: ServiceType? = null

    fun bind(service: ServiceType) {
        if (serviceType == service) return
        serviceType = service
        val config = ServiceConfigProvider.get(service)
        val isApiKey = config.method == FetchMethod.REST_API
        _uiState.update {
            it.copy(
                service = service,
                isApiKeyService = isApiKey,
                loginUrl = config.loginUrl
            )
        }
        load()
    }

    private fun load() {
        val type = serviceType ?: return
        viewModelScope.launch {
            val existing = credentialRepository.get(type)
            _uiState.update {
                it.copy(
                    apiKey = when (existing) {
                        is Credential.ApiKeyCredential -> existing.key
                        is Credential.SessionCredential -> existing.token.orEmpty()
                        else -> ""
                    },
                    cookieInput = if (existing is Credential.SessionCredential) {
                        existing.cookies.joinToString("; ") { c -> "${c.name}=${c.value}" }
                    } else "",
                    authCookie = (existing as? Credential.SessionCredential)?.authCookie.orEmpty(),
                    workspaceId = (existing as? Credential.SessionCredential)?.workspaceId.orEmpty(),
                    cookieCount = (existing as? Credential.SessionCredential)?.cookies?.size ?: 0,
                    codexAuthJson = if (existing is Credential.CodexCredential) {
                        buildString {
                            appendLine("{")
                            appendLine("  \"access_token\": \"${existing.accessToken}\",")
                            appendLine("  \"refresh_token\": \"${existing.refreshToken}\",")
                            append("  \"account_id\": \"${existing.accountId}\"")
                            if (existing.expiresAt > 0) {
                                appendLine(",")
                                append("  \"expires_at\": ${existing.expiresAt}")
                            }
                            appendLine()
                            append("}")
                        }
                    } else "",
                    ollamaCookie = (existing as? Credential.SessionCredential)?.ollamaCookie.orEmpty(),
                    triggerApiKey = (existing as? Credential.SessionCredential)?.apiKey.orEmpty(),
                    hasExisting = existing != null
                )
            }
        }
    }

    fun updateApiKey(value: String) {
        _uiState.update { it.copy(apiKey = value) }
    }

    fun updateCookieInput(value: String) {
        _uiState.update { it.copy(cookieInput = value) }
    }

    fun updateAuthCookie(value: String) {
        _uiState.update { it.copy(authCookie = value) }
    }

    fun updateWorkspaceId(value: String) {
        _uiState.update { it.copy(workspaceId = value) }
    }

    fun saveApiKey() {
        val type = serviceType ?: return
        val current = _uiState.value
        val trimmedKey = current.apiKey.trim()
        if (trimmedKey.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_api_key_empty)) }
            return
        }
        viewModelScope.launch {
            val existing = credentialRepository.get(type) as? Credential.ApiKeyCredential
            val updated = (existing ?: Credential.ApiKeyCredential(
                service = type,
                key = trimmedKey,
                lastVerifiedAt = 0L
            )).copy(key = trimmedKey)
            credentialRepository.save(updated)
            _uiState.update {
                it.copy(
                    apiKey = trimmedKey,
                    message = UiText.Resource(R.string.msg_saved),
                    hasExisting = true
                )
            }
        }
    }

    /** 通用模板：保存凭据 → 测试连接 → 失败按需回滚。 */
    private suspend fun testAndRollback(
        type: ServiceType,
        saveAndPrep: suspend () -> Pair<Credential?, suspend () -> Result<com.rainy.token.domain.model.ServiceBalance>>,
        formatSuccess: (com.rainy.token.domain.model.ServiceBalance) -> UiText,
        rollbackOnFailure: Boolean
    ) {
        val (previous, testBlock) = saveAndPrep()
        val testedSnapshot = credentialRepository.snapshot(type)
        val result = testBlock()
        if (result.isSuccess) {
            val bal = result.getOrNull()
            _uiState.update { it.copy(message = formatSuccess(bal!!), hasExisting = true) }
        } else {
            val rolledBack = if (rollbackOnFailure && testedSnapshot != null) {
                credentialRepository.restoreIfCurrent(testedSnapshot, previous)
            } else {
                false
            }
            val err = result.exceptionOrNull()
            val reason: UiText = when (err) {
                is RepositoryError.InvalidCredential ->
                    UiText.Resource(R.string.error_credential_rejected)
                is RepositoryError.CredentialChanged ->
                    UiText.Resource(R.string.error_credential_changed_test)
                is RepositoryError.RateLimited ->
                    UiText.Resource(R.string.error_rate_limited)
                is RepositoryError.ServerError ->
                    UiText.Resource(R.string.error_server, listOf(err.code))
                is RepositoryError.Network ->
                    UiText.Resource(
                        R.string.error_network,
                        listOf(
                            err.cause?.message?.let { UiText.Dynamic(it) }
                                ?: UiText.Resource(R.string.common_unknown)
                        )
                    )
                is RepositoryError.ParseError -> when (err.reason) {
                    RepositoryError.ParseErrorReason.EMPTY_BODY -> UiText.Resource(R.string.error_parse_empty_body)
                    RepositoryError.ParseErrorReason.NOT_JSON_OBJECT -> UiText.Resource(R.string.error_parse_not_json)
                    RepositoryError.ParseErrorReason.NO_WINDOWS -> UiText.Resource(R.string.error_parse_no_windows)
                    RepositoryError.ParseErrorReason.NO_MODELS -> UiText.Resource(R.string.error_parse_no_models)
                    RepositoryError.ParseErrorReason.MODELS_EMPTY -> UiText.Resource(R.string.error_parse_models_empty)
                    RepositoryError.ParseErrorReason.MALFORMED_RESPONSE -> UiText.Resource(R.string.error_parse_malformed)
                }
                // Unknown 的 message 以硬编码中文"未知错误"开头，不能透传 UI，统一映射本地化文案
                is RepositoryError.Unknown -> UiText.Resource(R.string.common_unknown)
                else -> err?.message?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.common_unknown)
            }
            val hasExisting = credentialRepository.get(type) != null
            val rollbackNote: Any = when {
                !rollbackOnFailure -> ""
                rolledBack -> UiText.Resource(R.string.error_rollback_done)
                else -> UiText.Resource(R.string.error_rollback_skipped)
            }
            _uiState.update {
                it.copy(
                    message = UiText.Resource(R.string.error_test_failed, listOf(reason, rollbackNote)),
                    hasExisting = hasExisting
                )
            }
        }
    }

    fun testAndSaveApiKey() {
        val type = serviceType ?: return
        if (type != ServiceType.DEEPSEEK && type != ServiceType.COMMANDCODE_GO && type != ServiceType.CODEX) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_test_not_supported)) }
            return
        }
        val current = _uiState.value
        val trimmedKey = current.apiKey.trim()
        if (trimmedKey.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_api_key_empty)) }
            return
        }
        viewModelScope.launch {
            val existing = credentialRepository.get(type) as? Credential.ApiKeyCredential
            val updated = (existing ?: Credential.ApiKeyCredential(
                service = type,
                key = trimmedKey,
                lastVerifiedAt = 0L
            )).copy(key = trimmedKey)
            credentialRepository.save(updated)
            _uiState.update { it.copy(apiKey = trimmedKey) }
            testAndRollback(
                type = type,
                saveAndPrep = { existing to { refreshBalanceUseCaseProvider.get().invoke(type) } },
                formatSuccess = { bal ->
                    UiText.Resource(
                        R.string.msg_connect_success_balance,
                        listOf(bal.amount.toString(), bal.unit)
                    )
                },
                rollbackOnFailure = false
            )
        }
    }

    /** 把 API Key 缩成 'sk-a***xyz' 这种形式，前 4 后 4，中间用 *** 代替。 */
    private fun maskedKeyPreview(key: String): UiText {
        if (key.length <= 8) {
            return UiText.Resource(R.string.msg_key_length, listOf(key.length))
        }
        val head = key.take(4)
        val tail = key.takeLast(4)
        return UiText.Resource(R.string.msg_key_masked, listOf(head, tail, key.length))
    }

    fun saveOpenCodeGoSession() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.authCookie.isBlank() || current.workspaceId.isBlank()) {
            _uiState.update {
                it.copy(message = UiText.Resource(R.string.error_auth_cookie_workspace))
            }
            return
        }
        viewModelScope.launch {
            doSaveOpenCodeGo(current.workspaceId.trim(), current.authCookie.trim())
            _uiState.update {
                it.copy(message = UiText.Resource(R.string.msg_credentials_saved), hasExisting = true)
            }
        }
    }

    fun testAndSaveOpenCodeGo() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.authCookie.isBlank() || current.workspaceId.isBlank()) {
            _uiState.update {
                it.copy(message = UiText.Resource(R.string.error_auth_cookie_workspace))
            }
            return
        }
        viewModelScope.launch {
            val previous = credentialRepository.get(type)
            doSaveOpenCodeGo(current.workspaceId.trim(), current.authCookie.trim())
            testAndRollback(
                type = type,
                saveAndPrep = { previous to { refreshBalanceUseCaseProvider.get().invoke(type) } },
                formatSuccess = { UiText.Resource(R.string.msg_connect_success_saved) },
                rollbackOnFailure = true
            )
        }
    }

    private suspend fun doSaveOpenCodeGo(workspaceId: String, authCookie: String) {
        val type = serviceType ?: return
        val existing = credentialRepository.get(type) as? Credential.SessionCredential
        val updated = (existing ?: Credential.SessionCredential(
            service = type,
            cookies = emptyList()
        )).copy(
            authCookie = authCookie,
            workspaceId = workspaceId,
            lastVerifiedAt = System.currentTimeMillis()
        )
        credentialRepository.save(updated)
    }

    fun saveCookies() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.cookieInput.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_cookie_empty)) }
            return
        }
        val cookies = parseCookieString(current.cookieInput)
        if (cookies.isEmpty()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_cookie_format)) }
            return
        }
        viewModelScope.launch {
            val existing = credentialRepository.get(type) as? Credential.SessionCredential
            val updated = (existing ?: Credential.SessionCredential(
                service = type,
                cookies = cookies
            )).copy(
                cookies = cookies,
                lastVerifiedAt = System.currentTimeMillis()
            )
            credentialRepository.save(updated)
            _uiState.update {
                it.copy(
                    message = UiText.Resource(R.string.msg_cookies_saved, listOf(cookies.size)),
                    hasExisting = true,
                    cookieCount = cookies.size
                )
            }
        }
    }

    fun updateCodexAuthJson(value: String) {
        _uiState.update { it.copy(codexAuthJson = value) }
    }

    fun saveCodexAuthJson() {
        val type = serviceType ?: return
        val current = _uiState.value
        val text = current.codexAuthJson.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_auth_json_empty)) }
            return
        }
        viewModelScope.launch {
            try {
                val parsed = Json.parseToJsonElement(text).jsonObject
                val tokens = parsed["tokens"]?.jsonObject ?: parsed
                val accessToken = tokens["access_token"]?.jsonPrimitive?.content
                val refreshToken = tokens["refresh_token"]?.jsonPrimitive?.content
                val accountId = tokens["account_id"]?.jsonPrimitive?.content ?: ""
                val expiresAt = tokens["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: tokens["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: tokens["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                        System.currentTimeMillis() + it * 1000L
                    }
                    ?: System.currentTimeMillis() + 10L * 24 * 3600 * 1000

                if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(message = UiText.Resource(R.string.error_auth_json_missing_tokens))
                    }
                    return@launch
                }

                val newCred = Credential.CodexCredential(
                    service = ServiceType.CODEX,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = accountId,
                    expiresAt = expiresAt,
                    lastVerifiedAt = System.currentTimeMillis()
                )
                credentialRepository.save(newCred)
                _uiState.update {
                    it.copy(
                        message = UiText.Resource(R.string.msg_codex_saved),
                        hasExisting = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        message = UiText.Resource(
                            R.string.error_auth_json_parse,
                            listOf(
                                e.message?.let { UiText.Dynamic(it) }
                                    ?: UiText.Resource(R.string.error_json_format)
                            )
                        )
                    )
                }
            }
        }
    }

    fun saveCommandCodeGoCredential() {
        val type = serviceType ?: return
        val current = _uiState.value
        val trimmedKey = current.apiKey.trim()
        if (trimmedKey.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_api_key_empty)) }
            return
        }
        val cookies = if (current.cookieInput.isNotBlank()) {
            parseCookieString(current.cookieInput)
        } else {
            emptyList()
        }

        viewModelScope.launch {
            val existing = credentialRepository.get(type) as? Credential.SessionCredential
            val updated = (existing ?: Credential.SessionCredential(
                service = type,
                cookies = cookies,
                token = trimmedKey
            )).copy(
                cookies = cookies,
                token = trimmedKey,
                lastVerifiedAt = System.currentTimeMillis()
            )
            credentialRepository.save(updated)
            _uiState.update {
                it.copy(
                    apiKey = trimmedKey,
                    message = UiText.Resource(R.string.msg_credentials_saved),
                    hasExisting = true,
                    cookieCount = cookies.size
                )
            }
        }
    }

    fun testAndSaveCommandCodeGo() {
        val type = serviceType ?: return
        val current = _uiState.value
        val trimmedKey = current.apiKey.trim()
        if (trimmedKey.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_api_key_empty)) }
            return
        }
        viewModelScope.launch {
            val cookies = if (current.cookieInput.isNotBlank()) {
                parseCookieString(current.cookieInput)
            } else {
                emptyList()
            }
            val existing = credentialRepository.get(type) as? Credential.SessionCredential
            val updated = (existing ?: Credential.SessionCredential(
                service = type,
                cookies = cookies,
                token = trimmedKey
            )).copy(
                cookies = cookies,
                token = trimmedKey,
                lastVerifiedAt = System.currentTimeMillis()
            )
            credentialRepository.save(updated)
            _uiState.update { it.copy(apiKey = trimmedKey) }
            testAndRollback(
                type = type,
                saveAndPrep = { existing to { refreshBalanceUseCaseProvider.get().invoke(type) } },
                formatSuccess = { bal ->
                    UiText.Resource(
                        R.string.msg_connect_success_dollar,
                        listOf(String.format(java.util.Locale.US, "%.2f", bal.amount))
                    )
                },
                rollbackOnFailure = false
            )
        }
    }

    fun updateOllamaCookie(value: String) {
        _uiState.update { it.copy(ollamaCookie = value) }
    }

    fun updateTriggerApiKey(value: String) {
        _uiState.update { it.copy(triggerApiKey = value) }
    }

    fun saveTriggerApiKey() {
        val type = serviceType ?: return
        val current = _uiState.value
        val trimmedKey = current.triggerApiKey.trim()
        viewModelScope.launch {
            val existing = credentialRepository.get(type) as? Credential.SessionCredential
            val updated = (existing ?: Credential.SessionCredential(
                service = type,
                cookies = emptyList()
            )).copy(
                apiKey = trimmedKey.ifBlank { null },
                lastVerifiedAt = System.currentTimeMillis()
            )
            credentialRepository.save(updated)
            _uiState.update {
                it.copy(
                    triggerApiKey = trimmedKey,
                    message = UiText.Resource(R.string.msg_api_key_saved),
                    hasExisting = true
                )
            }
        }
    }

    fun saveOllamaCredential() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.ollamaCookie.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_cookie_empty)) }
            return
        }
        viewModelScope.launch {
            doSaveOllama(current.ollamaCookie.trim())
            _uiState.update {
                it.copy(message = UiText.Resource(R.string.msg_credentials_saved), hasExisting = true)
            }
        }
    }

    fun testAndSaveOllama() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.ollamaCookie.isBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_cookie_empty)) }
            return
        }
        viewModelScope.launch {
            val previous = credentialRepository.get(type)
            doSaveOllama(current.ollamaCookie.trim())
            testAndRollback(
                type = type,
                saveAndPrep = { previous to { refreshBalanceUseCaseProvider.get().invoke(type) } },
                formatSuccess = { bal ->
                    UiText.Resource(
                        R.string.msg_connect_success_session,
                        listOf(bal.amount.toString(), bal.extras["plan"]?.takeIf { it.isNotBlank() } ?: "—")
                    )
                },
                rollbackOnFailure = true
            )
        }
    }

    private suspend fun doSaveOllama(cookie: String) {
        val type = serviceType ?: return
        val existing = credentialRepository.get(type) as? Credential.SessionCredential
        val updated = (existing ?: Credential.SessionCredential(
            service = type,
            cookies = emptyList()
        )).copy(
            ollamaCookie = cookie,
            lastVerifiedAt = System.currentTimeMillis()
        )
        credentialRepository.save(updated)
    }

    fun deleteCredential() {
        val type = serviceType ?: return
        viewModelScope.launch {
            credentialRepository.remove(type)
            _uiState.update {
                it.copy(
                    message = UiText.Resource(R.string.msg_credential_deleted),
                    hasExisting = false,
                    apiKey = "",
                    cookieInput = "",
                    authCookie = "",
                    workspaceId = "",
                    cookieCount = 0,
                    ollamaCookie = "",
                    triggerApiKey = ""
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun importFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            _uiState.update { it.copy(message = UiText.Resource(R.string.error_clipboard_empty)) }
            return
        }
        val ws = extractWorkspaceId(text)
        val auth = extractAuthCookie(text)
        _uiState.update { state ->
            state.copy(
                workspaceId = ws ?: state.workspaceId,
                authCookie = auth ?: state.authCookie
            )
        }
        when {
            ws != null && auth != null ->
                _uiState.update { it.copy(message = UiText.Resource(R.string.msg_clipboard_ws_auth)) }
            ws != null ->
                _uiState.update { it.copy(message = UiText.Resource(R.string.msg_clipboard_ws_only)) }
            auth != null ->
                _uiState.update { it.copy(message = UiText.Resource(R.string.msg_clipboard_auth_only)) }
            else ->
                _uiState.update { it.copy(message = UiText.Resource(R.string.error_clipboard_none)) }
        }
    }

    private fun extractWorkspaceId(text: String): String? {
        Regex("""https?:\/\/opencode\.ai\/workspace\/([a-zA-Z0-9_]+)\/go""").find(text)
            ?.groupValues?.get(1)?.let { return it }
        Regex("""workspace\/([a-zA-Z0-9_]+)\/go""").find(text)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun extractAuthCookie(text: String): String? {
        Regex("""auth=([^;\s]+)""").find(text)?.groupValues?.get(1)?.let { return it }
        Regex(""""name"\s*:\s*"auth"[^}]*"value"\s*:\s*"([^"]+)"""").find(text)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun parseCookieString(cookieString: String): List<CookieEntry> {
        return cookieString.split(";")
            .mapNotNull { entry ->
                val parts = entry.trim().split("=", limit = 2)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    return@mapNotNull null
                }
                CookieEntry(name = parts[0].trim(), value = parts[1].trim())
            }
    }
}

data class CredentialEditUiState(
    val service: ServiceType? = null,
    val isApiKeyService: Boolean = false,
    val loginUrl: String = "",
    val apiKey: String = "",
    val cookieInput: String = "",
    val authCookie: String = "",
    val workspaceId: String = "",
    val cookieCount: Int = 0,
    val hasExisting: Boolean = false,
    val codexAuthJson: String = "",
    val ollamaCookie: String = "",
    /** OCGO / Ollama 的一键激活用量 API Key */
    val triggerApiKey: String = "",
    val message: UiText? = null
)
