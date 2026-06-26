package com.rainy.token.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.UsageCache
import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private var loadGeneration = 0

    /** 覆盖 workspaceId，用于 CCGO 等非 OCGO 服务。必须在 loadModelsAndData() 前调用。 */
    fun setWorkspace(wid: String) {
        workspaceIdOverride = wid
        loadGeneration++
        // 先清空数据防止 init 自动加载的 OCGO 数据闪一下
        _state.value = UsageDataState()
        loadData()
    }

    // 不在 init 自动加载，由 Composable 层显式触发 loadData()（OCGO）或 setWorkspace()（CCGO）

    private fun loadModelsAndData() {
        viewModelScope.launch {
            val wid = workspaceId() ?: return@launch
            val genAtStart = loadGeneration
            val cache = cacheProvider.get()
            val models = cache.getDistinctModels(wid)
            _state.update { it.copy(allModels = models) }
            // 如果 loadGeneration 已经变化，丢弃这次结果
            if (loadGeneration != genAtStart) return@launch
            // 紧接着加载数据
            loadDataInternal(cache, wid)
        }
    }

    private suspend fun workspaceId(): String? {
        workspaceIdOverride?.let { return it }
        val c = credentialRepository.get(ServiceType.OPENCODE_GO)
        return (c as? Credential.SessionCredential)?.workspaceId?.takeIf { it.isNotBlank() }
    }

    fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val wid = workspaceId() ?: return@launch
            val genAtStart = loadGeneration
            val cache = cacheProvider.get()
            loadDataInternal(cache, wid)
            // 如果 loadGeneration 已经变化，丢弃这次结果
            if (loadGeneration != genAtStart) return@launch
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
        _state.update {
            it.copy(
                records = paged,
                allModels = models,
                totalRecords = total,
                totalPages = totalPages,
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
    val records: List<UsageRecord> = emptyList(),
    val allModels: List<String> = emptyList(),
    val selectedModels: Set<String> = emptySet(),  // empty = all
    val timeFilter: TimeFilter = TimeFilter.All,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val totalRecords: Int = 0
)
