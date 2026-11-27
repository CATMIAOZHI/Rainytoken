package com.rainy.token.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.ChartSettingsStore
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
import java.time.ZoneOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

private const val DATA_PAGE_SIZE = 20

private data class GoPageSession(
    val scopeCount: Int,
    val bounds: Pair<Long?, Long?>
)

@HiltViewModel
class UsageDataViewModel @Inject constructor(
    private val cacheProvider: Provider<UsageCache>,
    private val credentialRepository: CredentialRepository,
    private val chartSettingsStore: ChartSettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(UsageDataState())
    val state: StateFlow<UsageDataState> = _state.asStateFlow()

    private var workspaceIdOverride: String? = null
    private var loadJob: Job? = null
    private var goPageSession: GoPageSession? = null
    private var displayedGoScope: String? = null
    private var goScopeObserver: Job? = null

    private var zoneObserver: Job? = null
    private var useUtc8 = false

    fun observeGoUsageScope() {
        if (workspaceIdOverride == null && zoneObserver?.isActive != true) {
            zoneObserver = viewModelScope.launch {
                chartSettingsStore.useUtc8Flow.collect { saved ->
                    if (workspaceIdOverride == null) applyTimeZone(saved)
                }
            }
        }
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
        goPageSession = null
        _state.value = UsageDataState(
            loading = scope != null, scopeGeneration = _state.value.scopeGeneration + 1,
            useUtc8 = useUtc8, hasGoApiKey = scope != null
        )
    }

    /** Use the fixed CommandCode scope instead of the OpenCode API key. */
    fun setWorkspace(wid: String) {
        if (workspaceIdOverride == wid) return
        workspaceIdOverride = wid
        goPageSession = null
        goScopeObserver?.cancel()
        zoneObserver?.cancel()
        _state.value = UsageDataState(scopeGeneration = _state.value.scopeGeneration + 1)
        loadData()
    }
    private suspend fun cacheScope(): String? {
        workspaceIdOverride?.let { return it }
        return OpenCodeUsageRepository.cacheScope(credentialRepository.get(ServiceType.OPENCODE_GO))
    }

    fun loadData() {
        loadJob?.cancel()
        if (workspaceIdOverride == null) _state.update { it.copy(loading = true) }
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
        val snapshot = _state.value
        // Keep moving windows (e.g. last five hours) fixed between pages of the same query.
        val isGo = workspaceIdOverride == null
        val previous = goPageSession.takeIf { isGo }
        val bounds = previous?.bounds ?: snapshot.timeFilter.toRange(zoneOffset = snapshot.zoneOffset)
        val (fromTs, toTs) = bounds
        val selected = snapshot.selectedModels
        val total: Int
        val page: Int
        val paged: List<UsageRecord>
        val models: List<String>
        val nextSession: GoPageSession?
        if (isGo) {
            val result = cache.getGoRecordPageSnapshot(
                wid, fromTs, toTs, selected, DATA_PAGE_SIZE, snapshot.currentPage, previous?.scopeCount
            )
            total = result.filteredCount
            page = result.page
            paged = result.records
            models = result.models
            nextSession = GoPageSession(result.scopeCount, bounds)
        } else {
            total = cache.countRecords(wid, fromTs, toTs, selected)
            page = snapshot.currentPage.coerceIn(1, pageCount(total).coerceAtLeast(1))
            paged = cache.getRecordPage(wid, fromTs, toTs, selected, DATA_PAGE_SIZE, (page - 1) * DATA_PAGE_SIZE)
            models = cache.getDistinctModels(wid)
            nextSession = null
        }
        if (cacheScope() != wid) {
            loadData()
            return
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        if (_state.value.scopeGeneration != snapshot.scopeGeneration) return
        goPageSession = nextSession
        _state.update {
            it.copy(
                records = paged,
                allModels = models,
                totalRecords = total,
                totalPages = pageCount(total),
                currentPage = page,
                loading = false
            )
        }
    }

    private fun pageCount(records: Int): Int = ((records.toLong() + DATA_PAGE_SIZE - 1) / DATA_PAGE_SIZE).toInt()

    fun setTimeZone(utc8: Boolean) {
        if (workspaceIdOverride != null) return
        viewModelScope.launch { chartSettingsStore.setUseUtc8(utc8) }
    }

    private fun applyTimeZone(newValue: Boolean) {
        if (newValue == useUtc8) return
        useUtc8 = newValue
        goPageSession = null
        _state.update { state ->
            val oldOffset = state.zoneOffset
            val changed = state.copy(useUtc8 = newValue, currentPage = 1)
            val filter = state.timeFilter
            if (filter !is TimeFilter.Custom) changed else {
                // Keep the chosen calendar dates, not their UTC boundaries.
                val shift = (oldOffset.totalSeconds - changed.zoneOffset.totalSeconds) * 1000L
                changed.copy(timeFilter = TimeFilter.Custom(filter.from + shift, filter.to + shift))
            }
        }
        loadData()
    }

    fun setTimeFilter(filter: TimeFilter) {
        goPageSession = null
        _state.update { it.copy(timeFilter = filter, currentPage = 1) }
        if (filter !is TimeFilter.Custom || (filter.from != 0L || filter.to != 0L)) {
            loadData()
        }
    }

    fun toggleModel(model: String) {
        goPageSession = null
        _state.update {
            val set = it.selectedModels.toMutableSet()
            if (model in set) set.remove(model) else set.add(model)
            it.copy(selectedModels = set, currentPage = 1)
        }
        loadData()
    }

    fun selectAllModels() {
        goPageSession = null
        _state.update { it.copy(selectedModels = emptySet(), currentPage = 1) }
        loadData()
    }

    fun goToPage(page: Int) {
        if (workspaceIdOverride == null && _state.value.loading) return
        val maxPage = _state.value.totalPages
        val clamped = page.coerceIn(1, maxPage.coerceAtLeast(1))
        _state.update { it.copy(currentPage = clamped) }
        loadData()
    }

    fun nextPage() {
        if (workspaceIdOverride == null && _state.value.loading) return
        val maxPage = _state.value.totalPages
        if (_state.value.currentPage < maxPage) {
            _state.update { it.copy(currentPage = it.currentPage + 1) }
            loadData()
        }
    }

    fun prevPage() {
        if (workspaceIdOverride == null && _state.value.loading) return
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
    val totalRecords: Int = 0,
    val useUtc8: Boolean = false,
    val hasGoApiKey: Boolean = false
) {
    val zoneLabel: String get() = if (useUtc8) "UTC+8" else "UTC+0"
    val zoneOffset: ZoneOffset get() = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
}
