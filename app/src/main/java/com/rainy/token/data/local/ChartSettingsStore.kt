package com.rainy.token.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.chartSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chart_settings"
)

/** 图表页偏好设置持久化 */
class ChartSettingsStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_USE_UTC8 = booleanPreferencesKey("use_utc8")
    }

    /** 流式读取偏好 */
    val useUtc8Flow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USE_UTC8] ?: false
    }

    /** 同步读取（用于 ViewModel 初始化） */
    fun getUseUtc8(): Boolean = runBlocking {
        dataStore.data.map { it[KEY_USE_UTC8] ?: false }.first()
    }

    /** 写入偏好 */
    suspend fun setUseUtc8(value: Boolean) {
        dataStore.edit { it[KEY_USE_UTC8] = value }
    }
}

/** 顶层 DataStore 委托 */
val Context.chartSettingsStore: ChartSettingsStore
    get() = ChartSettingsStore(chartSettingsDataStore)