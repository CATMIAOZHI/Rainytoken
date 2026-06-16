package com.rainy.token.domain.usecase

import com.rainy.token.data.repository.DeepSeekRepository
import com.rainy.token.data.repository.OpenCodeGoRepository
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import javax.inject.Inject
import javax.inject.Provider

/**
 * 唯一的 UseCase。计划架构补充说明：
 *  - 内部按 ServiceType 分发到对应 Repository
 *  - 现阶段实现：DeepSeek（REST API） + OpenCode Go（OkHttp 抓 dashboard）
 *  - **使用 Provider 注入**——规避 KSP 2.x 在多个 @Inject constructor Repository
 *    注入同一 UseCase 时的"could not be resolved"误报（KSP 已知 issue）。
 *    Provider 让 Hilt 推迟创建 Repository 实例到第一次 .get() 时，KSP 不需要在
 *    编译期解析所有构造器签名。
 */
class RefreshBalanceUseCase @Inject constructor(
    private val deepSeekRepositoryProvider: Provider<DeepSeekRepository>,
    private val openCodeGoRepositoryProvider: Provider<OpenCodeGoRepository>
) {
    suspend operator fun invoke(service: ServiceType): Result<ServiceBalance> = when (service) {
        ServiceType.DEEPSEEK -> deepSeekRepositoryProvider.get().fetchBalance()
        ServiceType.OPENCODE_GO -> openCodeGoRepositoryProvider.get().fetchBalance()
    }
}