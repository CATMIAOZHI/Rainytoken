package com.rainy.token.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.repository.CommandCodeGoRepository
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.OpenCodeGoRepository
import com.rainy.token.data.repository.OllamaRepository
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.domain.model.CookieEntry
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.FetchMethod
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Provider

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
    private val openCodeGoRepositoryProvider: Provider<OpenCodeGoRepository>,
    private val commandCodeGoRepositoryProvider: Provider<CommandCodeGoRepository>,
    private val refreshBalanceUseCaseProvider: Provider<RefreshBalanceUseCase>,
    private val ollamaRepositoryProvider: Provider<OllamaRepository>
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
            _uiState.update { it.copy(message = "API Key 不能为空") }
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
            // 同步回写 UI 状态（去掉前后空白后的版本），避免下次进来还看到带空格的旧值
            _uiState.update {
                it.copy(
                    apiKey = trimmedKey,
                    message = "已保存",
                    hasExisting = true
                )
            }
        }
    }

    /**
     * 保存 API Key 后立即测试连通性（计划 2.3 凭据有效性校验）。
     * 复用 RefreshBalanceUseCase，成功/失败都把详细信息写进 message。
     */
    /**
     * 通用模板：保存凭据 → 测试连接 → 失败按需回滚。
     *
     * @param type 服务类型
     * @param saveAndPrep 保存新凭据，返回 (旧凭据用于回滚, 测试 suspend block)
     * @param formatSuccess 成功消息格式化
     * @param rollbackOnFailure 失败时是否回滚到旧凭据
     */
    private suspend fun testAndRollback(
        type: ServiceType,
        saveAndPrep: suspend () -> Pair<Credential?, suspend () -> Result<com.rainy.token.domain.model.ServiceBalance>>,
        formatSuccess: (com.rainy.token.domain.model.ServiceBalance) -> String,
        rollbackOnFailure: Boolean
    ) {
        val (previous, testBlock) = saveAndPrep()
        val result = testBlock()
        if (result.isSuccess) {
            val bal = result.getOrNull()
            _uiState.update { it.copy(message = formatSuccess(bal!!), hasExisting = true) }
        } else {
            if (rollbackOnFailure) {
                if (previous != null) credentialRepository.save(previous) else credentialRepository.remove(type)
            }
            val err = result.exceptionOrNull()
            val reason = when (err) {
                is RepositoryError.InvalidCredential -> "服务拒绝该凭据 (401/403)"
                is RepositoryError.RateLimited -> "请求过于频繁 (429)"
                is RepositoryError.ServerError -> "服务端错误 (${err.code})"
                is RepositoryError.Network -> "网络错误：${err.cause?.message ?: "未知"}"
                else -> err?.message ?: "未知错误"
            }
            _uiState.update {
                it.copy(message = "测试失败。$reason", hasExisting = if (rollbackOnFailure) previous != null else true)
            }
        }
    }

    fun testAndSaveApiKey() {
        val type = serviceType ?: return
        if (type != ServiceType.DEEPSEEK && type != ServiceType.COMMANDCODE_GO && type != ServiceType.CODEX) {
            _uiState.update { it.copy(message = "暂不支持测试此服务") }
            return
        }
        val current = _uiState.value
        val trimmedKey = current.apiKey.trim()
        if (trimmedKey.isBlank()) {
            _uiState.update { it.copy(message = "API Key 不能为空") }
            return
        }
        viewModelScope.launch {
            val existing = credentialRepository.get(type) as? Credential.ApiKeyCredential
            val updated = (existing ?: Credential.ApiKeyCredential(
                service = type, key = trimmedKey, lastVerifiedAt = 0L
            )).copy(key = trimmedKey)
            credentialRepository.save(updated)
            _uiState.update { it.copy(apiKey = trimmedKey) }
            testAndRollback(
                type = type,
                saveAndPrep = { existing to { refreshBalanceUseCaseProvider.get().invoke(type) } },
                formatSuccess = { bal -> "连接成功！余额: ${bal.amount} ${bal.unit}" },
                rollbackOnFailure = false
            )
        }
    }

    /** 把 API Key 缩成 'sk-a***xyz' 这种形式，前 4 后 4，中间用 *** 代替 */
    private fun maskedKeyPreview(key: String): String {
        if (key.length <= 8) return "*** (长度 ${key.length}) ***"
        val head = key.take(4)
        val tail = key.takeLast(4)
        return "$head***$tail (长度 ${key.length})"
    }

    /**
     * 保存 OpenCode Go 的 cookie + workspaceId。
     */
    fun saveOpenCodeGoSession() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.authCookie.isBlank() || current.workspaceId.isBlank()) {
            _uiState.update { it.copy(message = "auth cookie 和 workspaceId 都需要填写") }
            return
        }
        viewModelScope.launch {
            doSaveOpenCodeGo(current.workspaceId.trim(), current.authCookie.trim())
            _uiState.update { it.copy(message = "已保存凭据", hasExisting = true) }
        }
    }

    /**
     * 保存并立即测试连接。测试失败则回滚凭据。
     */
    fun testAndSaveOpenCodeGo() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.authCookie.isBlank() || current.workspaceId.isBlank()) {
            _uiState.update { it.copy(message = "auth cookie 和 workspaceId 都需要填写") }
            return
        }
        viewModelScope.launch {
            val previous = credentialRepository.get(type)
            doSaveOpenCodeGo(current.workspaceId.trim(), current.authCookie.trim())
            testAndRollback(
                type = type,
                saveAndPrep = { previous to { openCodeGoRepositoryProvider.get().fetchBalance() } },
                formatSuccess = { "连接成功，凭据已保存" },
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
            _uiState.update { it.copy(message = "Cookie 不能为空") }
            return
        }
        val cookies = parseCookieString(current.cookieInput)
        if (cookies.isEmpty()) {
            _uiState.update { it.copy(message = "Cookie 格式错误，应为 name1=value1; name2=value2") }
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
                it.copy(message = "已保存 ${cookies.size} 个 Cookie", hasExisting = true, cookieCount = cookies.size)
            }
        }
    }

    fun updateCodexAuthJson(value: String) {
        _uiState.update { it.copy(codexAuthJson = value) }
    }

    /**
     * 保存 Codex auth.json 完整内容，解析并存储为 CodexCredential。
     * 支持两种格式：
     * 1. 完整 {"tokens": {"access_token": "...", "refresh_token": "...", ...}}
     * 2. 扁平 {"access_token": "...", "refresh_token": "...", ...}
     */
    fun saveCodexAuthJson() {
        val type = serviceType ?: return
        val current = _uiState.value
        val text = current.codexAuthJson.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(message = "请粘贴 auth.json 内容") }
            return
        }
        viewModelScope.launch {
            try {
                val parsed = Json.parseToJsonElement(text).jsonObject
                val tokens = parsed["tokens"]?.jsonObject ?: parsed
                val accessToken = tokens["access_token"]?.jsonPrimitive?.content
                val refreshToken = tokens["refresh_token"]?.jsonPrimitive?.content
                val accountId = tokens["account_id"]?.jsonPrimitive?.content ?: ""
                // 支持三种过期时间格式：
                //   expiresAt / expires_at → epoch 毫秒（绝对时间）
                //   expires_in → 相对秒数（token 有效期），转为 now + seconds*1000
                //   无该字段 → 默认 10 天后过期（假定 token 尚未到期）
                val expiresAt = tokens["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: tokens["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: tokens["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000L }
                    ?: System.currentTimeMillis() + 10L * 24 * 3600 * 1000

                if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
                    _uiState.update { it.copy(message = "auth.json 缺少 access_token 或 refresh_token") }
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
                _uiState.update { it.copy(message = "已保存 Codex 凭据，token 到期后会自动刷新", hasExisting = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "解析失败：${e.message ?: "JSON 格式错误"}") }
            }
        }
    }

    /**
     * 保存 Codex auth.json 并立即测试连接。
     * API Key 存 token 字段，cookie 字符串解析后存 cookies 列表。
     */
    fun saveCommandCodeGoCredential() {
        val type = serviceType ?: return
        val current = _uiState.value
        val trimmedKey = current.apiKey.trim()
        if (trimmedKey.isBlank()) {
            _uiState.update { it.copy(message = "API Key 不能为空") }
            return
        }
        val cookies = if (current.cookieInput.isNotBlank()) {
            parseCookieString(current.cookieInput)
        } else emptyList()

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
                    message = "已保存凭据",
                    hasExisting = true,
                    cookieCount = cookies.size
                )
            }
        }
    }

    /**
     * 保存并测试 CommandCode Go 连通性。
     */
    fun testAndSaveCommandCodeGo() {
        val type = serviceType ?: return
        val current = _uiState.value
        val trimmedKey = current.apiKey.trim()
        if (trimmedKey.isBlank()) {
            _uiState.update { it.copy(message = "API Key 不能为空") }
            return
        }
        viewModelScope.launch {
            val cookies = if (current.cookieInput.isNotBlank()) {
                parseCookieString(current.cookieInput)
            } else emptyList()
            val existing = credentialRepository.get(type) as? Credential.SessionCredential
            val updated = (existing ?: Credential.SessionCredential(
                service = type, cookies = cookies, token = trimmedKey
            )).copy(cookies = cookies, token = trimmedKey, lastVerifiedAt = System.currentTimeMillis())
            credentialRepository.save(updated)
            _uiState.update { it.copy(apiKey = trimmedKey) }
            testAndRollback(
                type = type,
                saveAndPrep = { existing to { refreshBalanceUseCaseProvider.get().invoke(type) } },
                formatSuccess = { bal -> "连接成功！余额: \$${String.format(java.util.Locale.US, "%.2f", bal.amount)}" },
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

    /**
     * 保存触发用量 API Key（OCGO / Ollama 共用）。
     * 合并保存到已有的 SessionCredential 中。
     */
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
                    message = "API Key 已保存",
                    hasExisting = true
                )
            }
        }
    }

    /**
     * 保存 Ollama Pro 的 Cookie 字符串。
     */
    fun saveOllamaCredential() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.ollamaCookie.isBlank()) {
            _uiState.update { it.copy(message = "Cookie 不能为空") }
            return
        }
        viewModelScope.launch {
            doSaveOllama(current.ollamaCookie.trim())
            _uiState.update { it.copy(message = "已保存凭据", hasExisting = true) }
        }
    }

    /**
     * 保存并立即测试 Ollama Cloud 连接。
     */
    fun testAndSaveOllama() {
        val type = serviceType ?: return
        val current = _uiState.value
        if (current.ollamaCookie.isBlank()) {
            _uiState.update { it.copy(message = "Cookie 不能为空") }
            return
        }
        viewModelScope.launch {
            val previous = credentialRepository.get(type)
            doSaveOllama(current.ollamaCookie.trim())
            testAndRollback(
                type = type,
                saveAndPrep = { previous to { ollamaRepositoryProvider.get().fetchBalance() } },
                formatSuccess = { bal -> "连接成功！Session: ${bal.amount}% · ${bal.extras["plan"] ?: "—"}" },
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
                    message = "凭据已删除",
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
            _uiState.update { it.copy(message = "剪贴板为空") }
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
            ws != null && auth != null -> _uiState.update { it.copy(message = "已识别 workspaceId 和 auth cookie") }
            ws != null -> _uiState.update { it.copy(message = "已识别 workspaceId，请再粘贴 auth cookie") }
            auth != null -> _uiState.update { it.copy(message = "已识别 auth cookie，请再粘贴 workspaceId") }
            else -> _uiState.update { it.copy(message = "未识别到有效凭据") }
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
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
                // cookie 值原样存储，不做任何解码（better-auth 签名包含了原始字符）
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
    val message: String? = null
)