package com.rainy.token.data.repository

import android.util.Base64
import com.rainy.token.data.debug.DebugLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Codex / ChatGPT Plus OAuth PKCE 辅助工具。
 *
 * 参考：7shi/codex-oauth 的 Python 实现，移植到 Kotlin/Android。
 * 流程：
 *  1. 生成 code_verifier（随机 96 字节 base64url）
 *  2. 计算 code_challenge = SHA256(code_verifier) base64url 无填充
 *  3. 构建 auth URL → 用户在 WebView 中登录
 *  4. 拦截 localhost:1455/auth/callback?code=xxx&state=xxx
 *  5. POST token endpoint 用 code + code_verifier 换 access/refresh token
 *  6. 从 JWT (id_token / access_token) 提取 chatgpt_account_id
 */
object CodexOAuthHelper {

    private const val TAG = "CodexOAuth"

    const val AUTH_URL = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    const val SCOPE = "openid profile email offline_access"
    const val CALLBACK_PREFIX = "http://localhost:1455/auth/callback"

    private val json = Json { ignoreUnknownKeys = true }

    /** PKCE 参数对 */
    data class PkcePair(val codeVerifier: String, val codeChallenge: String)

    /** 生成 PKCE code_verifier 和 code_challenge (S256) */
    fun generatePkce(): PkcePair {
        val randomBytes = ByteArray(96)
        SecureRandom().nextBytes(randomBytes)
        val codeVerifier = Base64.encodeToString(
            randomBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())
        val codeChallenge = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return PkcePair(codeVerifier, codeChallenge)
    }

    /** 生成随机 state（CSRF 防护） */
    fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** 构建授权 URL */
    fun buildAuthUrl(codeChallenge: String, state: String): String {
        val params = mapOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPE,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to "opencode"
        )
        // 手动拼 URL（不用 URLEncoder.encode，因为 OAuth 参数不需要编码特殊字符）
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTH_URL?$query"
    }

    /** Token 交换响应 */
    @Serializable
    data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String,
        @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long = 3600,
        @kotlinx.serialization.SerialName("id_token") val idToken: String? = null,
        @kotlinx.serialization.SerialName("token_type") val tokenType: String? = null
    )

    /** 用 authorization code 换 token */
    fun exchangeCode(
        okHttpClient: OkHttpClient,
        code: String,
        codeVerifier: String
    ): TokenResponse? {
        val formBody = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(java.net.URLEncoder.encode(code, "UTF-8"))
            append("&redirect_uri=").append(java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&client_id=").append(CLIENT_ID)
            append("&code_verifier=").append(codeVerifier)
        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string()
                    DebugLog.e(TAG, "token exchange failed: HTTP ${resp.code} | $errorBody")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                json.decodeFromString(TokenResponse.serializer(), body)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "token exchange exception: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 从 JWT 中提取 chatgpt_account_id（不验证签名，只读 payload）。
     * 3 级回退：
     *   1. payload.chatgpt_account_id
     *   2. payload["https://api.openai.com/auth"].chatgpt_account_id
     *   3. payload.organizations[0].id
     */
    fun extractAccountId(idToken: String?, accessToken: String?): String? {
        for (token in listOfNotNull(idToken, accessToken)) {
            val payload = decodeJWTPayload(token) ?: continue
            // 1. top-level
            payload["chatgpt_account_id"]?.jsonPrimitive?.content?.let { return it }
            // 2. nested namespace
            payload["https://api.openai.com/auth"]?.jsonObject
                ?.get("chatgpt_account_id")?.jsonPrimitive?.content?.let { return it }
            // 3. organizations[0].id
            (payload["organizations"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.get("id")?.jsonPrimitive?.content?.let { return it }
        }
        return null
    }

    /** 解码 JWT payload（第二段），不验证签名 */
    private fun decodeJWTPayload(jwt: String): JsonObject? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payloadB64 = parts[1]
            // base64url decode，补 padding
            val decoded = Base64.decode(
                payloadB64,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            json.parseToJsonElement(String(decoded)).jsonObject
        } catch (_: Exception) {
            null
        }
    }
}