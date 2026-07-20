package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Singleton

/**
 * Ollama Pro Cloud 用量仓库。
 *
 * Ollama 没有公开的 Usage API（见 ollama/ollama#12532），
 * 本 Repository 通过 OkHttp 搓 Cookie 请求 https://ollama.com/settings，
 * 解析返回 HTML 中的用量数据：
 *
 * - Plan 层级（Pro / Max / Free）：Cloud usage 标题旁的 rounded-full badge
 * - Session usage 百分比：Session usage 区域的文本
 * - Weekly usage 百分比：Weekly usage 区域的文本
 * - 重置时间：data-time 属性（第 1 个=Session，第 2 个=Weekly）
 * - 模型级请求次数：data-model + data-requests 属性
 *
 * 认证方式：用户从浏览器 DevTools 复制完整 Cookie 字符串（至少含 `__Secure-session`）。
 */
@Singleton
class OllamaRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "Ollama"
        private const val SETTINGS_URL = "https://ollama.com/settings"
        private const val MODELS_API = "https://models.dev/api.json"
        private const val CHAT_API = "https://ollama.com/v1/chat/completions"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        /**
         * 解析 Ollama settings 页面 HTML，提取用量数据。
         * 正则方案与 hermes-ollama-cloud-usage 和 CodexBar 一致。
         */
        internal fun parseUsage(html: String): ParsedUsage? {
            // Plan: "Cloud usage" 后的 rounded-full badge 文本
            val planMatch = Regex(
                """Cloud usage[^<]*</span>\s*<span[^>]*class="[^"]*rounded-full[^"]*"[^>]*>\s*(pro|max|free)\s*</span""",
                RegexOption.IGNORE_CASE
            ).find(html)
            val plan = planMatch?.groupValues?.get(1)?.trim()?.replaceFirstChar { it.uppercaseChar() }
                ?: run {
                    // Fallback: 任意 rounded-full + text-neutral badge
                    val fallback = Regex(
                        """<span[^>]*class="[^"]*rounded-full[^"]*text-neutral[^"]*"[^>]*>\s*\n?\s*(pro|max|free)\s*</span""",
                        RegexOption.IGNORE_CASE
                    ).find(html)
                    fallback?.groupValues?.get(1)?.trim()?.replaceFirstChar { it.uppercaseChar() }
                }

            // Session usage: 先找 aria-label，再找 visible text
            val sessionPct = Regex("""aria-label="Session usage\s+([\d.]+)%\s+used""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("""Session usage[^<]*>[\s\S]*?([\d.]+)%\s*used""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.toFloatOrNull()

            // Weekly usage: 同上
            val weeklyPct = Regex("""aria-label="Weekly usage\s+([\d.]+)%\s+used""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("""Weekly usage[^<]*>[\s\S]*?([\d.]+)%\s*used""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.toFloatOrNull()

            // data-time 属性 → 重置时间戳
            val dataTimes = Regex("""data-time="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
            val sessionResetAt = dataTimes.getOrNull(0)?.let { parseIsoTime(it) }
            val weeklyResetAt = dataTimes.getOrNull(1)?.let { parseIsoTime(it) }

            // 模型级请求次数: data-model + data-requests
            val weeklyStart = html.indexOf("Weekly usage")
            val sessionModels = mutableListOf<Pair<String, Int>>()
            val weeklyModels = mutableListOf<Pair<String, Int>>()
            Regex("""data-model="([^"]+)"\s+data-requests="(\d+)"""").findAll(html).forEach { match ->
                val model = match.groupValues[1]
                val requests = match.groupValues[2].toIntOrNull() ?: 0
                if (match.range.first < weeklyStart) {
                    sessionModels.add(model to requests)
                } else {
                    weeklyModels.add(model to requests)
                }
            }

            if (sessionPct == null && weeklyPct == null) return null

            return ParsedUsage(
                plan = plan ?: "Unknown",
                sessionPercent = sessionPct ?: 0f,
                weeklyPercent = weeklyPct ?: 0f,
                sessionResetAt = sessionResetAt,
                weeklyResetAt = weeklyResetAt,
                sessionModels = sessionModels,
                weeklyModels = weeklyModels
            )
        }

        internal fun parseIsoTime(iso: String): Long? {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(iso)?.time
            } catch (e: Exception) {
                null
            }
        }

        internal data class ParsedUsage(
            val plan: String,
            val sessionPercent: Float,
            val weeklyPercent: Float,
            val sessionResetAt: Long?,
            val weeklyResetAt: Long?,
            val sessionModels: List<Pair<String, Int>>,
            val weeklyModels: List<Pair<String, Int>>
        )
    }

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.OLLAMA)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.SessionCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val cookie = credential.ollamaCookie
        if (cookie.isNullOrBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val request = Request.Builder()
            .url(SETTINGS_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", cookie)
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

            val parsed = parseUsage(html)
            if (parsed == null) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(
                        "解析失败：未找到 Ollama Cloud 用量数据。HTML=${html.length} 字节。Cookie 可能已过期。"
                    )
                )
            }

            // Session 是主要用量窗口（类似 OCGO 的 rollingUsage）
            val sessionPct = parsed.sessionPercent
            val weeklyPct = parsed.weeklyPercent

            val extras = buildMap {
                put("plan", parsed.plan)
                put("session.pct", sessionPct.toString())
                put("weekly.pct", weeklyPct.toString())
                parsed.sessionResetAt?.let { put("session.resetAt", it.toString()) }
                parsed.weeklyResetAt?.let { put("weekly.resetAt", it.toString()) }
                // 模型级数据序列化为分号分隔
                if (parsed.sessionModels.isNotEmpty()) {
                    put("session.models", parsed.sessionModels.joinToString(";") { "${it.first},${it.second}" })
                }
                if (parsed.weeklyModels.isNotEmpty()) {
                    put("weekly.models", parsed.weeklyModels.joinToString(";") { "${it.first},${it.second}" })
                }
            }

            val balance = ServiceBalance(
                service = ServiceType.OLLAMA,
                amount = sessionPct.toDouble(),
                unit = "%",
                isAvailable = true,
                totalQuota = null,
                nextResetAt = parsed.sessionResetAt,
                extras = extras
            )

            balanceCache.put(ServiceType.OLLAMA, balance)
            credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))

            Result.success(balance)
        }
    }

    /**
     * 从 models.dev/api.json 获取 Ollama Cloud 可用模型列表（provider key = "ollama-cloud"）。
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
                val provider = root["ollama-cloud"] as? JsonObject
                val modelsObj = provider?.get("models") as? JsonObject
                modelsObj?.keys?.toList()?.sorted()
                    ?: throw RepositoryError.ParseError("未找到 Ollama Cloud 模型列表")
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
     * 一键激活用量：用 API Key 向 Ollama Cloud chat completions API 发送简短请求。
     * @param model 用户选择的模型 slug
     * @return Result.success(响应摘要文本) — 供 UI 展示
     */
    suspend fun triggerUsage(model: String): Result<String> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.OLLAMA)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 Ollama 凭据"))
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
                Result.success(parseOllamaChatResponse(bodyStr, model))
            }
        } catch (e: IOException) {
            DebugLog.e(TAG, "triggerUsage 网络异常: ${e.message}")
            Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            DebugLog.e(TAG, "triggerUsage 异常: ${e::class.simpleName}: ${e.message}")
            Result.failure(RepositoryError.Unknown(e))
        }
    }

}

/**
 * 解析 Ollama Cloud chat completions 响应，提取回复文本和用量统计。
 */
internal fun parseOllamaChatResponse(responseBody: String, model: String): String {
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