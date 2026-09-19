package com.rainy.token.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.usecase.UsageSyncCoordinator
import com.rainy.token.sync.UsageSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页 ViewModel：凭据状态列表 + 用量同步相关开关。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val syncCoordinator: UsageSyncCoordinator,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val statuses = credentialRepository.statusForAll()
            _uiState.update {
                it.copy(
                    loading = false,
                    credentialStatuses = statuses,
                    backgroundSyncEnabled = syncCoordinator.backgroundSyncEnabled
                )
            }
        }
    }

    /**
     * 后台同步开关：写入偏好并立刻注册 / 取消 8 小时周期任务。
     *
     * 关闭后前台（进入页面）同步仍然生效，只是不再有后台兜底。
     */
    fun setBackgroundSyncEnabled(enabled: Boolean) {
        syncCoordinator.backgroundSyncEnabled = enabled
        UsageSyncScheduler.apply(appContext, enabled)
        _uiState.update { it.copy(backgroundSyncEnabled = enabled) }
    }
}

data class SettingsUiState(
    val loading: Boolean = false,
    val credentialStatuses: List<CredentialStatus> = emptyList(),
    val backgroundSyncEnabled: Boolean = true
)