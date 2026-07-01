package com.rainy.token.ui.servicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.CachedBalance
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.RepositoryError
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

    fun bind(service: ServiceType) {
        if (_serviceType.value == service) return
        _serviceType.value = service
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