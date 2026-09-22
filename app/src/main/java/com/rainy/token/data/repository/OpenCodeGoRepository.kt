package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.model.TriggerSummary
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
/**
 * OpenCode Go 配额仓库。
 *
 * 余额（5h/周/月 三档窗口）通过 **API Key** 直查官方接口：
 * `GET https://opencode.ai/zen/go/v1/usage`（Bearer 鉴权），不再抓取 dashboard HTML。
 *
 * - API Key：设置页填写（opencode.ai/settings → API Keys），查询余额必填
 * - auth cookie + workspaceId：**选填**，仅用于模型级明细增强（`_server` 接口）
 *   与 [OpenCodeUsageRepository] 用量记录页；缺失时余额仍正常返回
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
    /**
     * 拉取余额：`GET /zen/go/v1/usage`（Bearer API Key）。
     *
     * 响应形如 `{"usage":{"rolling":{"percent":42,"resetsAt":"…"},"weekly":{…},"monthly":{…}}}`。
     * auth cookie + workspaceId 仅作模型级明细增强，缺失不影响余额主数据。
     */
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

        val request = Request.Builder()
            .url(USAGE_API)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
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
                DebugLog.e(TAG, "fetchBalance: HTTP ${resp.code}")
                return@withContext when (resp.code) {
                    401, 403 -> Result.failure(RepositoryError.InvalidCredential("HTTP ${resp.code}"))
                    429 -> Result.failure(RepositoryError.RateLimited(resp.header("Retry-After")?.toLongOrNull()))
                    else -> Result.failure(RepositoryError.ServerError(resp.code))
                }
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

            // 把 3 个窗口的用量百分比 + 重置倒计时塞进 extras（详情页/小组件按窗口渲染）
            val extras = buildMap {
                windows["rollingUsage"]?.let { w ->
                    put("rolling.pct", w.usagePercent.toString())
                    w.resetInSec?.let { put("rolling.resetInSec", it.toString()) }
                }
                windows["weeklyUsage"]?.let { w ->
                    put("weekly.pct", w.usagePercent.toString())
                    w.resetInSec?.let { put("weekly.resetInSec", it.toString()) }
                }
                windows["monthlyUsage"]?.let { w ->
                    put("monthly.pct", w.usagePercent.toString())
                    w.resetInSec?.let { put("monthly.resetInSec", it.toString()) }
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

            // 模型级用量作为增量增强：需要 auth cookie + workspaceId；缺失或失败不影响主数据
            val workspaceId = credential.workspaceId
            val authCookie = credential.authCookie
            if (workspaceId.isNullOrBlank() || authCookie.isNullOrBlank()) {
                return@withContext Result.success(balance)
            }
            val modelUsage = fetchModelWindows(workspaceId, authCookie)
            if (modelUsage.isEmpty()) {
                return@withContext Result.success(balance)
            }
            val enriched = balance.copy(
                extras = buildMap {
                    putAll(extras)
                    modelUsage.forEach { (window, usage) -> put("$window.models", json.encodeToString(usage)) }
                }
            )
            balanceCache.put(ServiceType.OPENCODE_GO, enriched)
            Result.success(enriched)
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
        /** 余额/三档窗口用量查询（Bearer API Key） */
        private const val USAGE_API = "https://opencode.ai/zen/go/v1/usage"
        /** _server 端点：模型级用量接口（f:31 + 窗口参数，需 x-server-id / x-server-instance 头） */
        private const val SERVER_ENDPOINT = "https://opencode.ai/_server"
        /** 窗口模型用量的 server function id（与请求头 x-server-id 一致，取自网页端实测） */
        private const val MODEL_USAGE_SERVER_ID = "ba154d05c4028a885b8c753f9def7e45d87eb982e65fa8b14254cbe636168914"
        /** 模型级用量单请求超时（毫秒）：只影响增强数据，避免拖慢主窗口刷新 */
        private const val MODEL_REQUEST_TIMEOUT_MS = 5_000L

        /**
         * 解析 `GET /zen/go/v1/usage` JSON 响应为三档窗口。
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
                result[usageKey] = UsageWindow(
                    usagePercent = percent.coerceIn(0.0, 100.0).toFloat(),
                    resetInSec = resetAt?.let { ((it - nowMillis) / 1000).coerceAtLeast(0L) },
                    resetAtMillis = resetAt
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
         *
         * @param exactKey 为 true 时要求 key 后紧跟 ':'（精确字段匹配），
         *                 避免 "usage" 误命中 "usagePercent" 这类前缀字段。
         */
        private fun extractNumberAfterKey(body: String, key: String, exactKey: Boolean = false): String? {
            val keyIdx = if (exactKey) {
                // 精确匹配：key 后必须紧跟 ':'（如 "usage:"），不能是 "usagePercent"
                var idx = body.indexOf(key)
                while (idx >= 0) {
                    val after = idx + key.length
                    if (after < body.length && body[after] == ':') break
                    idx = body.indexOf(key, idx + 1)
                }
                idx
            } else {
                body.indexOf(key)
            }
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

        internal data class ScrapedWindow(
            val usagePercent: Float,
            val resetInSec: Long,
            val usage: Long? = null,
            val limit: Long? = null
        )

        /**
         * 解析 _server 返回的模型级用量 flight 响应。
         *
         * 实际响应形如：
         * ```
         * ;0x000003bc;((self.$R=self.$R||{})["server-fn:2"]=[],($R=>$R[0]={
         *   usage:1889462439,limit:6000000000,usagePercent:31.5,
         *   rows:$R[1]=[$R[2]={model:"deepseek-v4-flash",name:"DeepSeek V4 Flash",
         *                     cost:1002607535,quotaCost:2005215070,multiplier:2,
         *                     estimated:!0,contributionPercent:30.5},...]
         * })($R["server-fn:2"]))
         * ```
         *
         * 返回窗口模型用量（usage/limit/usagePercent + 每模型行），解析失败返回 null。
         * cost/quotaCost 单位为 1e-8 美元（cost × 10⁻⁸ = 美元）。
         */
        internal fun parseModelRows(flight: String): WindowModelUsage? {
            // 定位 "$R[0]={" 数据块（窗口模型用量的固定 hydration 格式）
            val rootIdx = flight.indexOf("\$R[0]={")
            if (rootIdx < 0) return null
            val braceStart = rootIdx + "\$R[0]=".length
            val braceEnd = findMatchingBrace(flight, braceStart) ?: return null
            val body = flight.substring(braceStart, braceEnd + 1)

            val usage = extractNumberAfterKey(body, "usage", exactKey = true)?.toLongOrNull()
            val limit = extractNumberAfterKey(body, "limit", exactKey = true)?.toLongOrNull()
            val usagePercent = extractNumberAfterKey(body, "usagePercent")?.toFloatOrNull()

            return WindowModelUsage(
                usage = usage,
                limit = limit,
                usagePercent = usagePercent,
                rows = extractModelRows(body)
            )
        }

        /** 从外层对象 body 中提取所有 {model:"..."} 模型行对象。 */
        private fun extractModelRows(body: String): List<ModelUsageRow> {
            val rows = mutableListOf<ModelUsageRow>()
            var idx = body.indexOf("{model:")
            while (idx >= 0) {
                val braceEnd = findMatchingBrace(body, idx) ?: break
                val obj = body.substring(idx, braceEnd + 1)
                val model = extractQuoted(obj, "model") ?: ""
                val name = extractQuoted(obj, "name") ?: ""
                val cost = extractNumberAfterKey(obj, "cost")?.toLongOrNull() ?: 0L
                val quotaCost = extractNumberAfterKey(obj, "quotaCost")?.toLongOrNull() ?: 0L
                val multiplier = extractNumberAfterKey(obj, "multiplier")?.toDoubleOrNull() ?: 1.0
                val estimated = obj.contains("estimated:!0")
                val contributionPercent = extractNumberAfterKey(obj, "contributionPercent")?.toDoubleOrNull() ?: 0.0
                rows += ModelUsageRow(
                    model = model,
                    name = name,
                    cost = cost,
                    quotaCost = quotaCost,
                    multiplier = multiplier,
                    estimated = estimated,
                    contributionPercent = contributionPercent
                )
                idx = body.indexOf("{model:", braceEnd + 1)
            }
            return rows
        }

        /** 提取 "key:" 后面紧跟的 "..." 引号字符串值。 */
        private fun extractQuoted(body: String, key: String): String? {
            val keyIdx = body.indexOf("$key:\"")
            if (keyIdx < 0) return null
            val start = keyIdx + key.length + 2
            val end = body.indexOf('"', start)
            if (end < 0) return null
            return body.substring(start, end)
        }

        /**
         * 窗口配额（1e-8 美元整数）：窗口 limit（token 数）÷ multiplier。
         * 与网页端各窗口"配额"列口径一致（实测：5h=12 亿÷2=$6、周=30 亿÷2=$15、月=60 亿÷2=$30；hy3 月=60 亿÷0.125=$480）。
         * multiplier 缺失或非正时安全降级为月度基础 $60（与网页默认一致）。
         */
        internal fun windowQuotaRaw(windowLimit: Long, multiplier: Double): Long {
            if (multiplier <= 0.0) return 60L * 100_000_000L
            return (windowLimit.toDouble() / multiplier).toLong()
        }
    }

    /**
     * 拉取三个窗口（rolling/weekly/monthly）的模型级用量。
     * 并行发起 + 每个请求独立短超时（MODEL_REQUEST_TIMEOUT_MS），
     * 任一窗口失败不影响其他窗口；整体失败返回空 Map（主窗口数据不受影响）。
     *
     * 依赖 auth cookie + workspaceId（选填凭据）；仅在两者齐备时由 [fetchBalance] 调用。
     */
    private suspend fun fetchModelWindows(workspaceId: String, authCookie: String): Map<String, WindowModelUsage> =
        coroutineScope {
            listOf("rolling", "weekly", "monthly").map { window ->
                async {
                    try {
                        requestModelWindow(workspaceId, authCookie, window)?.let { parseModelRows(it) }
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "fetchModelWindows($window) 异常: ${e.message}")
                        null
                    }
                }
            }.awaitAll().let { results ->
                listOf("rolling", "weekly", "monthly").zip(results).mapNotNull { (window, usage) ->
                    usage?.let { window to it }
                }.toMap()
            }
        }

    /** 请求 _server 的窗口模型用量接口，返回 flight 文本；HTTP 失败/超时返回 null。 */
    private fun requestModelWindow(workspaceId: String, authCookie: String, window: String): String? {
        val args = """{"t":{"t":9,"i":0,"l":2,"a":[{"t":1,"s":"$workspaceId"},{"t":1,"s":"$window"}],"o":0},"f":31,"m":[]}"""
        val url = "$SERVER_ENDPOINT?id=$MODEL_USAGE_SERVER_ID&args=${URLEncoder.encode(args, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Cookie", "auth=$authCookie")
            .header("x-server-id", MODEL_USAGE_SERVER_ID)
            .header("x-server-instance", "server-fn:2")
            .header("Referer", "https://opencode.ai/workspace/$workspaceId/go")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/152.0.0.0 Safari/537.36")
            .get().build()
        val call = okHttpClient.newCall(request)
        call.timeout().timeout(MODEL_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.e(TAG, "requestModelWindow($window): HTTP ${resp.code}")
                    null
                } else {
                    resp.body?.string()
                }
            }
        } catch (e: IOException) {
            DebugLog.e(TAG, "requestModelWindow($window) 网络异常: ${e.message}")
            null
        } catch (e: Throwable) {
            DebugLog.e(TAG, "requestModelWindow($window) 异常: ${e.message}")
            null
        }
    }
}

