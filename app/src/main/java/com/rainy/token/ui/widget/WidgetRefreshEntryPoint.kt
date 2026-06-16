package com.rainy.token.ui.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.rainy.token.domain.usecase.RefreshBalanceUseCase

/**
 * Hilt EntryPoint：让非 Hilt 组件（普通 BroadcastReceiver）获取依赖。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetRefreshEntryPoint {
    fun refreshBalanceUseCase(): RefreshBalanceUseCase
}