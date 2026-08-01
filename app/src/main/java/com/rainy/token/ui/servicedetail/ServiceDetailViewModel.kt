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
import com.rainy.token.R
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import com.rainy.token.ui.components.UiText
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
                                UiText.Resource(R.string.error_credential_not_configured),
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
            val requestFingerprint = credentialRepository.readLocalState(type).fingerprint
            if (isStaleRequest(type, serviceGen, gen)) return@launch
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
            val fingerprintChanged = local.fingerprint != requestFingerprint

            result
                .onSuccess { balance ->
                    // 认证字段可能因 Codex Token 轮换而变化，也可能是用户在请求完成后
                    // 切换了账户。两种情况下只信任当前凭据对应的持久化缓存；绝不把
                    // 请求结果中的旧账户余额重新挂到新凭据上。
                    val currentCache = if (fingerprintChanged) {
                        local.cachedBalance
                    } else {
                        newerOf(_uiState.value.cached, local.cachedBalance)
                    }
                    currentCredentialFingerprint = local.fingerprint
                    if (fingerprintChanged && currentCache == null) {
                        loadFromCache()
                        return@onSuccess
                    }
                    _uiState.update {
                        val mergedCache = if (fingerprintChanged) {
                            local.cachedBalance
                        } else {
                            newerOf(it.cached, local.cachedBalance)
                        }
                        it.copy(
                            hasCredential = true,
                            cached = mergedCache,
                            state = State.Fresh(mergedCache?.balance ?: balance)
                        )
                    }
                }
                .onFailure { error ->
                    if (error is RepositoryError.CredentialChanged) {
                        loadFromCache()
                        return@onFailure
                    }
                    // 指纹变化时不复用当前页面里的旧账户缓存，也不提前接受新指纹；
                    // 返回页面的 reload 会把它识别为 REPLACED 并按新凭据重验。
                    if (!fingerprintChanged) {
                        currentCredentialFingerprint = local.fingerprint
                    }
                    _uiState.update { current ->
                        val mergedCache = if (fingerprintChanged) {
                            local.cachedBalance
                        } else {
                            newerOf(current.cached, local.cachedBalance)
                        }
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
            _triggerState.value = TriggerState.Error(
                UiText.Resource(R.string.error_select_model_first),
                null
            )
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
                    val message: UiText
                    val responseBody: String?
                    when (error) {
                        is TriggerError -> {
                            message = UiText.Dynamic(error.summary)
                            responseBody = error.responseBody.ifBlank { null }
                        }

                        is RepositoryError.InvalidCredential -> {
                            message = UiText.Resource(R.string.error_credential_invalid_relogin)
                            responseBody = null
                        }

                        is RepositoryError.CredentialChanged -> {
                            message = UiText.Resource(R.string.error_credential_changed_retry)
                            responseBody = null
                        }

                        is RepositoryError.Network -> {
                            message = UiText.Resource(R.string.error_network_generic)
                            responseBody = null
                        }

                        else -> {
                            message = if ((error as? RepositoryError.Unknown)?.cause is IllegalArgumentException) {
                                UiText.Resource(R.string.error_trigger_not_supported)
                            } else {
                                error.message?.let { UiText.Dynamic(it) }
                                    ?: UiText.Resource(R.string.common_unknown)
                            }
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
                    State.Error(
                        null,
                        UiText.Resource(R.string.error_credential_not_configured),
                        RepositoryError.InvalidCredential()
                    )
                cached != null && status.state == CredentialStatus.State.OK ->
                    State.Stale(cached.balance, cached.fetchedAt)
                cached != null ->
                    State.Error(
                        cached.balance,
                        UiText.Resource(R.string.error_credential_not_configured_expired),
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
                UiText.Resource(R.string.error_credential_not_configured),
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

    private fun errorMessage(error: Throwable): UiText = when (error) {
        is RepositoryError.InvalidCredential ->
            UiText.Resource(R.string.error_credential_invalid_reconfigure)
        is RepositoryError.CredentialChanged ->
            UiText.Resource(R.string.error_credential_changed_reload)
        is RepositoryError.RateLimited -> UiText.Resource(
            R.string.error_rate_limited_retry,
            listOf(
                error.retryAfterSeconds?.let {
                    UiText.Resource(R.string.error_rate_limited_retry_suffix, listOf(it))
                } ?: ""
            )
        )
        is RepositoryError.Network -> UiText.Resource(R.string.error_network_check)
        is RepositoryError.ServerError ->
            UiText.Resource(R.string.error_server_http, listOf(error.code))
        is RepositoryError.ParseError ->
            UiText.Resource(R.string.error_parse_failed, listOf(error.message ?: ""))
        else -> error.message?.let { UiText.Dynamic(it) }
            ?: UiText.Resource(R.string.common_unknown)
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
        val message: UiText,
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
    data class Error(val message: UiText, val responseBody: String?) : TriggerState()
}
