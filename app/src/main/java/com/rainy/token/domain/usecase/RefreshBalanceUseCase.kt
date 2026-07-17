package com.rainy.token.domain.usecase

import com.rainy.token.data.repository.CodexRepository
import com.rainy.token.data.repository.CommandCodeGoRepository
import com.rainy.token.data.repository.DeepSeekRepository
import com.rainy.token.data.repository.OllamaRepository
import com.rainy.token.data.repository.OpenCodeGoRepository
import com.rainy.token.data.repository.retryOnTransientError
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import javax.inject.Inject
import javax.inject.Provider

/**
 * 唯一的 UseCase。计划架构补充说明：
 *  - 内部按 ServiceType 分发到对应 Repository
 *  - 现阶段实现：DeepSeek（REST API） + OpenCode Go（OkHttp 抓 dashboard）+ CommandCode Go（JSON API）
 *    + Codex / ChatGPT Plus（wham usage API）+ Ollama Pro（Cookie 抓 settings HTML）
 *  - **使用 Provider 注入**——规避 KSP 2.x 在多个 @Inject constructor Repository
 *    注入同一 UseCase 时的"could not be resolved"误报（KSP 已知 issue）。
 *    Provider 让 Hilt 推迟创建 Repository 实例到第一次 .get() 时，KSP 不需要在
 *    编译期解析所有构造器签名。
 *  - **重试策略**：对 Network 和 5xx ServerError 自动指数退避重试（最多 2 次），
 *    401/403/429/ParseError 不重试。
 */
class RefreshBalanceUseCase @Inject constructor(
    private val deepSeekRepositoryProvider: Provider<DeepSeekRepository>,
    private val openCodeGoRepositoryProvider: Provider<OpenCodeGoRepository>,
    private val commandCodeGoRepositoryProvider: Provider<CommandCodeGoRepository>,
    private val codexRepositoryProvider: Provider<CodexRepository>,
    private val ollamaRepositoryProvider: Provider<OllamaRepository>
) {
    suspend operator fun invoke(service: ServiceType): Result<ServiceBalance> = when (service) {
        ServiceType.DEEPSEEK -> retryOnTransientError { deepSeekRepositoryProvider.get().fetchBalance() }
        ServiceType.OPENCODE_GO -> retryOnTransientError { openCodeGoRepositoryProvider.get().fetchBalance() }
        ServiceType.COMMANDCODE_GO -> retryOnTransientError { commandCodeGoRepositoryProvider.get().fetchBalance() }
        ServiceType.CODEX -> retryOnTransientError { codexRepositoryProvider.get().fetchBalance() }
        ServiceType.OLLAMA -> retryOnTransientError { ollamaRepositoryProvider.get().fetchBalance() }
    }

    /** 获取 Codex 可用模型列表 */
    suspend fun fetchCodexModels(): Result<List<String>> =
        codexRepositoryProvider.get().fetchModels()

    /** 一键激活 Codex 用量：向 ChatGPT API 发送请求，返回完整响应体 */
    suspend fun triggerCodexUsage(model: String): Result<String> =
        codexRepositoryProvider.get().triggerUsage(model)
}