/**
 * 单个用量窗口（`GET /zen/go/v1/usage` 解析结果）。
 * @param usagePercent 已用百分比（0..100）
 * @param resetInSec 距离重置的倒计时秒数（相对 nowMillis，已钳制 ≥0；null = 接口未返回重置时间）
 * @param resetAtMillis 重置时刻（epoch millis；null = 接口未返回）
 */
internal data class UsageWindow(
    val usagePercent: Float,
    val resetInSec: Long? = null,
    val resetAtMillis: Long? = null
)

/**
 * 窗口模型级用量（_server 接口 payload）。
 * cost/quotaCost 单位为 1e-8 美元；multiplier 为计费倍率（quotaCost = cost × multiplier）。
 */
@Serializable
internal data class WindowModelUsage(
    val usage: Long? = null,
    val limit: Long? = null,
    val usagePercent: Float? = null,
    val rows: List<ModelUsageRow> = emptyList()
)

/** 单模型用量行。contributionPercent 为该模型占窗口用量的百分比（如 30.5 = 30.5%）。 */
@Serializable
internal data class ModelUsageRow(
    val model: String = "",
    val name: String = "",
    val cost: Long = 0L,
    val quotaCost: Long = 0L,
    val multiplier: Double = 1.0,
    val estimated: Boolean = false,
    val contributionPercent: Double = 0.0
)

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