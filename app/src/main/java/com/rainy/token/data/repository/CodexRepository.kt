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
        private const val RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
        private const val MODELS_API = "https://models.dev/api.json"
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

    /**
     * 从 models.dev/api.json 获取 OpenAI 可用模型列表。
     * 该 API 不需要认证，返回所有 provider 的模型目录。
     * 参考 OpenCode 的 ModelsDev 服务实现。
     */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(MODELS_API)
                .header("Accept", "application/json")
                .header("User-Agent", "codex-reset-tracker/0.1")
                .get().build()
            val models = okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.e(TAG, "fetchModels: HTTP ${resp.code}")
                    return@withContext Result.failure(RepositoryError.ServerError(resp.code))
                }
                val root = json.parseToJsonElement(resp.body?.string() ?: throw RepositoryError.ParseError("响应体为空")) as? JsonObject
                    ?: throw RepositoryError.ParseError("响应根节点不是 JSON 对象")
                // 结构: { "openai": { "models": { "gpt-5.6": {...}, ... } } }
                val openaiProvider = root["openai"] as? JsonObject
                val modelsObj = openaiProvider?.get("models") as? JsonObject
                modelsObj?.keys?.toList()?.sorted()
                    ?: throw RepositoryError.ParseError("未找到 OpenAI 模型列表")
            }
            if (models.isEmpty()) {
                return@withContext Result.failure(RepositoryError.ParseError("模型列表为空"))
            }
            DebugLog.i(TAG, "fetchModels: 获取到 ${models.size} 个模型")
            Result.success(models)
        } catch (e: IOException) {
            DebugLog.e(TAG, "fetchModels 网络异常: ${e.message}")
            Result.failure(RepositoryError.Network(e))
        } catch (e: RepositoryError) {
            Result.failure(e)
        } catch (e: Throwable) {
            DebugLog.e(TAG, "fetchModels 异常: ${e::class.simpleName}: ${e.message}")
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    /** 从 access_token JWT 中解析 chatgpt_account_id */
    private fun extractAccountId(accessToken: String): String? {
        return try {
            val parts = accessToken.split(".")
            if (parts.size != 3) return null
            // JWT payload 是 Base64Url 编码
            val payload = android.util.Base64.decode(
                parts[1].replace("-", "+").replace("_", "/").padEnd(4, '='), 
                android.util.Base64.DEFAULT
            ).toString(Charsets.UTF_8)
            val json = this.json.parseToJsonElement(payload) as? JsonObject ?: return null
            (json["chatgpt_account_id"] as? JsonPrimitive)?.contentOrNull
                ?: ((json["https://api.openai.com/auth"] as? JsonObject)?.get("chatgpt_account_id") as? JsonPrimitive)?.contentOrNull
                ?: (json["organizations"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.let { (it as? JsonObject)?.get("id") as? JsonPrimitive }?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 一键激活用量：向 ChatGPT responses API 发送一条请求。
     * @param model 用户选择的模型 slug
     * @return Result.success(响应体原文) — 供 UI 展示
     */
    suspend fun triggerUsage(model: String): Result<String> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.CODEX)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 Codex 凭据"))
        if (credential !is Credential.CodexCredential)
            return@withContext Result.failure(RepositoryError.InvalidCredential("凭据类型不匹配"))

        val effectiveCred = ensureToken(credential)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("token 刷新失败"))

        val accountId = extractAccountId(effectiveCred.accessToken)
        DebugLog.i(TAG, "triggerUsage: accountId=$accountId, model=$model")

        val requestBody = """{"model":"$model","input":[{"role":"user","content":[{"type":"input_text","text":"hello"}]}],"stream":true,"store":false}"""
            .toRequestBody("application/json".toMediaType())

        val request = buildPostRequest(RESPONSES_URL, effectiveCred.accessToken, requestBody, accountId)

        try {
            okHttpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                DebugLog.i(TAG, "triggerUsage: HTTP ${resp.code}, body=${bodyStr.take(500)}")
                if (!resp.isSuccessful) {
                    DebugLog.e(TAG, "triggerUsage failed: HTTP ${resp.code} body=${bodyStr.take(200)}")
                    if (resp.code in listOf(401, 403)) {
                        // token 过期，二次刷新重试
                        DebugLog.w(TAG, "triggerUsage: 401，尝试二次刷新")
                        when (val r = refreshToken(credential)) {
                            is RefreshResult.Success -> {
                                credentialRepository.save(r.cred)
                                val retryAccountId = extractAccountId(r.cred.accessToken)
                                val retryRequest = buildPostRequest(RESPONSES_URL, r.cred.accessToken, requestBody, retryAccountId)
                                okHttpClient.newCall(retryRequest).execute().use { resp2 ->
                                    val body2 = resp2.body?.string() ?: ""
                                    DebugLog.i(TAG, "triggerUsage retry: HTTP ${resp2.code}, body=${body2.take(500)}")
                                    if (!resp2.isSuccessful) {
                                        DebugLog.e(TAG, "triggerUsage retry failed: HTTP ${resp2.code}")
                                        return@use Result.failure<String>(
                                            TriggerError(
                                                "HTTP ${resp2.code}",
                                                body2.ifBlank { "无响应体" }
                                            )
                                        )
                                    }
                                    Result.success(parseSseResponse(body2, model))
                                }
                            }
                            is RefreshResult.Failure -> {
                                DebugLog.e(TAG, "triggerUsage: 二次刷新失败: ${r.reason}")
                                return@use Result.failure(TriggerError("token 刷新失败: ${r.reason}", ""))
                            }
                        }
                    } else {
                        return@use Result.failure<String>(
                            TriggerError("HTTP ${resp.code}", bodyStr.ifBlank { "无响应体" })
                        )
                    }
                } else {
                    DebugLog.i(TAG, "triggerUsage: 请求成功，模型=$model")
                    val formatted = parseSseResponse(bodyStr, model)
                    Result.success(formatted)
                }
            }
        } catch (e: IOException) {
            DebugLog.e(TAG, "triggerUsage 网络异常: ${e.message}")
            Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            DebugLog.e(TAG, "triggerUsage 异常: ${e::class.simpleName}: ${e.message}")
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    /** 确保 access_token 有效，返回刷新后的凭据或 null（刷新失败时） */
    private suspend fun ensureToken(credential: Credential.CodexCredential): Credential.CodexCredential? {
        if (!tokenNeedsRefresh(credential)) return credential
        DebugLog.i(TAG, "ensureToken: access_token 即将过期，尝试刷新")
        return when (val r = refreshToken(credential)) {
            is RefreshResult.Success -> {
                credentialRepository.save(r.cred); r.cred
            }
            is RefreshResult.Failure -> {
                DebugLog.e(TAG, "ensureToken: token 刷新失败: ${r.reason}")
                null
            }
        }
    }

    /** 构建 responses POST 请求 */
    private fun buildPostRequest(url: String, token: String, body: okhttp3.RequestBody, accountId: String? = null): Request =
        Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "codex-1")
            .header("OAI-Language", "en")
            .header("User-Agent", "codex-reset-tracker/0.1")
            .header("originator", "codex-reset-tracker")
            .header("Authorization", "Bearer $token")
            .apply {
                if (accountId != null) header("ChatGPT-Account-Id", accountId)
            }
            .post(body).build()

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

/** 携带完整响应体的错误类，供 UI 展示服务端返回的详细信息 */
class TriggerError(val summary: String, val responseBody: String) : Exception(summary)

/**
 * 将 SSE 流响应解析为简洁的文本摘要。
 * 提取：模型回复文本、用量统计（input/output tokens）。
 */
private fun parseSseResponse(sseText: String, model: String): String {
    val sseJson = Json { ignoreUnknownKeys = true }
    val outputText = StringBuilder()
    var inputTokens: String? = null
    var outputTokens: String? = null
    var responseId: String? = null

    for (line in sseText.lines()) {
        if (!line.startsWith("data: ")) continue
        val jsonStr = line.removePrefix("data: ").trim()
        if (jsonStr == "[DONE]") continue
        try {
            val obj = sseJson.parseToJsonElement(jsonStr) as? JsonObject ?: continue
            when (obj["type"]?.toString()?.trim('"')) {
                "response.output_text.done" -> {
                    // 最终文本
                    (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { outputText.clear(); outputText.append(it) }
                }
                "response.completed" -> {
                    val resp = obj["response"] as? JsonObject
                    responseId = (resp?.get("id") as? JsonPrimitive)?.contentOrNull
                    val usage = resp?.get("usage") as? JsonObject
                    inputTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.contentOrNull
                    outputTokens = (usage?.get("output_tokens") as? JsonPrimitive)?.contentOrNull
                }
            }
        } catch (_: Exception) { }
    }

    val sb = StringBuilder()
    sb.appendLine("✓ 激活成功")
    sb.appendLine("模型: $model")
    if (responseId != null) sb.appendLine("Response ID: $responseId")
    sb.appendLine()
    sb.appendLine("回复:")
    sb.appendLine(outputText.ifEmpty { "(空)" })
    if (inputTokens != null || outputTokens != null) {
        sb.appendLine()
        sb.appendLine("用量: input=$inputTokens output=$outputTokens tokens")
    }
    return sb.toString().trim()
}