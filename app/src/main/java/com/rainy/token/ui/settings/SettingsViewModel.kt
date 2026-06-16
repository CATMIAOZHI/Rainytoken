package com.rainy.token.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.CredentialStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页 ViewModel。当前阶段只展示凭据状态列表，后续阶段会加入
 * 刷新间隔、预警阈值、深色模式等设置项。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository
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
                it.copy(loading = false, credentialStatuses = statuses)
            }
        }
    }
}

data class SettingsUiState(
    val loading: Boolean = false,
    val credentialStatuses: List<CredentialStatus> = emptyList()
)