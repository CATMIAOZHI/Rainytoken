package com.rainy.token.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

private val Context.chartSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chart_settings"
)

private val chartSettingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 图表页偏好设置持久化 */
class ChartSettingsStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_USE_UTC8 = booleanPreferencesKey("use_utc8")
    }

    /** 流式读取偏好 */
    val useUtc8Flow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USE_UTC8] ?: false
    }

    /** StateFlow 版本，UI 层 collectAsState 直接使用 */
    val useUtc8State: StateFlow<Boolean> = useUtc8Flow
        .stateIn(chartSettingsScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** 写入偏好 */
    suspend fun setUseUtc8(value: Boolean) {
        dataStore.edit { it[KEY_USE_UTC8] = value }
    }
}

/** 顶层 DataStore 委托 */
val Context.chartSettingsStore: ChartSettingsStore
    get() = ChartSettingsStore(chartSettingsDataStore)