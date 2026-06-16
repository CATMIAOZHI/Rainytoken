package com.rainy.token.data.repository

import android.webkit.CookieManager
import com.rainy.token.domain.model.CookieEntry
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从系统 [CookieManager] 抓取所有 Cookie 并保存为 [Credential.SessionCredential]。
 *
 * 注意：Android 系统 [CookieManager.getCookie] 返回的是 `name=value` 拼接字符串，
 * 不包含 domain / path / expires / secure / httpOnly。要拿到完整信息需要
 * 配合 `ReflectiveCookieManager` 或自己解析 `Set-Cookie` 响应头。
 *
 * 当前实现只抓 `name=value`（计划 4.1），完整字段留作 TODO。
 */
@Singleton
class WebViewSessionSaver @Inject constructor(
    private val credentialRepository: CredentialRepository
) {

    /**
     * 提取并保存 Cookie 列表。返回保存后的凭据。
     */
    fun saveFromCookieManager(
        service: ServiceType,
        url: String,
        token: String? = null,
        expiresAt: Long? = null
    ): Credential.SessionCredential? {
        val cookieString = CookieManager.getInstance().getCookie(url) ?: return null
        val cookies = parseCookieString(cookieString)
        if (cookies.isEmpty() && token == null) return null

        val session = Credential.SessionCredential(
            service = service,
            cookies = cookies,
            token = token,
            expiresAt = expiresAt,
            lastVerifiedAt = System.currentTimeMillis()
        )
        // 用 runBlocking 写凭据 —— 调用方在 Composable 中，可以接受
        kotlinx.coroutines.runBlocking {
            credentialRepository.save(session)
        }
        return session
    }

    /**
     * 把 `name1=value1; name2=value2` 解析为 [CookieEntry] 列表。
     * 完整属性（domain/path/expires/secure/httpOnly）暂不可用，因为 Android CookieManager
     * 不提供这些字段；如需完整字段，需要在 WebViewClient 中拦截 `Set-Cookie` 响应头自行解析。
     */
    private fun parseCookieString(cookieString: String): List<CookieEntry> {
        return cookieString.split(";")
            .mapNotNull { entry ->
                val parts = entry.trim().split("=", limit = 2)
                if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
                CookieEntry(name = parts[0].trim(), value = parts[1].trim())
            }
    }
}