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
 * 凭据的统一读写入口。
 *
 * 所有凭据变更都经过 [mutationMutex] 串行化，并维护进程内 revision。网络刷新先取得
 * [CredentialSnapshot]；请求结束后只有快照仍为当前版本时，暂存的凭据与余额才会提交。
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

    private data class CacheRollbackEntry(
        val testedFingerprint: String,
        val previousIdentity: String?,
        val cachedBalance: CachedBalance?
    )

    private val mutationMutex = Mutex()
    private val revisions = mutableMapOf<ServiceType, Long>()
    private val cacheRollbackEntries = mutableMapOf<ServiceType, CacheRollbackEntry>()

    private fun keyFor(service: ServiceType): String = "credential_${service.storageKey}"

    suspend fun save(credential: Credential) {
        currentCoroutineContext()[RefreshWriteSession]?.let { session ->
            session.stageCredential(credential)
            return
        }

        mutationMutex.withLock {
            val service = credential.service
            val current = getUnlocked(service)
            val currentIdentity = cacheIdentityFingerprint(current)
            val newIdentity = cacheIdentityFingerprint(credential)
            val cacheIdentityChanged = currentIdentity != newIdentity

            bumpRevision(service)
            if (cacheIdentityChanged) {
                cacheRollbackEntries[service] = CacheRollbackEntry(
                    testedFingerprint = credentialFingerprint(credential)!!,
                    previousIdentity = currentIdentity,
                    cachedBalance = if (current != null) balanceCache.get(service) else null
                )
                balanceCache.remove(service)
            } else {
                cacheRollbackEntries.remove(service)
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

    internal suspend fun readLocalState(service: ServiceType): LocalState =
        mutationMutex.withLock {
            val credential = getUnlocked(service)
            localStateOf(service, credential, balanceCache.get(service))
        }

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
            cacheRollbackEntries.remove(service)
            balanceCache.remove(service)
            secureStorage.remove(keyFor(service))
        }
    }

    /**
     * 仅当测试期间凭据 revision 与认证指纹都未变化时恢复旧凭据。
     * 若替换凭据时清除了旧账户缓存，这里会一并恢复原缓存和原 fetchedAt。
     */
    internal suspend fun restoreIfCurrent(
        testedSnapshot: CredentialSnapshot,
        previous: Credential?
    ): Boolean = mutationMutex.withLock {
        val service = testedSnapshot.service
        val current = getUnlocked(service) ?: return@withLock false
        if (
            !snapshotMatches(
                snapshotRevision = testedSnapshot.revision,
                snapshotFingerprint = testedSnapshot.fingerprint,
                currentRevision = revisionFor(service),
                currentFingerprint = credentialFingerprint(current)
            )
        ) return@withLock false

        val currentIdentity = cacheIdentityFingerprint(current)
        val previousIdentity = cacheIdentityFingerprint(previous)
        val identityChanged = currentIdentity != previousIdentity
        val rollbackEntry = cacheRollbackEntries[service]?.takeIf {
            it.testedFingerprint == testedSnapshot.fingerprint &&
                it.previousIdentity == previousIdentity
        }

        bumpRevision(service)
        if (identityChanged) {
            balanceCache.remove(service)
        }
        if (previous == null) {
            secureStorage.remove(keyFor(service))
        } else {
            require(previous.service == service) { "回滚凭据服务不匹配" }
            putUnlocked(previous)
        }
        if (identityChanged) {
            rollbackEntry?.cachedBalance?.let { balanceCache.putCached(service, it) }
        }
        cacheRollbackEntries.remove(service)
        true
    }

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
     * 成功且完全只读的请求不参与 revision 竞争。存在写入时通常要求快照完全匹配；
     * 唯一例外是认证字段已经轮换、当前持久化认证仍等于请求起点且属于同一刷新链，
     * 此时允许新 Token 越过另一条同账户请求造成的纯 revision 变化。
     */
    internal suspend fun commit(
        session: RefreshWriteSession,
        includeBalance: Boolean
    ): Boolean = mutationMutex.withLock {
        val pendingCredential = session.stagedCredential()
        val pendingBalance = session.stagedBalance().takeIf { includeBalance }
        val hasPendingWrites = pendingCredential != null || pendingBalance != null
        if (!hasPendingWrites && includeBalance) return@withLock true

        val snapshot = session.snapshot
        val current = getUnlocked(snapshot.service) ?: return@withLock false
        val currentFingerprint = credentialFingerprint(current)
        val snapshotStillCurrent = snapshotMatches(
            snapshotRevision = snapshot.revision,
            snapshotFingerprint = snapshot.fingerprint,
            currentRevision = revisionFor(snapshot.service),
            currentFingerprint = currentFingerprint
        )
        val canMergeRotatedCredential = pendingCredential != null &&
            credentialFingerprint(pendingCredential) != snapshot.fingerprint &&
            currentFingerprint == snapshot.fingerprint &&
            sameRefreshLineage(snapshot.credential, pendingCredential)

        if (!snapshotStillCurrent && !canMergeRotatedCredential) return@withLock false
        if (!hasPendingWrites) return@withLock true

        val finalCredential = pendingCredential ?: current
        val cacheIdentityChanged =
            cacheIdentityFingerprint(current) != cacheIdentityFingerprint(finalCredential)

        bumpRevision(snapshot.service)
        if (cacheIdentityChanged) {
            balanceCache.remove(snapshot.service)
        }
        if (pendingCredential != null) {
            putUnlocked(pendingCredential)
        }
        if (pendingBalance != null) {
            balanceCache.put(snapshot.service, pendingBalance)
            cacheRollbackEntries.remove(snapshot.service)
        }
        true
    }

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
        internal fun determineCredentialState(
            lastVerifiedAt: Long,
            now: Long
        ): CredentialStatus.State = when {
            lastVerifiedAt == 0L -> CredentialStatus.State.WARNING
            now - lastVerifiedAt > 7L * 24 * 3600 * 1000 -> CredentialStatus.State.WARNING
            else -> CredentialStatus.State.OK
        }

        /**
         * 判断认证更新是否来自同一条合法刷新链。Codex 的 refresh 响应由旧凭据 copy
         * 产生，因此 accountId（即使为空）必须保持一致；其他类型要求余额账户身份一致。
         */
        internal fun sameRefreshLineage(
            original: Credential,
            updated: Credential
        ): Boolean = when {
            original is Credential.CodexCredential && updated is Credential.CodexCredential ->
                original.service == updated.service && original.accountId == updated.accountId
            else -> cacheIdentityFingerprint(original) == cacheIdentityFingerprint(updated)
        }

        /**
         * 余额缓存所属账户的不可逆标识。Codex 的短期 Token 轮换不会改变账户标识；
         * SessionCredential 的触发用 API Key 也不会影响余额账户。
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
                        credential.cookies.sortedForFingerprint().forEachIndexed { index, cookie ->
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

        internal fun snapshotMatches(
            snapshotRevision: Long,
            snapshotFingerprint: String,
            currentRevision: Long,
            currentFingerprint: String?
        ): Boolean =
            snapshotRevision == currentRevision && snapshotFingerprint == currentFingerprint

        /** 对认证相关字段生成不可逆 SHA-256 指纹；不包含 lastVerifiedAt。 */
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
                        credential.cookies.sortedForFingerprint().forEachIndexed { index, cookie ->
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

        private fun List<com.rainy.token.domain.model.CookieEntry>.sortedForFingerprint() =
            sortedWith(
                compareBy(
                    { it.name },
                    { it.domain.orEmpty() },
                    { it.path.orEmpty() },
                    { it.value }
                )
            )

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
