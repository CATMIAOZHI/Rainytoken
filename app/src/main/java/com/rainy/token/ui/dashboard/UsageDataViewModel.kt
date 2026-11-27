package com.rainy.token.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.OpenCodeUsageRepository
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

private const val DATA_PAGE_SIZE = 20

@HiltViewModel
class UsageDataViewModel @Inject constructor(
    private val cacheProvider: Provider<UsageCache>,
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    private val _state = MutableStateFlow(UsageDataState())
    val state: StateFlow<UsageDataState> = _state.asStateFlow()

    private var workspaceIdOverride: String? = null
    private var loadJob: Job? = null
    private var displayedGoScope: String? = null
    private var goScopeObserver: Job? = null

    fun observeGoUsageScope() {
        if (workspaceIdOverride != null || goScopeObserver?.isActive == true) return
        goScopeObserver = viewModelScope.launch {
            credentialRepository.openCodeUsageScopeChanges.collect { scope ->
                if (workspaceIdOverride != null) return@collect
                showGoScope(scope)
                loadData()
            }
        }
    }

    private fun showGoScope(scope: String?) {
        if (displayedGoScope == scope) return
        displayedGoScope = scope
        _state.value = UsageDataState(loading = scope != null, scopeGeneration = _state.value.scopeGeneration + 1)
    }

    /** Use the fixed CommandCode scope instead of the OpenCode API key. */
    fun setWorkspace(wid: String) {
        workspaceIdOverride = wid
        goScopeObserver?.cancel()
        _state.value = UsageDataState()
        loadData()
    }
    private suspend fun cacheScope(): String? {
        workspaceIdOverride?.let { return it }
        return OpenCodeUsageRepository.cacheScope(credentialRepository.get(ServiceType.OPENCODE_GO))
    }

    fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val wid = cacheScope()
            if (workspaceIdOverride == null) showGoScope(wid)
            if (wid == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _state.update { it.copy(loading = true) }
            loadDataInternal(cacheProvider.get(), wid)
        }
    }
    private suspend fun loadDataInternal(cache: UsageCache, wid: String) {
        val filter = _state.value.timeFilter
        val (fromTs, toTs) = filter.toRange()

        var records = cache.getRecords(wid, fromTs = fromTs, toTs = toTs)
        val selected = _state.value.selectedModels
        if (selected.isNotEmpty()) {
            records = records.filter { it.model in selected }
        }
        records = records.sortedByDescending { it.timeCreated }

        val total = records.size
        val totalPages = (total + DATA_PAGE_SIZE - 1) / DATA_PAGE_SIZE
        val page = _state.value.currentPage.coerceIn(1, totalPages.coerceAtLeast(1))
        val paged = records.drop((page - 1) * DATA_PAGE_SIZE).take(DATA_PAGE_SIZE)

        val models = cache.getDistinctModels(wid)
        if (cacheScope() != wid) {
            loadData()
            return
        }
        _state.update {
            it.copy(
                records = paged,
                allModels = models,
                totalRecords = total,
                totalPages = totalPages,
                currentPage = page,
                loading = false
            )
        }
    }

    fun setTimeFilter(filter: TimeFilter) {
        _state.update { it.copy(timeFilter = filter, currentPage = 1) }
        if (filter !is TimeFilter.Custom || (filter.from != 0L || filter.to != 0L)) {
            loadData()
        }
    }

    fun toggleModel(model: String) {
        _state.update {
            val set = it.selectedModels.toMutableSet()
            if (model in set) set.remove(model) else set.add(model)
            it.copy(selectedModels = set, currentPage = 1)
        }
        loadData()
    }

    fun selectAllModels() {
        _state.update { it.copy(selectedModels = emptySet(), currentPage = 1) }
        loadData()
    }

    fun goToPage(page: Int) {
        val maxPage = _state.value.totalPages
        val clamped = page.coerceIn(1, maxPage.coerceAtLeast(1))
        _state.update { it.copy(currentPage = clamped) }
        loadData()
    }

    fun nextPage() {
        val maxPage = _state.value.totalPages
        if (_state.value.currentPage < maxPage) {
            _state.update { it.copy(currentPage = it.currentPage + 1) }
            loadData()
        }
    }

    fun prevPage() {
        if (_state.value.currentPage > 1) {
            _state.update { it.copy(currentPage = it.currentPage - 1) }
            loadData()
        }
    }
}

data class UsageDataState(
        val loading: Boolean = true,
    val scopeGeneration: Int = 0,
    val records: List<UsageRecord> = emptyList(),
    val allModels: List<String> = emptyList(),
    val selectedModels: Set<String> = emptySet(),  // empty = all
    val timeFilter: TimeFilter = TimeFilter.All,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val totalRecords: Int = 0
)
