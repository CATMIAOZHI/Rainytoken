package com.rainy.token.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.cache.CachedBalance
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import com.rainy.token.R
import com.rainy.token.ui.components.UiText
import com.rainy.token.ui.widget.OpenCodeGoWidgetProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 仪表盘 ViewModel。
 *
 * 状态聚合：凭据状态 + 余额缓存 + 在线刷新
 *  - 启动时读缓存展示（无网时也能看旧数据）
 *  - refresh() 并行拉取所有服务的最新余额（任一失败不影响其他）
 *  - 下拉刷新触发同一 refresh()
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val refreshBalanceUseCase: RefreshBalanceUseCase,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** 防止并发 refresh() 调用交错覆盖 UI。 */
    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch {
            // 1) 先从缓存快速填充 UI（断网时也能看旧数据）
            loadFromCache()
            // 2) 再发起网络刷新——此时 UI 已有缓存兜底，不会闪回旧值
            refresh()
        }
    }

    /**
     * 重新读取本地凭据状态 + 余额缓存，不发起网络请求。
     *
     * 卡片记录不含明文密钥的 SHA-256 指纹。更新时始终基于 _uiState 的最新卡片：
     * - 凭据变化时采用已清理过的当前账户缓存；
     * - 凭据未变时按 fetchedAt 采用更新缓存，支持 Widget 后台刷新结果回显；
     * - 不修改 refreshing，旧缓存快照也不能回滚新数据。
     */
    fun reloadLocalState() {
        viewModelScope.launch {
            val localStates = credentialRepository.readLocalStates()
            _uiState.update { state ->
                state.copy(
                    cards = state.cards.map { card ->
                        val local = localStates.getValue(card.service)
                        val credentialChanged = local.fingerprint != card.credentialFingerprint
                        val cacheAdvanced = !credentialChanged && isNewer(
                            candidate = local.cachedBalance,
                            current = card.cachedBalance
                        )
                        card.copy(
                            credentialState = local.status.state,
                            credentialFingerprint = local.fingerprint,
                            cachedBalance = if (credentialChanged) {
                                local.cachedBalance
                            } else {
                                newerOf(card.cachedBalance, local.cachedBalance)
                            },
                            // 凭据变化或出现更新的成功缓存时，旧错误已不再代表当前数据。
                            lastFetchError = if (credentialChanged || cacheAdvanced) {
                                null
                            } else {
                                card.lastFetchError
                            }
                        )
                    }
                )
            }
        }
    }

    /** 从本地缓存快速填充一次（不阻塞）。挂起函数，供调用方控制执行顺序。 */
    private suspend fun loadFromCache() {
        val localStates = credentialRepository.readLocalStates()
        val cards = ServiceType.entries.map { type ->
            buildCard(localStates.getValue(type), lastFetchError = null)
        }
        _uiState.update { it.copy(loading = false, refreshing = false, cards = cards) }
    }

    /** 拉取所有服务最新余额，更新缓存。失败的服务保留旧数据并把错误信息带上。 */
    fun refresh() {
        viewModelScope.launch {
            // Mutex 防并发：如果已有 refresh 在跑，后来的直接跳过
            if (!refreshMutex.tryLock()) return@launch
            try {
                _uiState.update { it.copy(refreshing = true) }
                val results: Map<ServiceType, Result<ServiceBalance>?> = coroutineScope {
                    ServiceType.entries.map { type ->
                        async {
                            val status = credentialRepository.statusFor(type)
                            if (status.state == CredentialStatus.State.NOT_CONFIGURED) {
                                type to null // 未配置的服务不拉
                            } else {
                                type to refreshBalanceUseCase(type)
                            }
                        }
                    }.awaitAll().toMap()
                }
                val localStates = credentialRepository.readLocalStates()
                val cards = ServiceType.entries.map { type ->
                    val result = results[type]
                    val error = result?.exceptionOrNull()
                    val errorUi = error
                        ?.takeUnless { it is RepositoryError.CredentialChanged }
                        ?.let { errorToUiText(it) }
                    buildCard(localStates.getValue(type), lastFetchError = errorUi)
                }
                _uiState.update { it.copy(refreshing = false, cards = cards) }
                // 刷新成功后更新桌面小组件
                if (results[ServiceType.OPENCODE_GO]?.isSuccess == true) {
                    OpenCodeGoWidgetProvider.notifyDataChanged(appContext)
                }
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    private fun buildCard(
        local: CredentialRepository.LocalState,
        lastFetchError: UiText?
    ): DashboardCardUi = DashboardCardUi(
        service = local.status.service,
        credentialState = local.status.state,
        credentialFingerprint = local.fingerprint,
        cachedBalance = local.cachedBalance,
        lastFetchError = lastFetchError
    )

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
}

data class DashboardUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val cards: List<DashboardCardUi> = emptyList()
)

data class DashboardCardUi(
    val service: ServiceType,
    val credentialState: CredentialStatus.State,
    val credentialFingerprint: String?,
    val cachedBalance: CachedBalance?,
    val lastFetchError: UiText?
) {
    /** 余额展示主数字。优先取缓存，错误时也展示（不隐藏，让用户看到旧值 + 红点提示）。 */
    val displayBalance: ServiceBalance? get() = cachedBalance?.balance
}

/** 把 Repository 错误映射为本地化文案（错误详情 detail 为技术信息，随资源参数展示）。 */
private fun errorToUiText(error: Throwable): UiText = when (error) {
    is RepositoryError.InvalidCredential ->
        UiText.Resource(R.string.error_credential_invalid_reconfigure)
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
        UiText.Resource(R.string.error_parse_failed, listOf(error.detail))
    else -> error.message?.let { UiText.Dynamic(it) }
        ?: UiText.Resource(R.string.common_unknown)
}
