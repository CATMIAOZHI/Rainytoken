package com.rainy.token.domain.usecase

import com.rainy.token.data.repository.CodexRepository
import com.rainy.token.data.repository.CommandCodeGoRepository
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.DeepSeekRepository
import com.rainy.token.data.repository.OllamaRepository
import com.rainy.token.data.repository.OpenCodeGoRepository
import com.rainy.token.data.repository.RefreshWriteSession
import com.rainy.token.data.repository.RepositoryError
import com.rainy.token.data.repository.retryOnTransientError
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.model.TriggerSummary
import com.rainy.token.domain.service.ServiceType
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 唯一的余额刷新 UseCase。
 *
 * Repository 中的凭据/缓存写入先暂存；请求结束后校验起始凭据快照仍为当前版本才提交，
 * 避免旧请求覆盖用户刚保存或删除的新凭据。
 */
class RefreshBalanceUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val deepSeekRepositoryProvider: Provider<DeepSeekRepository>,
    private val openCodeGoRepositoryProvider: Provider<OpenCodeGoRepository>,
    private val commandCodeGoRepositoryProvider: Provider<CommandCodeGoRepository>,
    private val codexRepositoryProvider: Provider<CodexRepository>,
    private val ollamaRepositoryProvider: Provider<OllamaRepository>
) {
    suspend operator fun invoke(service: ServiceType): Result<ServiceBalance> =
        withCredentialSession(service) {
            when (service) {
                ServiceType.DEEPSEEK -> retryOnTransientError {
                    deepSeekRepositoryProvider.get().fetchBalance()
                }
                ServiceType.OPENCODE_GO -> retryOnTransientError {
                    openCodeGoRepositoryProvider.get().fetchBalance()
                }
                ServiceType.COMMANDCODE_GO -> retryOnTransientError {
                    commandCodeGoRepositoryProvider.get().fetchBalance()
                }
                ServiceType.CODEX -> retryOnTransientError {
                    codexRepositoryProvider.get().fetchBalance()
                }
                ServiceType.OLLAMA -> retryOnTransientError {
                    ollamaRepositoryProvider.get().fetchBalance()
                }
            }
        }

    suspend fun fetchCodexModels(): Result<List<String>> =
        codexRepositoryProvider.get().fetchModels()

    suspend fun triggerCodexUsage(model: String): Result<TriggerSummary> =
        withCredentialSession(ServiceType.CODEX) {
            codexRepositoryProvider.get().triggerUsage(model)
        }

    suspend fun fetchOpenCodeGoModels(): Result<List<String>> =
        openCodeGoRepositoryProvider.get().fetchModels()

    suspend fun triggerOpenCodeGoUsage(model: String): Result<TriggerSummary> =
        withCredentialSession(ServiceType.OPENCODE_GO) {
            openCodeGoRepositoryProvider.get().triggerUsage(model)
        }

    suspend fun fetchOllamaModels(): Result<List<String>> =
        ollamaRepositoryProvider.get().fetchModels()

    suspend fun triggerOllamaUsage(model: String): Result<TriggerSummary> =
        withCredentialSession(ServiceType.OLLAMA) {
            ollamaRepositoryProvider.get().triggerUsage(model)
        }

    private suspend fun <T> withCredentialSession(
        service: ServiceType,
        block: suspend () -> Result<T>
    ): Result<T> {
        val snapshot = credentialRepository.snapshot(service)
            ?: return Result.failure(RepositoryError.InvalidCredential())
        val session = RefreshWriteSession(snapshot)

        val result = try {
            withContext(session) { block() }
        } catch (cancelled: CancellationException) {
            // Codex refresh_token 是单次轮换的：服务端可能已经作废旧 token，而新 token
            // 已暂存在 session。即使 Widget 超时取消，也必须先在不可取消区提交凭据；
            // 余额不提交，因为原业务请求没有正常完成。
            commitIgnoringCancellation(session, includeBalance = false)
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(RepositoryError.Unknown(error))
        }

        val committed = try {
            withContext(NonCancellable) {
                credentialRepository.commit(
                    session = session,
                    includeBalance = result.isSuccess
                )
            }
        } catch (error: Throwable) {
            return Result.failure(RepositoryError.Unknown(error))
        }

        return if (committed) {
            result
        } else {
            Result.failure(RepositoryError.CredentialChanged())
        }
    }

    private suspend fun commitIgnoringCancellation(
        session: RefreshWriteSession,
        includeBalance: Boolean
    ) {
        withContext(NonCancellable) {
            try {
                credentialRepository.commit(session, includeBalance)
            } catch (_: Throwable) {
                // 保持原始 CancellationException 语义；提交失败不会伪装成业务成功。
            }
        }
    }
}
