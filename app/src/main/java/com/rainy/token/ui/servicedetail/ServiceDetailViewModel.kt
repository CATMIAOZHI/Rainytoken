package com.rainy.token.ui.servicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.CachedBalance
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.data.repository.TriggerError
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 服务详情页 ViewModel。 */
@HiltViewModel
class ServiceDetailViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache,
    private val refreshBalanceUseCase: RefreshBalanceUseCase
) : ViewModel() {

    private val _serviceType = MutableStateFlow<ServiceType?>(null)
    private val _uiState = MutableStateFlow(ServiceDetailUiState())
    val uiState: StateFlow<ServiceDetailUiState> = _uiState.asStateFlow()

    private val _triggerState = MutableStateFlow<TriggerState>(TriggerState.Idle)
    val triggerState: StateFlow<TriggerState> = _triggerState.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    /** 当前凭据的不可逆 SHA-256 指纹。 */
    private var currentCredentialFingerprint: String? = null

    /** 每次发起刷新 +1；凭据变化时旧刷新结果会被丢弃。 */
    private var refreshGeneration: Int = 0

    /** 每次切换服务 +1；防止宽屏快速切换时旧服务协程覆盖新服务 UI。 */
    private var serviceGeneration: Int = 0

    companion object {
        private const val CODEX_PREFS = "codex_trigger_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_MODELS_CACHE = "models_cache"
        private const val OCGO_PREFS = "ocgo_trigger_prefs"
        private const val OLLAMA_PREFS = "ollama_trigger_prefs"

        internal fun credentialFingerprint(credential: Credential?): String? =
            CredentialRepository.credentialFingerprint(credential)

        enum class CredentialChange { UNCHANGED, NEW, REPLACED, DELETED, NONE_TO_NONE }

        internal fun classifyCredentialChange(
            oldFingerprint: String?,
            newFingerprint: String?
        ): CredentialChange = when {
            oldFingerprint == null && newFingerprint == null -> CredentialChange.NONE_TO_NONE
            oldFingerprint == null && newFingerprint != null -> CredentialChange.NEW
            oldFingerprint != null && newFingerprint == null -> CredentialChange.DELETED
            oldFingerprint == newFingerprint -> CredentialChange.UNCHANGED
            else -> CredentialChange.REPLACED
        }
    }

    private fun prefsNameFor(service: ServiceType): String = when (service) {
        ServiceType.CODEX -> CODEX_PREFS
        ServiceType.OPENCODE_GO -> OCGO_PREFS
        ServiceType.OLLAMA -> OLLAMA_PREFS
        else -> CODEX_PREFS
    }

    fun bind(service: ServiceType) {
        if (_serviceType.value == service) return

        serviceGeneration++
        refreshGeneration++
        _serviceType.value = service
        currentCredentialFingerprint = null
        _models.value = emptyList()
        _selectedModel.value = null
        _modelsLoading.value = false
        _triggerState.value = TriggerState.Idle

        if (service == ServiceType.CODEX || service == ServiceType.OPENCODE_GO || service == ServiceType.OLLAMA) {
            loadSelectedModel(service)?.let { _selectedModel.value = it }
            loadModelsCache(service).takeIf { it.isNotEmpty() }?.let { _models.value = it }
        }
        loadFromCache()
    }

    /**
     * 返回页面时同步凭据和缓存。凭据未变时保留错误/Loading，但按 fetchedAt 采用
     * Widget 或其他入口写入的更新缓存；凭据新增或替换时废弃旧状态并重新刷新。
     */
    fun reloadCredentialState() {
        val type = _serviceType.value ?: return
        val serviceGen = serviceGeneration
        viewModelScope.launch {
            val local = credentialRepository.readLocalState(type)
            val newFingerprint = local.fingerprint
            val change = classifyCredentialChange(currentCredentialFingerprint, newFingerprint)
            val newHasCredential = local.status.state != CredentialStatus.State.NOT_CONFIGURED

            if (serviceGen != serviceGeneration || _serviceType.value != type) return@launch
            currentCredentialFingerprint = newFingerprint

            when (change) {
                CredentialChange.NONE_TO_NONE, CredentialChange.UNCHANGED -> {
                    _uiState.update { current ->
                        mergeUnchangedLocalState(
                            current = current,
                            localCache = local.cachedBalance,
                            hasCredential = newHasCredential
                        )
                    }
                }

                CredentialChange.NEW, CredentialChange.REPLACED -> {
                    _uiState.update {
                        it.copy(
                            hasCredential = true,
                            cached = local.cachedBalance,
                            state = State.Loading
                        )
                    }
                    refresh()
                }

                CredentialChange.DELETED -> {
                    refreshGeneration++
                    _uiState.update {
                        it.copy(
                            hasCredential = false,
                            cached = local.cachedBalance,
                            state = State.Error(
                                local.cachedBalance?.balance,
                                "凭据未配置",
                                RepositoryError.InvalidCredential()
                            )
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        val type = _serviceType.value ?: return
        val serviceGen = serviceGeneration
        val gen = ++refreshGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(state = State.Loading) }

            val config = com.rainy.token.domain.service.ServiceConfigProvider.get(type)
            if (config.method == com.rainy.token.domain.service.FetchMethod.MANUAL) {
                val cached = balanceCache.get(type)
                if (isStaleRequest(type, serviceGen, gen)) return@launch
                _uiState.update {
                    it.copy(
                        cached = newerOf(it.cached, cached),
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
            if (isStaleRequest(type, serviceGen, gen)) return@launch

            val local = credentialRepository.readLocalState(type)
            if (isStaleRequest(type, serviceGen, gen)) return@launch

            if (result.exceptionOrNull() !is RepositoryError.CredentialChanged) {
                currentCredentialFingerprint = local.fingerprint
            }

            result
                .onSuccess { balance ->
                    _uiState.update {
                        it.copy(
                            hasCredential = true,
                            cached = newerOf(it.cached, local.cachedBalance),
                            state = State.Fresh(balance)
                        )
                    }
                }
                .onFailure { error ->
                    if (error is RepositoryError.CredentialChanged) {
                        loadFromCache()
                        return@onFailure
                    }
                    _uiState.update { current ->
                        val mergedCache = newerOf(current.cached, local.cachedBalance)
                        current.copy(
                            cached = mergedCache,
                            state = State.Error(
                                cached = mergedCache?.balance,
                                message = errorMessage(error),
                                error = error
                            )
                        )
                    }
                }
        }
    }

    /** 加载可用模型列表（优先缓存，force=true 时强制联网）。 */
    fun loadModels(force: Boolean = false) {
        val service = _serviceType.value ?: return
        val serviceGen = serviceGeneration
        if (!force && _models.value.isNotEmpty()) return
        viewModelScope.launch {
            _modelsLoading.value = true
            val result = when (service) {
                ServiceType.CODEX -> refreshBalanceUseCase.fetchCodexModels()
                ServiceType.OPENCODE_GO -> refreshBalanceUseCase.fetchOpenCodeGoModels()
                ServiceType.OLLAMA -> refreshBalanceUseCase.fetchOllamaModels()
                else -> Result.failure(
                    RepositoryError.Unknown(IllegalArgumentException("不支持模型列表"))
                )
            }
            if (serviceGen != serviceGeneration || _serviceType.value != service) return@launch

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

    fun selectModel(model: String) {
        _selectedModel.value = model
        _serviceType.value?.let { persistSelectedModel(it, model) }
    }

    /** 一键激活用量。 */
    fun triggerUsage() {
        val service = _serviceType.value ?: return
        val serviceGen = serviceGeneration
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
                else -> Result.failure(
                    RepositoryError.Unknown(IllegalArgumentException("不支持激活用量"))
                )
            }
            if (serviceGen != serviceGeneration || _serviceType.value != service) return@launch

            result
                .onSuccess { responseBody ->
                    _triggerState.value = TriggerState.Success(responseBody)
                    kotlinx.coroutines.delay(2000)
                    if (serviceGen == serviceGeneration && _serviceType.value == service) {
                        refresh()
                    }
                }
                .onFailure { error ->
                    val message: String
                    val responseBody: String?
                    when (error) {
                        is TriggerError -> {
                            message = error.summary
                            responseBody = error.responseBody.ifBlank { null }
                        }

                        is RepositoryError.InvalidCredential -> {
                            message = error.message ?: "凭据无效，请重新登录"
                            responseBody = null
                        }

                        is RepositoryError.CredentialChanged -> {
                            message = "凭据已变更，请重新操作"
                            responseBody = null
                        }

                        is RepositoryError.Network -> {
                            message = "网络异常"
                            responseBody = null
                        }

                        else -> {
                            message = error.message ?: "未知错误"
                            responseBody = null
                        }
                    }
                    _triggerState.value = TriggerState.Error(message, responseBody)
                }
        }
    }

    fun dismissTrigger() {
        _triggerState.value = TriggerState.Idle
    }

    fun saveManualBalance(amount: Double) {
        val type = _serviceType.value ?: return
        val serviceGen = serviceGeneration
        viewModelScope.launch {
            val config = com.rainy.token.domain.service.ServiceConfigProvider.get(type)
            val balance = ServiceBalance(
                service = type,
                amount = amount,
                unit = config.displayUnit,
                isAvailable = true
            )
            balanceCache.put(type, balance)
            val cached = balanceCache.get(type)
            if (serviceGen == serviceGeneration && _serviceType.value == type) {
                _uiState.update {
                    it.copy(
                        cached = newerOf(it.cached, cached),
                        state = State.Fresh(balance)
                    )
                }
            }
        }
    }

    fun markVerified() {
        val type = _serviceType.value ?: return
        viewModelScope.launch {
            val credential = credentialRepository.get(type) ?: return@launch
            val updated = when (credential) {
                is Credential.ApiKeyCredential ->
                    credential.copy(lastVerifiedAt = System.currentTimeMillis())
                is Credential.SessionCredential ->
                    credential.copy(lastVerifiedAt = System.currentTimeMillis())
                is Credential.CodexCredential ->
                    credential.copy(lastVerifiedAt = System.currentTimeMillis())
            }
            credentialRepository.save(updated)
            loadFromCache()
        }
    }

    private fun loadFromCache() {
        val type = _serviceType.value ?: return
        val serviceGen = serviceGeneration
        viewModelScope.launch {
            val local = credentialRepository.readLocalState(type)
            val status = local.status
            val cached = local.cachedBalance
            val config = com.rainy.token.domain.service.ServiceConfigProvider.get(type)
            val isManual = config.method == com.rainy.token.domain.service.FetchMethod.MANUAL
            val newState: State = when {
                isManual && cached != null -> State.Stale(cached.balance, cached.fetchedAt)
                isManual -> State.ManualModeHint
                status.state == CredentialStatus.State.NOT_CONFIGURED ->
                    State.Error(null, "凭据未配置", RepositoryError.InvalidCredential())
                cached != null && status.state == CredentialStatus.State.OK ->
                    State.Stale(cached.balance, cached.fetchedAt)
                cached != null ->
                    State.Error(
                        cached.balance,
                        "凭据未配置或已过期",
                        RepositoryError.InvalidCredential()
                    )
                else -> State.Loading
            }

            if (serviceGen != serviceGeneration || _serviceType.value != type) return@launch
            currentCredentialFingerprint = local.fingerprint
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

    private fun mergeUnchangedLocalState(
        current: ServiceDetailUiState,
        localCache: CachedBalance?,
        hasCredential: Boolean
    ): ServiceDetailUiState {
        val cacheAdvanced = isNewer(candidate = localCache, current = current.cached)
        val mergedCache = newerOf(current.cached, localCache)
        val mergedState: State = when {
            !hasCredential -> State.Error(
                mergedCache?.balance,
                "凭据未配置",
                RepositoryError.InvalidCredential()
            )
            current.state is State.Loading -> current.state
            current.state is State.Error -> current.state.copy(
                cached = mergedCache?.balance ?: current.state.cached
            )
            cacheAdvanced && current.state is State.Fresh ->
                State.Fresh(mergedCache!!.balance)
            cacheAdvanced && current.state is State.Stale ->
                State.Stale(mergedCache!!.balance, mergedCache.fetchedAt)
            current.state is State.Fresh -> current.state
            current.state is State.Stale -> current.state
            mergedCache != null -> State.Stale(mergedCache.balance, mergedCache.fetchedAt)
            else -> current.state
        }
        return current.copy(
            hasCredential = hasCredential,
            cached = mergedCache,
            state = mergedState
        )
    }

    private fun newerOf(
        current: CachedBalance?,
        candidate: CachedBalance?
    ): CachedBalance? = when {
        current == null -> candidate
        candidate == null -> current
        candidate.fetchedAt > current.fetchedAt -> candidate
        else -> current
    }

    private fun isNewer(
        candidate: CachedBalance?,
        current: CachedBalance?
    ): Boolean = candidate != null && (current == null || candidate.fetchedAt > current.fetchedAt)

    private fun isStaleRequest(type: ServiceType, serviceGen: Int, refreshGen: Int): Boolean =
        refreshGen != refreshGeneration ||
            serviceGen != serviceGeneration ||
            _serviceType.value != type

    private fun errorMessage(error: Throwable): String = when (error) {
        is RepositoryError.InvalidCredential -> "凭据无效，请在设置中重新配置"
        is RepositoryError.CredentialChanged -> "凭据已变更，正在重新加载"
        is RepositoryError.RateLimited ->
            "请求过于频繁${error.retryAfterSeconds?.let { "，请 ${it} 秒后重试" } ?: ""}"
        is RepositoryError.Network -> "网络异常，请检查网络"
        is RepositoryError.ServerError -> "服务端异常 (HTTP ${error.code})"
        is RepositoryError.ParseError -> "数据解析失败: ${error.message}"
        else -> error.message ?: "未知错误"
    }

    private fun prefs(service: ServiceType): android.content.SharedPreferences =
        com.rainy.token.RainyTokenApplication.appContext.getSharedPreferences(
            prefsNameFor(service),
            android.content.Context.MODE_PRIVATE
        )

    private fun persistSelectedModel(service: ServiceType, model: String?) {
        prefs(service).edit().putString(KEY_SELECTED_MODEL, model).apply()
    }

    private fun loadSelectedModel(service: ServiceType): String? =
        prefs(service).getString(KEY_SELECTED_MODEL, null)

    private fun persistModelsCache(service: ServiceType, models: List<String>) {
        prefs(service).edit().putString(KEY_MODELS_CACHE, models.joinToString("\n")).apply()
    }

    private fun loadModelsCache(service: ServiceType): List<String> =
        prefs(service).getString(KEY_MODELS_CACHE, "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
}

sealed class State {
    data object Loading : State()
    data class Fresh(val data: ServiceBalance) : State()
    data class Stale(val data: ServiceBalance, val lastFetchedAt: Long) : State()
    data class Error(
        val cached: ServiceBalance?,
        val message: String,
        val error: Throwable
    ) : State()
    data object ManualModeHint : State()
}

data class ServiceDetailUiState(
    val state: State = State.Loading,
    val hasCredential: Boolean = false,
    val cached: CachedBalance? = null
)

sealed class TriggerState {
    data object Idle : TriggerState()
    data object Loading : TriggerState()
    data class Success(val responseBody: String) : TriggerState()
    data class Error(val message: String, val responseBody: String?) : TriggerState()
}
