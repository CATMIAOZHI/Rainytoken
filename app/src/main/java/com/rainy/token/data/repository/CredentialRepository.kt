package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.CachedBalance
import com.rainy.token.data.local.SecureStorage
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 凭据的统一读写入口。封装 SecureStorage 的 key 命名规则（用 [ServiceType.storageKey]），
 * 把 JSON 反序列化成密封类 [Credential]。
 *
 * 所有凭据变更都经过 [mutationMutex] 串行化，并维护进程内 revision。余额刷新先取得
 * [CredentialSnapshot]，网络完成后只有快照仍为当前版本时才能提交凭据与缓存，防止
 * 旧请求覆盖用户刚保存、替换或删除的凭据。
 */
@Singleton
class CredentialRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val balanceCache: BalanceCache
) {

    internal data class CredentialSnapshot(
        val credential: Credential,
        val revision: Long,
        val fingerprint: String
    ) {
        val service: ServiceType get() = credential.service
    }

    internal data class LocalState(
        val status: CredentialStatus,
        val fingerprint: String?,
        val cachedBalance: CachedBalance?
    )

    private val mutationMutex = Mutex()
    private val revisions = mutableMapOf<ServiceType, Long>()

    private fun keyFor(service: ServiceType): String = "credential_${service.storageKey}"

    suspend fun save(credential: Credential) {
        currentCoroutineContext()[RefreshWriteSession]?.let { session ->
            session.stageCredential(credential)
            return
        }

        mutationMutex.withLock {
            val current = getUnlocked(credential.service)
            val cacheIdentityChanged =
                cacheIdentityFingerprint(current) != cacheIdentityFingerprint(credential)

            // 先推进 revision，使已在途的旧快照立即失效。即使后续存储异常，旧请求也不能回写。
            bumpRevision(credential.service)
            if (cacheIdentityChanged) {
                balanceCache.remove(credential.service)
            }
            putUnlocked(credential)
        }
    }

    suspend fun get(service: ServiceType): Credential? {
        currentCoroutineContext()[RefreshWriteSession]?.let { session ->
            if (session.snapshot.service == service) {
                return session.credentialForRead()
            }
        }
        return mutationMutex.withLock { getUnlocked(service) }
    }

    /** 在凭据互斥区内读取凭据、状态与对应缓存，避免账户切换时拼出跨版本状态。 */
    internal suspend fun readLocalState(service: ServiceType): LocalState =
        mutationMutex.withLock {
            val credential = getUnlocked(service)
            val cached = balanceCache.get(service)
            localStateOf(service, credential, cached)
        }

    /** Dashboard 一次性读取所有服务，缓存与凭据来自同一受保护快照。 */
    internal suspend fun readLocalStates(): Map<ServiceType, LocalState> =
        mutationMutex.withLock {
            val cached = balanceCache.getAll()
            ServiceType.entries.associateWith { service ->
                val credential = getUnlocked(service)
                localStateOf(service, credential, cached[service])
            }
        }

    suspend fun remove(service: ServiceType) {
        mutationMutex.withLock {
            bumpRevision(service)
            balanceCache.remove(service)
            secureStorage.remove(keyFor(service))
        }
    }

    /** 获取一次刷新使用的不可变凭据快照。 */
    internal suspend fun snapshot(service: ServiceType): CredentialSnapshot? =
        mutationMutex.withLock {
            val credential = getUnlocked(service) ?: return@withLock null
            CredentialSnapshot(
                credential = credential,
                revision = revisionFor(service),
                fingerprint = credentialFingerprint(credential)!!
            )
        }

    /**
     * 提交 [RefreshWriteSession] 中暂存的写入。
     *
     * 只有 revision 和认证字段指纹都仍与请求开始时一致才允许提交。凭据与余额缓存在
     * 同一个凭据互斥区内更新；若用户期间保存/删除了凭据，本次旧结果会被完整丢弃。
     */
    internal suspend fun commit(session: RefreshWriteSession): Boolean =
        mutationMutex.withLock {
            val snapshot = session.snapshot
            val current = getUnlocked(snapshot.service) ?: return@withLock false
            if (
                !snapshotMatches(
                    snapshotRevision = snapshot.revision,
                    snapshotFingerprint = snapshot.fingerprint,
                    currentRevision = revisionFor(snapshot.service),
                    currentFingerprint = credentialFingerprint(current)
                )
            ) return@withLock false

            val pendingCredential = session.stagedCredential()
            val pendingBalance = session.stagedBalance()
            if (pendingCredential == null && pendingBalance == null) return@withLock true

            val finalCredential = pendingCredential ?: current
            val cacheIdentityChanged =
                cacheIdentityFingerprint(current) != cacheIdentityFingerprint(finalCredential)

            // 在任何持久化副作用前推进 revision，阻止另一份同快照请求随后提交。
            bumpRevision(snapshot.service)
            if (cacheIdentityChanged) {
                balanceCache.remove(snapshot.service)
            }
            if (pendingCredential != null) {
                putUnlocked(pendingCredential)
            }
            if (pendingBalance != null) {
                balanceCache.put(snapshot.service, pendingBalance)
            }
            true
        }

    /** 读取并转换为 UI 用的 [CredentialStatus]。 */
    suspend fun statusFor(service: ServiceType): CredentialStatus {
        val credential = get(service) ?: return CredentialStatus(
            service = service,
            state = CredentialStatus.State.NOT_CONFIGURED,
            lastVerifiedAt = 0L
        )
        return CredentialStatus(
            service = service,
            state = determineCredentialState(
                credential.lastVerifiedAt,
                System.currentTimeMillis()
            ),
            lastVerifiedAt = credential.lastVerifiedAt
        )
    }

    suspend fun statusForAll(): List<CredentialStatus> =
        ServiceConfigProvider.all().map { statusFor(it.type) }

    private fun localStateOf(
        service: ServiceType,
        credential: Credential?,
        cached: CachedBalance?
    ): LocalState {
        val status = if (credential == null) {
            CredentialStatus(
                service = service,
                state = CredentialStatus.State.NOT_CONFIGURED,
                lastVerifiedAt = 0L
            )
        } else {
            CredentialStatus(
                service = service,
                state = determineCredentialState(
                    credential.lastVerifiedAt,
                    System.currentTimeMillis()
                ),
                lastVerifiedAt = credential.lastVerifiedAt
            )
        }
        return LocalState(
            status = status,
            fingerprint = credentialFingerprint(credential),
            cachedBalance = cached
        )
    }

    private suspend fun putUnlocked(credential: Credential) {
        secureStorage.put(
            key = keyFor(credential.service),
            value = credential,
            serializer = Credential.serializer()
        )
    }

    private suspend fun getUnlocked(service: ServiceType): Credential? =
        secureStorage.get(
            key = keyFor(service),
            serializer = Credential.serializer()
        )

    private fun revisionFor(service: ServiceType): Long = revisions[service] ?: 0L

    private fun bumpRevision(service: ServiceType) {
        revisions[service] = revisionFor(service) + 1L
    }

    companion object {
        /**
         * 根据上次验证时间判断凭据状态。纯函数，便于单元测试。
         *
         * - [lastVerifiedAt] == 0 → WARNING（已保存但未验证）
         * - 距上次验证 > 7 天 → WARNING
         * - 否则 → OK
         */
        internal fun determineCredentialState(lastVerifiedAt: Long, now: Long): CredentialStatus.State {
            return when {
                lastVerifiedAt == 0L -> CredentialStatus.State.WARNING
                now - lastVerifiedAt > 7L * 24 * 3600 * 1000 -> CredentialStatus.State.WARNING
                else -> CredentialStatus.State.OK
            }
        }

        /**
         * 余额缓存所属账户的不可逆标识。
         *
         * 与 [credentialFingerprint] 不同，Codex 的短期 Token 轮换不会改变账户标识；
         * SessionCredential 的触发用 API Key 也不会影响余额账户。无法取得稳定账户 ID
         * 的服务采用其主要认证字段，宁可保守失效缓存，也不跨账户展示余额。
         */
        internal fun cacheIdentityFingerprint(credential: Credential?): String? {
            if (credential == null) return null
            val material = buildString {
                field("service", credential.service.storageKey)
                when (credential) {
                    is Credential.ApiKeyCredential -> {
                        field("type", "api-key")
                        field("key", credential.key)
                    }
                    is Credential.SessionCredential -> {
                        field("type", "session")
                        field("token", credential.token)
                        field("authCookie", credential.authCookie)
                        field("workspaceId", credential.workspaceId)
                        field("ollamaCookie", credential.ollamaCookie)
                        credential.cookies
                            .sortedWith(
                                compareBy(
                                    { it.name },
                                    { it.domain.orEmpty() },
                                    { it.path.orEmpty() },
                                    { it.value }
                                )
                            )
                            .forEachIndexed { index, cookie ->
                                field("cookie[$index].name", cookie.name)
                                field("cookie[$index].value", cookie.value)
                                field("cookie[$index].domain", cookie.domain)
                                field("cookie[$index].path", cookie.path)
                            }
                    }
                    is Credential.CodexCredential -> {
                        field("type", "codex")
                        if (credential.accountId.isNotBlank()) {
                            field("accountId", credential.accountId)
                        } else {
                            field("accessToken", credential.accessToken)
                            field("refreshToken", credential.refreshToken)
                        }
                    }
                }
            }
            return sha256(material)
        }

        /** 纯函数：revision 与认证指纹都一致时，旧请求才允许提交。 */
        internal fun snapshotMatches(
            snapshotRevision: Long,
            snapshotFingerprint: String,
            currentRevision: Long,
            currentFingerprint: String?
        ): Boolean =
            snapshotRevision == currentRevision && snapshotFingerprint == currentFingerprint

        /**
         * 对认证相关字段生成不可逆 SHA-256 指纹。
         *
         * 指纹不包含 lastVerifiedAt 等展示元数据；Codex 明确包含 refreshToken，Session
         * 同时包含 Cookie 列表及各服务专用认证字段。返回值可安全用于内存状态比较，
         * 不会额外保留 API Key、Cookie 或 Token 原文。
         */
        internal fun credentialFingerprint(credential: Credential?): String? {
            if (credential == null) return null
            val material = buildString {
                field("service", credential.service.storageKey)
                when (credential) {
                    is Credential.ApiKeyCredential -> {
                        field("type", "api-key")
                        field("key", credential.key)
                    }
                    is Credential.SessionCredential -> {
                        field("type", "session")
                        field("token", credential.token)
                        field("authCookie", credential.authCookie)
                        field("workspaceId", credential.workspaceId)
                        field("ollamaCookie", credential.ollamaCookie)
                        field("apiKey", credential.apiKey)
                        credential.cookies
                            .sortedWith(
                                compareBy(
                                    { it.name },
                                    { it.domain.orEmpty() },
                                    { it.path.orEmpty() },
                                    { it.value }
                                )
                            )
                            .forEachIndexed { index, cookie ->
                                field("cookie[$index].name", cookie.name)
                                field("cookie[$index].value", cookie.value)
                                field("cookie[$index].domain", cookie.domain)
                                field("cookie[$index].path", cookie.path)
                                field("cookie[$index].expiresAt", cookie.expiresAt?.toString())
                                field("cookie[$index].secure", cookie.isSecure.toString())
                                field("cookie[$index].httpOnly", cookie.isHttpOnly.toString())
                            }
                    }
                    is Credential.CodexCredential -> {
                        field("type", "codex")
                        field("accessToken", credential.accessToken)
                        field("refreshToken", credential.refreshToken)
                        field("accountId", credential.accountId)
                    }
                }
            }
            return sha256(material)
        }

        private fun StringBuilder.field(name: String, value: String?) {
            append(name)
            append('=')
            if (value == null) {
                append("-1:")
            } else {
                append(value.length)
                append(':')
                append(value)
            }
            append(';')
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            val chars = CharArray(digest.size * 2)
            val hex = "0123456789abcdef"
            digest.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                chars[index * 2] = hex[unsigned ushr 4]
                chars[index * 2 + 1] = hex[unsigned and 0x0f]
            }
            return String(chars)
        }
    }
}
