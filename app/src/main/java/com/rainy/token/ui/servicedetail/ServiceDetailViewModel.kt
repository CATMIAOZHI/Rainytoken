package com.rainy.token.ui.servicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.CachedBalance
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.data.repository.TriggerError
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 服务详情页 ViewModel。计划 3.3：UiState = Loading | Fresh | Stale | Error
 * 阶段 3 实现：DeepSeek 的真实刷新。其他服务调用 RefreshBalanceUseCase 会得到
 * UnsupportedServiceException，进入"暂未支持"提示状态。
 */
@HiltViewModel
class ServiceDetailViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache,
    private val refreshBalanceUseCase: RefreshBalanceUseCase
) : ViewModel() {

    private val _serviceType = MutableStateFlow<ServiceType?>(null)
    private val _uiState = MutableStateFlow(ServiceDetailUiState())
    val uiState: StateFlow<ServiceDetailUiState> = _uiState.asStateFlow()

    /** Codex 一键激活用量状态 */
    private val _triggerState = MutableStateFlow<TriggerState>(TriggerState.Idle)
    val triggerState: StateFlow<TriggerState> = _triggerState.asStateFlow()

    /** 可用模型列表（Codex / OCGO / Ollama 共用） */
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    /** 用户选中的模型（持久化） */
    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()

    /** 模型列表加载状态 */
    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    companion object {
        private const val CODEX_PREFS = "codex_trigger_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_MODELS_CACHE = "models_cache"
        private const val OCGO_PREFS = "ocgo_trigger_prefs"
        private const val OLLAMA_PREFS = "ollama_trigger_prefs"
    }

    private fun prefsNameFor(service: ServiceType): String = when (service) {
        ServiceType.CODEX -> CODEX_PREFS
        ServiceType.OPENCODE_GO -> OCGO_PREFS
        ServiceType.OLLAMA -> OLLAMA_PREFS
        else -> CODEX_PREFS
    }

    fun bind(service: ServiceType) {
        if (_serviceType.value == service) return
        _serviceType.value = service
        // 恢复持久化状态
        if (service == ServiceType.CODEX || service == ServiceType.OPENCODE_GO || service == ServiceType.OLLAMA) {
            val savedModel = loadSelectedModel(service)
            if (savedModel != null) _selectedModel.value = savedModel
            val cachedModels = loadModelsCache(service)
            if (cachedModels.isNotEmpty()) _models.value = cachedModels
        }
        loadFromCache()
    }

    /**
     * 重新读取凭据状态 + 缓存（不发起网络请求）。
     * 用于从凭据编辑页返回时同步配置变更。
     */
    fun reloadCredentialState() {
        loadFromCache()
    }

    fun refresh() {
        val type = _serviceType.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(state = State.Loading) }

            // 手动输入模式：直接展示用户上次输入的余额（如果有）
            val config = com.rainy.token.domain.service.ServiceConfigProvider.get(type)
            if (config.method == com.rainy.token.domain.service.FetchMethod.MANUAL) {
                val cached = balanceCache.get(type)
                _uiState.update {
                    it.copy(
                        state = if (cached != null) {
                            State.Fresh(cached.balance)
                        } else {
                            State.ManualModeHint
                        }
                    )
                }
                return@launch
            }

            val result = refreshBalanceUseCase(type)
            result
                .onSuccess { balance -> _uiState.update { it.copy(state = State.Fresh(balance)) } }
                .onFailure { error ->
                    val cached = balanceCache.get(type)
                    _uiState.update {
                        it.copy(
                            state = State.Error(
                                cached = cached?.balance,
                                message = errorMessage(error),
                                error = error
                            )
                        )
                    }
                }
        }
    }

    /**
     * 加载可用模型列表（优先从缓存读取，首次才请求网络）。
     * @param force true 时强制从网络刷新
     */
    fun loadModels(force: Boolean = false) {
        val service = _serviceType.value ?: return
        if (!force && _models.value.isNotEmpty()) return
        viewModelScope.launch {
            _modelsLoading.value = true
            val result = when (service) {
                ServiceType.CODEX -> refreshBalanceUseCase.fetchCodexModels()
                ServiceType.OPENCODE_GO -> refreshBalanceUseCase.fetchOpenCodeGoModels()
                ServiceType.OLLAMA -> refreshBalanceUseCase.fetchOllamaModels()
                else -> Result.failure(RepositoryError.Unknown(IllegalArgumentException("不支持模型列表")))
            }
            result
                .onSuccess { list ->
                    _models.value = list
                    persistModelsCache(service, list)
                    val current = _selectedModel.value
                    if (current == null || !list.contains(current)) {
                        _selectedModel.value = list.firstOrNull()
                        persistSelectedModel(service, list.firstOrNull())
                    }
                }
                .onFailure { error ->
                    DebugLog.e(service.displayName, "加载模型列表失败: ${error.message}")
                    if (_models.value.isEmpty()) {
                        val cached = loadModelsCache(service)
                        if (cached.isNotEmpty()) {
                            _models.value = cached
                            if (_selectedModel.value == null) {
                                _selectedModel.value = cached.firstOrNull()
                            }
                        }
                    }
                }
            _modelsLoading.value = false
        }
    }

    /** 用户选择模型（持久化） */
    fun selectModel(model: String) {
        _selectedModel.value = model
        _serviceType.value?.let { persistSelectedModel(it, model) }
    }

    /**
     * 一键激活用量：发送 API 请求触发用量统计。
     * 支持 Codex / OpenCode Go / Ollama 三个服务。
     */
    fun triggerUsage() {
        val service = _serviceType.value ?: return
        val model = _selectedModel.value ?: run {
            _triggerState.value = TriggerState.Error("请先选择模型", null)
            return
        }
        viewModelScope.launch {
            _triggerState.value = TriggerState.Loading
            val result = when (service) {
                ServiceType.CODEX -> refreshBalanceUseCase.triggerCodexUsage(model)
                ServiceType.OPENCODE_GO -> refreshBalanceUseCase.triggerOpenCodeGoUsage(model)
                ServiceType.OLLAMA -> refreshBalanceUseCase.triggerOllamaUsage(model)
                else -> Result.failure(RepositoryError.Unknown(IllegalArgumentException("不支持激活用量")))
            }
            result
                .onSuccess { responseBody ->
                    _triggerState.value = TriggerState.Success(responseBody)
                    // 等待 2 秒让服务端处理用量，再刷新余额
                    kotlinx.coroutines.delay(2000)
                    refresh()
                }
                .onFailure { error ->
                    val msg: String
                    val respBody: String?
                    when (error) {
                        is TriggerError -> {
                            msg = error.summary
                            respBody = error.responseBody.ifBlank { null }
                        }
                        is RepositoryError.InvalidCredential -> {
                            msg = error.message ?: "凭据无效，请重新登录"
                            respBody = null
                        }
                        is RepositoryError.Network -> {
                            msg = "网络异常"
                            respBody = null
                        }
                        else -> {
                            msg = error.message ?: "未知错误"
                            respBody = null
                        }
                    }
                    _triggerState.value = TriggerState.Error(msg, respBody)
                }
        }
    }

    /** 关闭响应弹窗，回到 Idle */
    fun dismissTrigger() {
        _triggerState.value = TriggerState.Idle
    }

    /**
     * 手动输入模式：保存用户填的余额值。
     */
    fun saveManualBalance(amount: Double) {
        val type = _serviceType.value ?: return
        viewModelScope.launch {
            val config = com.rainy.token.domain.service.ServiceConfigProvider.get(type)
            val balance = com.rainy.token.domain.model.ServiceBalance(
                service = type,
                amount = amount,
                unit = config.displayUnit,
                isAvailable = true
            )
            balanceCache.put(type, balance)
            _uiState.update { it.copy(state = State.Fresh(balance)) }
        }
    }

    fun markVerified() {
        val type = _serviceType.value ?: return
        viewModelScope.launch {
            val credential = credentialRepository.get(type) ?: return@launch
            val updated = when (credential) {
                is com.rainy.token.domain.model.Credential.ApiKeyCredential ->
                    credential.copy(lastVerifiedAt = System.currentTimeMillis())
                is com.rainy.token.domain.model.Credential.SessionCredential ->
                    credential.copy(lastVerifiedAt = System.currentTimeMillis())
                is com.rainy.token.domain.model.Credential.CodexCredential ->
                    credential.copy(lastVerifiedAt = System.currentTimeMillis())
            }
            credentialRepository.save(updated)
            loadFromCache()
        }
    }

    private fun loadFromCache() {
        val type = _serviceType.value ?: return
        viewModelScope.launch {
            val status = credentialRepository.statusFor(type)
            val cached = balanceCache.get(type)
            val config = com.rainy.token.domain.service.ServiceConfigProvider.get(type)
            val isManual = config.method == com.rainy.token.domain.service.FetchMethod.MANUAL
            val newState: State = when {
                isManual && cached != null -> State.Stale(cached.balance, cached.fetchedAt)
                isManual -> State.ManualModeHint
                cached != null && status.state == CredentialStatus.State.OK -> State.Stale(cached.balance, cached.fetchedAt)
                cached != null -> State.Error(cached.balance, "凭据未配置或已过期", RepositoryError.InvalidCredential())
                else -> State.Loading
            }
            _uiState.update {
                it.copy(
                    hasCredential = status.state != CredentialStatus.State.NOT_CONFIGURED,
                    cached = cached,
                    state = newState
                )
            }
            if (!isManual && status.state != CredentialStatus.State.NOT_CONFIGURED && cached == null) {
                refresh()
            }
        }
    }

    private fun errorMessage(error: Throwable): String = when (error) {
        is RepositoryError.InvalidCredential -> "凭据无效，请在设置中重新配置"
        is RepositoryError.RateLimited -> "请求过于频繁${error.retryAfterSeconds?.let { "，请 ${it} 秒后重试" } ?: ""}"
        is RepositoryError.Network -> "网络异常，请检查网络"
        is RepositoryError.ServerError -> "服务端异常 (HTTP ${error.code})"
        is RepositoryError.ParseError -> "数据解析失败: ${error.message}"
        else -> error.message ?: "未知错误"
    }

    // ── 模型持久化（Codex / OCGO / Ollama 各自独立 prefs） ──

    private fun prefs(service: ServiceType): android.content.SharedPreferences =
        com.rainy.token.RainyTokenApplication.appContext.getSharedPreferences(prefsNameFor(service), android.content.Context.MODE_PRIVATE)

    private fun persistSelectedModel(service: ServiceType, model: String?) {
        prefs(service).edit().putString(KEY_SELECTED_MODEL, model).apply()
    }

    private fun loadSelectedModel(service: ServiceType): String? = prefs(service).getString(KEY_SELECTED_MODEL, null)

    private fun persistModelsCache(service: ServiceType, models: List<String>) {
        prefs(service).edit().putString(KEY_MODELS_CACHE, models.joinToString("\n")).apply()
    }

    private fun loadModelsCache(service: ServiceType): List<String> =
        prefs(service).getString(KEY_MODELS_CACHE, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
}

/**
 * 计划 7.1 规定的 UI 状态。
 */
sealed class State {
    data object Loading : State()
    data class Fresh(val data: ServiceBalance) : State()
    data class Stale(val data: ServiceBalance, val lastFetchedAt: Long) : State()
    data class Error(
        val cached: ServiceBalance?,
        val message: String,
        val error: Throwable
    ) : State()
    /** 手动输入模式：尚未填入任何余额 */
    data object ManualModeHint : State()
}

data class ServiceDetailUiState(
    val state: State = State.Loading,
    val hasCredential: Boolean = false,
    val cached: CachedBalance? = null
)

/** Codex 一键激活用量状态 */
sealed class TriggerState {
    data object Idle : TriggerState()
    data object Loading : TriggerState()
    data class Success(val responseBody: String) : TriggerState()
    data class Error(val message: String, val responseBody: String?) : TriggerState()
}