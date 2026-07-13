package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import com.rainy.token.data.debug.DebugLog
import java.io.IOException
import javax.inject.Singleton

@Singleton
class CodexRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val WHAM_USAGE = "https://chatgpt.com/backend-api/wham/usage"
        private const val OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val REFRESH_BUFFER_MS = 60L * 60 * 1000
        private const val TAG = "Codex"
    }

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.CODEX)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 Codex 凭据"))
        if (credential !is Credential.CodexCredential)
            return@withContext Result.failure(RepositoryError.InvalidCredential("凭据类型不匹配"))

        val effectiveCred = if (tokenNeedsRefresh(credential)) {
            DebugLog.i(TAG, "access_token 即将过期，尝试刷新（expiresAt=${credential.expiresAt}）")
            when (val r = refreshToken(credential)) {
                is RefreshResult.Success -> {
                    DebugLog.i(TAG, "token 刷新成功，新 expiresAt=${r.cred.expiresAt}")
                    credentialRepository.save(r.cred); r.cred
                }
                is RefreshResult.Failure -> {
                    DebugLog.e(TAG, "token 主动刷新失败: ${r.reason}")
                    credential
                }
            }
        } else credential

        val usageResult = try {
            fetchJson(WHAM_USAGE, effectiveCred.accessToken)
        } catch (e: IOException) {
            DebugLog.e(TAG, "网络异常: ${e.message}")
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: RepositoryError) {
            if (e is RepositoryError.InvalidCredential && effectiveCred == credential) {
                DebugLog.w(TAG, "401 收到，尝试用 refresh_token 二次刷新")
                when (val r = refreshToken(credential)) {
                    is RefreshResult.Success -> {
                        DebugLog.i(TAG, "二次刷新成功")
                        credentialRepository.save(r.cred)
                        try { fetchJson(WHAM_USAGE, r.cred.accessToken) }
                        catch (e2: RepositoryError) { return@withContext Result.failure(e2) }
                        catch (e2: IOException) { return@withContext Result.failure(RepositoryError.Network(e2)) }
                        catch (e2: Throwable) { return@withContext Result.failure(RepositoryError.Unknown(e2)) }
                    }
                    is RefreshResult.Failure -> {
                        DebugLog.e(TAG, "二次刷新也失败: ${r.reason}")
                        return@withContext Result.failure(RepositoryError.InvalidCredential(r.reason))
                    }
                }
            } else return@withContext Result.failure(e)
        } catch (e: Throwable) { return@withContext Result.failure(RepositoryError.Unknown(e)) }

        val windows = try {
            parseUsageWindows(usageResult)
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析 Codex 用量失败: ${e::class.simpleName}: ${e.message}")
            return@withContext Result.failure(RepositoryError.ParseError("Codex 用量响应格式异常: ${e.message ?: e::class.simpleName}"))
        }
        if (windows.isEmpty()) return@withContext Result.failure(RepositoryError.ParseError("未找到 Codex 用量窗口数据"))

        val config = ServiceConfigProvider.get(ServiceType.CODEX)
        val primary = windows.firstOrNull { it.label.contains("h") } ?: windows.first()
        val extras = buildMap {
            windows.forEachIndexed { i, w ->
                put("window_$i.label", w.label)
                put("window_$i.remainingPct", w.remainingPct.toString())
                put("window_$i.resetAt", w.resetAt?.toString() ?: "")
            }
            put("primary.label", primary.label)
            (usageResult["plan_type"] as? JsonPrimitive)?.contentOrNull?.let { put("plan", it) }
            (usageResult["credits"] as? JsonObject)?.let { c ->
                (c["balance"] as? JsonPrimitive)?.floatOrNull?.let { put("usageCredits", it.toString()) }
            }
        }
        val balance = ServiceBalance(ServiceType.CODEX, (100 - primary.remainingPct).coerceIn(0, 100).toDouble(), config.displayUnit, true, null, null, primary.resetAt, extras)
        balanceCache.put(ServiceType.CODEX, balance)
        credentialRepository.save(effectiveCred.copy(lastVerifiedAt = System.currentTimeMillis()))
        Result.success(balance)
    }

    private fun tokenNeedsRefresh(cred: Credential.CodexCredential): Boolean =
        System.currentTimeMillis() >= cred.expiresAt - REFRESH_BUFFER_MS

    private sealed class RefreshResult {
        data class Success(val cred: Credential.CodexCredential) : RefreshResult()
        data class Failure(val reason: String) : RefreshResult()
    }

    private fun refreshToken(cred: Credential.CodexCredential): RefreshResult {
        // OpenAI auth endpoint 要求 form-urlencoded，不能用 JSON（否则返回 401）
        val formBody = "grant_type=refresh_token&refresh_token=${cred.refreshToken}&client_id=$CLIENT_ID"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder().url(OAUTH_TOKEN_URL)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody).build()
        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string()
                    Log.w("CodexRepository", "token refresh failed: HTTP ${resp.code} ${resp.message} body=$errorBody")
                    DebugLog.e(TAG, "token refresh failed: HTTP ${resp.code} ${resp.message}" +
                        (errorBody?.take(200)?.let { " | $it" } ?: ""))
                    // 解析 OpenAI 错误响应，给出用户可读的提示
                    val reason = parseRefreshError(resp.code, errorBody)
                    return@use RefreshResult.Failure(reason)
                }
                val bodyStr = resp.body?.string() ?: return@use RefreshResult.Failure("响应体为空")
                val tr = json.decodeFromString(OAuthRefreshResponse.serializer(), bodyStr)
                // OpenAI 轮换 refresh_token：响应中可能含新 refresh_token，也可能不含（不轮换时）
                RefreshResult.Success(cred.copy(
                    accessToken = tr.accessToken,
                    refreshToken = tr.refreshToken ?: cred.refreshToken,
                    expiresAt = System.currentTimeMillis() + tr.expiresIn * 1000L,
                    lastVerifiedAt = System.currentTimeMillis()
                ))
            }
        } catch (e: Exception) {
            Log.w("CodexRepository", "token refresh exception: ${e::class.simpleName}: ${e.message}")
            DebugLog.e(TAG, "token refresh exception: ${e::class.simpleName}: ${e.message}")
            RefreshResult.Failure("网络异常: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** 把 OpenAI token endpoint 的错误响应翻译成用户可读的中文提示 */
    private fun parseRefreshError(httpCode: Int, errorBody: String?): String {
        if (errorBody == null) return "HTTP $httpCode，无错误详情"
        // 尝试提取 error.message 字段
        val msg = try {
            (json.parseToJsonElement(errorBody) as? JsonObject)?.let { root ->
                (root["error"] as? JsonObject)?.let { err ->
                    (err["message"] as? JsonPrimitive)?.contentOrNull
                }
            }
        } catch (_: Exception) { null }
        return when {
            msg != null && msg.contains("already been used") ->
                "refresh_token 已被使用（被其他工具轮换），请重新导出 auth.json 并导入"
            msg != null && msg.contains("sign in", ignoreCase = true) ->
                "refresh_token 已失效，请重新登录获取新 auth.json"
            httpCode == 401 && msg != null -> "认证失败: $msg"
            httpCode == 401 -> "认证失败 (HTTP 401)，refresh_token 可能已过期"
            httpCode == 400 && msg != null -> "请求参数错误: $msg"
            else -> "HTTP $httpCode: ${msg ?: errorBody.take(100)}"
        }
    }

    private fun fetchJson(url: String, token: String): JsonObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("OpenAI-Beta", "codex-1")
            .header("OAI-Language", "en")
            .header("User-Agent", "codex-reset-tracker/0.1")
            .header("originator", "codex-reset-tracker")
            .header("Authorization", "Bearer $token").get().build()
        val resp = okHttpClient.newCall(request).execute()
        resp.use {
            if (!it.isSuccessful) {
                Log.w("CodexRepository", "fetchJson failed: HTTP ${it.code} ${it.message} url=$url")
                DebugLog.e(TAG, "fetchJson failed: HTTP ${it.code} ${it.message} url=$url")
                throw if (it.code in listOf(401, 403)) RepositoryError.InvalidCredential("HTTP ${it.code}") else RepositoryError.ServerError(it.code)
            }
            return (json.parseToJsonElement(it.body?.string() ?: throw RepositoryError.ParseError("响应体为空")) as? JsonObject)
                ?: throw RepositoryError.ParseError("响应根节点不是 JSON 对象")
        }
    }

    private data class UsageWindow(val label: String, val remainingPct: Int, val resetAt: Long?)

    private fun parseUsageWindows(data: JsonObject): List<UsageWindow> {
        val result = mutableListOf<UsageWindow>()
        fun addWindows(rl: JsonObject?) {
            if (rl == null) return
            for (key in listOf("primary_window", "secondary_window")) {
                val w = rl[key] as? JsonObject ?: continue
                val usedPct = (w["used_percent"] as? JsonPrimitive)?.floatOrNull ?: continue
                val remaining = (100 - usedPct).toInt().coerceIn(0, 100)
                result.add(UsageWindow(durationLabel((w["limit_window_seconds"] as? JsonPrimitive)?.longOrNull), remaining, (w["reset_at"] as? JsonPrimitive)?.longOrNull?.times(1000L)))
            }
        }
        addWindows(data["rate_limit"] as? JsonObject)
        (data["additional_rate_limits"] as? kotlinx.serialization.json.JsonArray)?.forEach { item ->
            if (item is JsonObject) addWindows(item["rate_limit"] as? JsonObject)
        }
        return result
    }

    private fun durationLabel(seconds: Long?): String = when { seconds == null -> "Usage"; seconds / 60.0 >= 10079 -> "每周"; seconds / 60.0 >= 1439 -> "${(seconds / 86400).toInt()}d"; seconds / 60.0 >= 60 -> "${(seconds / 3600).toInt()}h"; else -> "${maxOf(1, (seconds / 60).toInt())}m" }

    @Serializable data class OAuthRefreshResponse(@kotlinx.serialization.SerialName("access_token") val accessToken: String, @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null, @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long = 3600, @kotlinx.serialization.SerialName("token_type") val tokenType: String? = null)
}