package com.rainy.token.data.repository

import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 单次余额/用量请求的延迟写入会话。
 *
 * Repository 仍可按原有顺序调用 CredentialRepository.save() 与 BalanceCache.put()，
 * 但在该上下文中写入只会暂存。请求结束后由 CredentialRepository 在同一互斥区内
 * 校验凭据快照并一次性提交，避免旧请求覆盖刚保存或删除的新凭据。
 */
internal class RefreshWriteSession(
    val snapshot: CredentialRepository.CredentialSnapshot
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<RefreshWriteSession>

    private val stateLock = Any()
    private var pendingCredential: Credential? = null
    private var pendingBalance: ServiceBalance? = null

    fun stageCredential(credential: Credential) {
        require(credential.service == snapshot.service) {
            "刷新会话服务不匹配：expected=${snapshot.service}, actual=${credential.service}"
        }
        synchronized(stateLock) {
            pendingCredential = mergeCredential(pendingCredential, credential)
        }
    }

    fun stageBalance(service: ServiceType, balance: ServiceBalance) {
        require(service == snapshot.service && balance.service == snapshot.service) {
            "刷新缓存服务不匹配：expected=${snapshot.service}, key=$service, balance=${balance.service}"
        }
        synchronized(stateLock) {
            pendingBalance = balance
        }
    }

    /** 同一刷新会话内始终读取快照或已暂存的新凭据，保证重试不会退回旧 Token。 */
    fun credentialForRead(): Credential = synchronized(stateLock) {
        pendingCredential ?: snapshot.credential
    }

    fun stagedCredential(): Credential? = synchronized(stateLock) { pendingCredential }

    fun stagedBalance(): ServiceBalance? = synchronized(stateLock) { pendingBalance }

    private fun mergeCredential(current: Credential?, candidate: Credential): Credential {
        if (current == null) return candidate

        val baseFingerprint = snapshot.fingerprint
        val currentChanged = CredentialRepository.credentialFingerprint(current) != baseFingerprint
        val candidateChanged = CredentialRepository.credentialFingerprint(candidate) != baseFingerprint

        // Codex 可能先暂存轮换后的 token，随后又用请求开始时的旧 credential
        // 仅更新 lastVerifiedAt。此时必须保留已轮换的认证字段，不能退回旧 token。
        val selected = when {
            currentChanged && !candidateChanged -> current
            !currentChanged && candidateChanged -> candidate
            else -> candidate
        }
        return selected.withLastVerifiedAt(maxOf(current.lastVerifiedAt, candidate.lastVerifiedAt))
    }
}

private fun Credential.withLastVerifiedAt(value: Long): Credential = when (this) {
    is Credential.ApiKeyCredential -> copy(lastVerifiedAt = value)
    is Credential.SessionCredential -> copy(lastVerifiedAt = value)
    is Credential.CodexCredential -> copy(lastVerifiedAt = value)
}
