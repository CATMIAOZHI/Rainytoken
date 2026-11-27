package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.model.TriggerSummary
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Singleton
/**
 * OpenCode Go 配额仓库。
 *
 * 余额（5h/周/月 三档窗口）通过 **API Key** 直查官方接口：
 * `GET https://opencode.ai/inference/go/v1/usage`（Bearer 鉴权），无需浏览器凭据。
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
        val apiKey = credential.apiKey?.trim()
        if (apiKey.isNullOrEmpty()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential("未配置 API Key"))
        }

        val request = usageRequest(apiKey)
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.e(TAG, "fetchBalance: HTTP ${resp.code}")
                return@withContext Result.failure(usageHttpError(resp.code, resp.header("Retry-After")))
            }
            val body = resp.body?.string() ?: return@withContext Result.failure(
                RepositoryError.ParseError(RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空")
            )

            val now = System.currentTimeMillis()
            val windows = parseUsageResponse(body, now)
            if (windows.isEmpty()) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.NO_WINDOWS,
                        "解析失败：未找到任何 OpenCode Go 配额窗口。body=${body.length} 字节。"
                    )
                )
            }

            // 主体数据用 rollingUsage（5h 滚动窗口），这是用户最关心的"实时配额"
            val primary = windows["rollingUsage"] ?: windows.values.first()

            // 详情页、首页和小组件共用这些窗口字段。
            val extras = buildMap {
                windows.forEach { (key, window) ->
                    val prefix = key.removeSuffix("Usage")
                    put("$prefix.pct", window.usagePercent.toString())
                    window.resetInSec?.let { put("$prefix.resetInSec", it.toString()) }
                    window.status?.let { put("$prefix.status", it.wireValue) }
                }
            }
            val balance = ServiceBalance(
                service = ServiceType.OPENCODE_GO,
                amount = primary.usagePercent.toDouble(),
                unit = "%",
                isAvailable = true,
                monthlySpent = windows["monthlyUsage"]?.usagePercent?.toDouble(),
                totalQuota = null,
                nextResetAt = primary.resetAtMillis,
                extras = extras
            )
            balanceCache.put(ServiceType.OPENCODE_GO, balance)
            credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))

            Result.success(balance)
        }
    }

    /**
     * 从 models.dev/api.json 获取 OpenCode Go 可用模型列表（provider key = "opencode-go"）。
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
                val root = json.parseToJsonElement(resp.body?.string() ?: throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空")) as? JsonObject
                    ?: throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.NOT_JSON_OBJECT, "响应根节点不是 JSON 对象")
                val provider = root["opencode-go"] as? JsonObject
                val modelsObj = provider?.get("models") as? JsonObject
                modelsObj?.keys?.toList()?.sorted()
                    ?: throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.NO_MODELS, "未找到 OpenCode Go 模型列表")
            }
            if (models.isEmpty()) {
                return@withContext Result.failure(RepositoryError.ParseError(RepositoryError.ParseErrorReason.MODELS_EMPTY, "模型列表为空"))
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
    suspend fun triggerUsage(model: String): Result<TriggerSummary> = withContext(Dispatchers.IO) {
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
                    return@withContext Result.failure<TriggerSummary>(
                        TriggerError("HTTP ${resp.code}", bodyStr.ifBlank { "" })
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
        private const val CHAT_API = "https://opencode.ai/zen/go/v1/chat/completions"
        // 旧 GET /zen/go/v1/usage 在无 Key / 使用明确无效的测试 Key 时也返回 401 AuthError；
        // 这不能证明两个路由在鉴权成功后等价，配额查询只使用官方文档中的新地址。
        private const val USAGE_API = "https://opencode.ai/inference/go/v1/usage"

        internal fun usageRequest(apiKey: String): Request = Request.Builder()
            .url(USAGE_API)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        internal fun usageHttpError(code: Int, retryAfter: String?, now: Instant = Instant.now()): RepositoryError =
            when (code) {
                401 -> RepositoryError.InvalidCredential("HTTP 401")
                403 -> RepositoryError.OpenCodeGoSubscriptionRequired()
                429 -> RepositoryError.RateLimited(parseRetryAfterSeconds(retryAfter, now))
                else -> RepositoryError.ServerError(code)
            }

        private fun parseRetryAfterSeconds(raw: String?, now: Instant): Long? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
            return try {
                val retryAt = OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                val delay = Duration.between(now, retryAt)
                if (delay.isNegative) 0L else delay.seconds + if (delay.nano > 0) 1L else 0L
            } catch (_: DateTimeParseException) {
                null
            }
        }

        /**
         * 解析官方 Go 用量 API 的 JSON 响应为三档窗口。
         *
         * - `percent` 缺失或非数字 → 跳过该窗口（不猜测数值）；有值则钳制到 0..100
         * - `resetsAt` 支持 ISO-8601 与 epoch（秒/毫秒自动识别）；缺失时窗口仍保留（倒计时为 null）
         * - 非 JSON / 缺 `usage` 节点 → 空 map（调用方映射 NO_WINDOWS 错误）
         *
         * @param nowMillis 计算重置倒计时的基准时间（测试可注入）
         */
        internal fun parseUsageResponse(body: String, nowMillis: Long): Map<String, UsageWindow> {
            val root = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return emptyMap()
            val usage = root["usage"] as? JsonObject ?: return emptyMap()

            val result = mutableMapOf<String, UsageWindow>()
            for ((apiField, usageKey) in listOf(
                "rolling" to "rollingUsage",
                "weekly" to "weeklyUsage",
                "monthly" to "monthlyUsage"
            )) {
                val node = usage[apiField] as? JsonObject ?: continue
                val percent = (node["percent"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                if (percent == null || !percent.isFinite()) continue
                val resetAt = parseResetEpochMillis((node["resetsAt"] as? JsonPrimitive)?.content)
                val rawStatus = (node["status"] as? JsonPrimitive)?.content
                result[usageKey] = UsageWindow(
                    usagePercent = percent.coerceIn(0.0, 100.0).toFloat(),
                    resetInSec = resetAt?.let { ((it - nowMillis) / 1000).coerceAtLeast(0L) },
                    resetAtMillis = resetAt,
                    status = UsageWindowStatus.entries.firstOrNull { it.wireValue == rawStatus }
                )
            }
            return result
        }

        /** `resetsAt` → epoch millis：数字（秒/毫秒）或 ISO-8601；无法解析返回 null。 */
        private fun parseResetEpochMillis(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            val numeric = trimmed.toDoubleOrNull()
            if (numeric != null && numeric.isFinite() && numeric > 0) {
                return if (numeric >= 10_000_000_000.0) numeric.toLong() else (numeric * 1000).toLong()
            }
            return try {
                Instant.parse(trimmed).toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }

    }
}

/** 单个配额窗口。未识别或缺失的 status 不影响可用的百分比。 */
internal data class UsageWindow(
    val usagePercent: Float,
    val resetInSec: Long? = null,
    val resetAtMillis: Long? = null,
    val status: UsageWindowStatus? = null
)

internal enum class UsageWindowStatus(val wireValue: String) {
    OK("ok"),
    RATE_LIMITED("rate-limited")
}

/**
 * 解析 OpenAI 兼容的 chat completions 响应，提取回复文本和用量统计。
 */
internal fun parseChatResponse(responseBody: String, model: String): TriggerSummary {
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

        TriggerSummary(
            model = model,
            reply = content,
            inputTokens = promptTokens,
            outputTokens = completionTokens,
            totalTokens = totalTokens
        )
    } catch (_: Exception) {
        TriggerSummary(model = model, reply = null, parseFailed = true)
    }
}