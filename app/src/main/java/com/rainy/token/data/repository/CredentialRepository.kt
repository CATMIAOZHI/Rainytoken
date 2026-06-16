package com.rainy.token.data.repository

import com.rainy.token.data.local.SecureStorage
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据的统一读写入口。封装 SecureStorage 的 key 命名规则（用 [ServiceType.storageKey]），
 * 把 JSON 反序列化成密封类 [Credential]。
 */
@Singleton
class CredentialRepository @Inject constructor(
    private val secureStorage: SecureStorage
) {

    private fun keyFor(service: ServiceType): String = "credential_${service.storageKey}"

    suspend fun save(credential: Credential) {
        secureStorage.put(
            key = keyFor(credential.service),
            value = credential,
            serializer = Credential.serializer()
        )
    }

    suspend fun get(service: ServiceType): Credential? =
        secureStorage.get(
            key = keyFor(service),
            serializer = Credential.serializer()
        )

    suspend fun remove(service: ServiceType) {
        secureStorage.remove(keyFor(service))
    }

    /**
     * 读取并转换为 UI 用的 [CredentialStatus]。未配置/已删除都返回 NOT_CONFIGURED。
     */
    suspend fun statusFor(service: ServiceType): CredentialStatus {
        val credential = get(service) ?: return CredentialStatus(
            service = service,
            state = CredentialStatus.State.NOT_CONFIGURED,
            lastVerifiedAt = 0L
        )
        val now = System.currentTimeMillis()
        // 简单启发：最近 7 天内有验证 → OK；否则按 lastVerifiedAt 是否为 0 判断
        val state = when {
            credential.lastVerifiedAt == 0L -> CredentialStatus.State.WARNING
            now - credential.lastVerifiedAt > 7L * 24 * 3600 * 1000 -> CredentialStatus.State.WARNING
            else -> CredentialStatus.State.OK
        }
        return CredentialStatus(
            service = service,
            state = state,
            lastVerifiedAt = credential.lastVerifiedAt
        )
    }

    suspend fun statusForAll(): List<CredentialStatus> =
        ServiceConfigProvider.all().map { statusFor(it.type) }
}