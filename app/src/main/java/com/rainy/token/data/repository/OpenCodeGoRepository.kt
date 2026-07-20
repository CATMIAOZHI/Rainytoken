package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Singleton

/**
 * OpenCode Go 配额仓库。计划阶段 5.3（已根据 [slkiser/opencode-quota](https://github.com/slkiser/opencode-quota)
 * 调整实现）：
 *
 * - 用户在浏览器登录 https://opencode.ai/auth（GitHub / Google）
 * - 登录后访问 dashboard，URL 形如 `https://opencode.ai/workspace/{workspaceId}/go`
 * - 用户从 dashboard URL 中复制 `workspaceId` + 浏览器 DevTools 的 `auth` cookie 值
 * - 粘贴到 APP，APP 用 OkHttp 携带 Cookie 抓取该 URL
 * - 解析 HTML 中 SolidJS SSR hydration 字段 `rollingUsage` / `weeklyUsage` / `monthlyUsage`
 *
 * 不在类上加 @Inject constructor —— 在 [com.rainy.token.di.NetworkModule] 里 @Provides 显式提供。
 * 规避 KSP 2.x 多文件 @Inject 跨依赖的"could not be resolved"误报。
 */
@Singleton
class OpenCodeGoRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.OPENCODE_GO)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.SessionCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val authCookie = credential.authCookie
        val workspaceId = credential.workspaceId
        if (authCookie.isNullOrBlank() || workspaceId.isNullOrBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val url = "https://opencode.ai/workspace/${URLEncoder.encode(workspaceId, "UTF-8")}/go"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Gecko/20100101 Firefox/148.0")
            .header("Accept", "text/html")
            .header("Cookie", "auth=$authCookie")
            .get()
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext Result.failure(RepositoryError.InvalidCredential())
                }
                return@withContext Result.failure(RepositoryError.ServerError(resp.code))
            }

            val html = resp.body?.string() ?: return@withContext Result.failure(
                RepositoryError.ParseError("响应体为空")
            )

            val windows = parseWindows(html)
            if (windows.isEmpty()) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(
                        "解析失败：未找到任何 OpenCode Go 配额窗口。HTML=${html.length} 字节。"
                    )
                )
            }

            // 主体数据用 rollingUsage（5h 滚动窗口），这是用户最关心的"实时配额"
            val primary = windows["rollingUsage"] ?: windows.values.first()
            val config = ServiceConfigProvider.get(ServiceType.OPENCODE_GO)

            // 把 3 个窗口的用量百分比 + 重置时间全部塞进 extras（详情页按窗口渲染）
            val extras = buildMap {
                windows["rollingUsage"]?.let { w ->
                    put("rolling.pct", w.usagePercent.toString())
                    put("rolling.resetInSec", w.resetInSec.toString())
                }
                windows["weeklyUsage"]?.let { w ->
                    put("weekly.pct", w.usagePercent.toString())
                    put("weekly.resetInSec", w.resetInSec.toString())
                }
                windows["monthlyUsage"]?.let { w ->
                    put("monthly.pct", w.usagePercent.toString())
                    put("monthly.resetInSec", w.resetInSec.toString())
                }
            }

            val balance = ServiceBalance(
                service = ServiceType.OPENCODE_GO,
                amount = primary.usagePercent.toDouble(),
                unit = "%",
                isAvailable = true,
                monthlySpent = windows["monthlyUsage"]?.usagePercent?.toDouble(),
                totalQuota = null,
                nextResetAt = System.currentTimeMillis() + primary.resetInSec * 1000L,
                extras = extras
            )

            balanceCache.put(ServiceType.OPENCODE_GO, balance)
            credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))

            Result.success(balance)
        }
    }

    /**
     * 从 models.dev/api.json 获取 OpenCode 可用模型列表（provider key = "opencode"）。
     * 该 API 不需要认证。
     */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(MODELS_API)
                .header("Accept", "application/json")
                .header("User-Agent", "rainy-token/0.1")
                .get().build()
            val models = okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.e(TAG, "fetchModels: HTTP ${resp.code}")
                    return@withContext Result.failure(RepositoryError.ServerError(resp.code))
                }
                val root = json.parseToJsonElement(resp.body?.string() ?: throw RepositoryError.ParseError("响应体为空")) as? JsonObject
                    ?: throw RepositoryError.ParseError("响应根节点不是 JSON 对象")
                val provider = root["opencode"] as? JsonObject
                val modelsObj = provider?.get("models") as? JsonObject
                modelsObj?.keys?.toList()?.sorted()
                    ?: throw RepositoryError.ParseError("未找到 OpenCode 模型列表")
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

    /**
     * 一键激活用量：用 API Key 向 OpenCode chat completions API 发送简短请求。
     * @param model 用户选择的模型 slug
     * @return Result.success(响应摘要文本) — 供 UI 展示
     */
    suspend fun triggerUsage(model: String): Result<String> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.OPENCODE_GO)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 OpenCode Go 凭据"))
        if (credential !is Credential.SessionCredential)
            return@withContext Result.failure(RepositoryError.InvalidCredential("凭据类型不匹配"))

        val apiKey = credential.apiKey
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential("未配置 API Key，请在设置中填写"))
        }

        DebugLog.i(TAG, "triggerUsage: model=$model")

        val requestBody = """{"model":"$model","messages":[{"role":"user","content":"hello"}],"max_tokens":50}"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(CHAT_API)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", "rainy-token/0.1")
            .post(requestBody).build()

        try {
            okHttpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                DebugLog.i(TAG, "triggerUsage: HTTP ${resp.code}, body=${bodyStr.take(500)}")
                if (!resp.isSuccessful) {
                    return@withContext Result.failure<String>(
                        TriggerError("HTTP ${resp.code}", bodyStr.ifBlank { "无响应体" })
                    )
                }
                DebugLog.i(TAG, "triggerUsage: 请求成功，模型=$model")
                Result.success(parseChatResponse(bodyStr, model))
            }
        } catch (e: IOException) {
            DebugLog.e(TAG, "triggerUsage 网络异常: ${e.message}")
            Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            DebugLog.e(TAG, "triggerUsage 异常: ${e::class.simpleName}: ${e.message}")
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    companion object {
        private const val TAG = "OCGO"
        private const val MODELS_API = "https://models.dev/api.json"
        private const val CHAT_API = "https://opencode.ai/zen/v1/chat/completions"
        private val SCRAPED_FIELDS = listOf("rollingUsage", "weeklyUsage", "monthlyUsage")

        /**
         * 解析 SolidJS SSR hydration 输出。匹配模式形如：
         *   `rollingUsage:$R[0]={usagePercent:42,resetInSec:12345}`
         *
         * 用精确前缀 "field:$R[" 定位 hydration 数据中的字段声明，
         * 避免命中 HTML 其他位置（如 JS 代码注释、模板字符串）的同名文本。
         * 用括号计数匹配闭合 "}"，正确处理嵌套对象。
         */
        internal fun parseWindows(html: String): Map<String, ScrapedWindow> {
            val result = mutableMapOf<String, ScrapedWindow>()
            val fields = listOf("rollingUsage", "weeklyUsage", "monthlyUsage")

            for (field in fields) {
                // 精确模式：找 "field:$R[N]={" —— 这是 hydration 数据块的独有格式
                val keyIdx = html.indexOf("$field:\$R[")
                if (keyIdx < 0) continue

                // 找 '=' 然后找 '{'
                val eqIdx = html.indexOf('=', keyIdx)
                if (eqIdx < 0 || eqIdx - keyIdx > 60) continue
                val braceStart = html.indexOf('{', eqIdx)
                if (braceStart < 0 || braceStart - eqIdx > 10) continue

                // 括号计数找正确的闭合 "}"（处理嵌套对象）
                val braceEnd = findMatchingBrace(html, braceStart) ?: continue
                val body = html.substring(braceStart, braceEnd + 1)

                val pct = extractNumberAfterKey(body, "usagePercent")?.toIntOrNull()
                val reset = extractNumberAfterKey(body, "resetInSec")?.toLongOrNull()
                if (pct != null && reset != null) {
                    result[field] = ScrapedWindow(pct, reset)
                }
            }

            return result
        }

        /**
         * 从 openIdx（'{' 的位置）开始，用深度计数找匹配的闭合 '}'。
         * 正确处理嵌套对象：`{status:"ok", sub:{...}, usagePercent:34}`。
         */
        private fun findMatchingBrace(s: String, openIdx: Int): Int? {
            var depth = 0
            for (i in openIdx until s.length) {
                when (s[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return null
        }

        /**
         * 在 body 字符串中找 "key:" 后面紧跟的数字（含可选小数）。返回数字字符串，未找到返回 null。
         * 跳过 status 字符串值（"ok" 之类）。
         */
        private fun extractNumberAfterKey(body: String, key: String): String? {
            val keyIdx = body.indexOf(key)
            if (keyIdx < 0) return null
            var i = keyIdx + key.length
            // 跳过 ":" 后面所有非数字、非负号、非小数点字符
            while (i < body.length) {
                val c = body[i]
                if (c.isDigit() || c == '-' || c == '.') break
                i++
            }
            if (i >= body.length) return null
            // 收集数字
            val start = i
            while (i < body.length) {
                val c = body[i]
                if (c.isDigit() || c == '.' || (c == '-' && i == start)) {
                    i++
                } else {
                    break
                }
            }
            return body.substring(start, i).ifEmpty { null }
        }

        internal data class ScrapedWindow(val usagePercent: Int, val resetInSec: Long)
    }
}

/**
 * 解析 OpenAI 兼容的 chat completions 响应，提取回复文本和用量统计。
 */
internal fun parseChatResponse(responseBody: String, model: String): String {
    val json = Json { ignoreUnknownKeys = true }
    return try {
        val root = json.parseToJsonElement(responseBody) as? JsonObject
        val choices = root?.get("choices") as? kotlinx.serialization.json.JsonArray
        val firstChoice = choices?.firstOrNull() as? JsonObject
        val message = firstChoice?.get("message") as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        val usage = root?.get("usage") as? JsonObject
        val promptTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.contentOrNull
        val completionTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.contentOrNull
        val totalTokens = (usage?.get("total_tokens") as? JsonPrimitive)?.contentOrNull

        val sb = StringBuilder()
        sb.appendLine("✓ 激活成功")
        sb.appendLine("模型: $model")
        sb.appendLine()
        sb.appendLine("回复:")
        sb.appendLine(content?.ifEmpty { "(空)" } ?: "(无回复内容)")
        if (promptTokens != null || completionTokens != null) {
            sb.appendLine()
            sb.appendLine("用量: prompt=$promptTokens completion=$completionTokens total=$totalTokens tokens")
        }
        sb.toString().trim()
    } catch (_: Exception) {
        "✓ 激活成功\n模型: $model\n\n(响应解析失败，但请求已发出)"
    }
}