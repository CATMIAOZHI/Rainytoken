package com.rainy.token.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.CachedBalance
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import com.rainy.token.ui.widget.OpenCodeGoWidgetProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

/**
 * 仪表盘 ViewModel。
 *
 * 状态聚合：凭据状态 + 余额缓存 + 在线刷新
 *  - 启动时读缓存展示（无网时也能看）
 *  - refresh() 并行拉取所有服务的最新余额（任一失败不影响其他）
 *  - 下拉刷新触发同一 refresh()
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache,
    private val refreshBalanceUseCase: RefreshBalanceUseCase,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** 防止并发 refresh() 调用交错覆盖 UI */
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
     * 仅重新读取本地凭据状态 + 余额缓存，不发起网络请求。
     * 用于从设置页返回 Dashboard 时同步配置变更。
     *
     * 安全设计：只更新已有卡片的凭据状态字段，不替换余额/refreshing，
     * 避免与正在运行的 refresh() 产生竞态（AGENTS.md 刷新竞态红线）。
     */
    fun reloadLocalState() {
        viewModelScope.launch {
            val cached = balanceCache.getAll()
            _uiState.update { state ->
                state.copy(
                    cards = state.cards.map { card ->
                        val status = credentialRepository.statusFor(card.service)
                        card.copy(
                            credentialState = status.state,
                            // 如果之前因 NOT_CONFIGURED 没有余额，现在配了凭据就补上缓存
                            cachedBalance = card.cachedBalance ?: cached[card.service]
                        )
                    }
                )
            }
        }
    }

    /** 从本地缓存快速填充一次（不阻塞）。挂起函数，供调用方控制执行顺序。 */
    private suspend fun loadFromCache() {
        val cached = balanceCache.getAll()
        val cards = ServiceType.entries.map { type ->
            buildCard(type, cachedBalance = cached[type], lastFetchError = null)
        }
        _uiState.update { it.copy(loading = false, refreshing = false, cards = cards) }
    }

    /** 拉取所有服务最新余额，更新缓存。失败的服务保留旧数据并把错误信息带上 */
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
                                type to null  // 未配置的服务不拉
                            } else {
                                type to refreshBalanceUseCase(type)
                            }
                        }
                    }.awaitAll().toMap()
                }
                val newCache = balanceCache.getAll()
                val cards = ServiceType.entries.map { type ->
                    val result = results[type]
                    val errMsg = result?.exceptionOrNull()?.message
                    buildCard(type, cachedBalance = newCache[type], lastFetchError = errMsg)
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

    private suspend fun buildCard(
        type: ServiceType,
        cachedBalance: CachedBalance?,
        lastFetchError: String?
    ): DashboardCardUi {
        val status = credentialRepository.statusFor(type)
        return DashboardCardUi(
            service = type,
            credentialState = status.state,
            cachedBalance = cachedBalance,
            lastFetchError = lastFetchError
        )
    }
}

data class DashboardUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val cards: List<DashboardCardUi> = emptyList()
)

data class DashboardCardUi(
    val service: ServiceType,
    val credentialState: CredentialStatus.State,
    val cachedBalance: CachedBalance?,
    val lastFetchError: String?
) {
    /** 余额展示主数字。优先取缓存，错误时也展示（不隐藏，让用户看到旧值 + 红点提示） */
    val displayBalance: ServiceBalance? get() = cachedBalance?.balance

    /** 卡片顶部状态徽章 */
    val statusBadge: String get() = when {
        credentialState == CredentialStatus.State.NOT_CONFIGURED -> "未配置"
        lastFetchError != null -> "刷新失败"
        cachedBalance == null -> "未获取"
        else -> "正常"
    }
}