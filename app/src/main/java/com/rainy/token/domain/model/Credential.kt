package com.rainy.token.domain.model

import com.rainy.token.domain.service.ServiceType
import kotlinx.serialization.Serializable

/**
 * 统一凭据密封类。序列化为 JSON 后经 [com.rainy.token.data.local.SecureStorage] 加密存储。
 *
 * - [ApiKeyCredential] 用于 DeepSeek（API Key 形式）
 * - [SessionCredential] 用于 OpenCode Zen/Go（用户登录 dashboard 后粘贴的值）
 * - [CodexCredential] 用于 Codex / ChatGPT Plus（完整 OAuth 凭据，含自动刷新）
 */
@Serializable
sealed class Credential {

    abstract val service: ServiceType

    abstract val lastVerifiedAt: Long

    @Serializable
    data class ApiKeyCredential(
        override val service: ServiceType,
        val key: String,
        override val lastVerifiedAt: Long = 0L
    ) : Credential()

    /**
     * 用于 OpenCode Go：用户在浏览器登录 opencode.ai 后从 dashboard 拿到
     * - [authCookie]: 名为 `auth` 的 cookie 值
     * - [workspaceId]: 浏览器 dashboard URL 中的 workspace ID
     */
    @Serializable
    data class SessionCredential(
        override val service: ServiceType,
        val cookies: List<CookieEntry> = emptyList(),
        /** 优先持久化的 OAuth Token（access_token / id_token），比 Cookie 更稳定。 */
        val token: String? = null,
        /** Token 过期时间（epoch millis），null 表示未知。 */
        val expiresAt: Long? = null,
        /** OpenCode Go 专用：dashboard auth cookie 值（简化为单字段，便于用户粘贴） */
        val authCookie: String? = null,
        /** OpenCode Go 专用：workspace ID */
        val workspaceId: String? = null,
        override val lastVerifiedAt: Long = 0L
    ) : Credential()

    /**
     * 用于 Codex / ChatGPT Plus：完整 OAuth 凭据，支持自动刷新。
     * 用户从 auth.json 粘贴整个 tokens 对象。
     */
    @Serializable
    data class CodexCredential(
        override val service: ServiceType,
        val accessToken: String,
        val refreshToken: String,
        val accountId: String,
        /** 过期时间（epoch millis） */
        val expiresAt: Long,
        override val lastVerifiedAt: Long = 0L
    ) : Credential()
}

/**
 * 单个 Cookie 的完整信息。仅 [name] + [value] 是必须字段；其他字段用于还原
 * 系统 WebView 在登录成功后通过 `Set-Cookie` 头写入的所有元数据。
 */
@Serializable
data class CookieEntry(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val expiresAt: Long? = null,
    val isSecure: Boolean = false,
    val isHttpOnly: Boolean = false
)