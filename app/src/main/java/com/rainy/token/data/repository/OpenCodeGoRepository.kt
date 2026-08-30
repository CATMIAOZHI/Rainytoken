package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.model.TriggerSummary
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
import java.util.concurrent.TimeUnit
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
                RepositoryError.ParseError(RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空")
            )

            val windows = parseWindows(html)
            if (windows.isEmpty()) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.NO_WINDOWS,
                        "解析失败：未找到任何 OpenCode Go 配额窗口。HTML=${html.length} 字节。"
                    )
                )
            }

            // 主体数据用 rollingUsage（5h 滚动窗口），这是用户最关心的"实时配额"
            val primary = windows["rollingUsage"] ?: windows.values.first()
            val config = ServiceConfigProvider.get(ServiceType.OPENCODE_GO)

            // 把 3 个窗口的用量百分比 + 重置时间全部塞进 extras（详情页按窗口渲染）
            // usage/limit 为页面新增的用量与限额字段（单位以服务端定义为准，保留供详情页展示，UI 兼容缺失场景）
            val extras = buildMap {
                windows["rollingUsage"]?.let { w ->
                    put("rolling.pct", w.usagePercent.toString())
                    put("rolling.resetInSec", w.resetInSec.toString())
                    w.usage?.let { put("rolling.usage", it.toString()) }
                    w.limit?.let { put("rolling.limit", it.toString()) }
                }
                windows["weeklyUsage"]?.let { w ->
                    put("weekly.pct", w.usagePercent.toString())
                    put("weekly.resetInSec", w.resetInSec.toString())
                    w.usage?.let { put("weekly.usage", it.toString()) }
                    w.limit?.let { put("weekly.limit", it.toString()) }
                }
                windows["monthlyUsage"]?.let { w ->
                    put("monthly.pct", w.usagePercent.toString())
                    put("monthly.resetInSec", w.resetInSec.toString())
                    w.usage?.let { put("monthly.usage", it.toString()) }
                    w.limit?.let { put("monthly.limit", it.toString()) }
                }
            }

            // 主窗口数据先落缓存并返回（关键路径只依赖 HTML 解析一次请求）
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

            // 模型级用量作为增量增强：并行拉取 + 短超时，失败/超时不影响主窗口数据与缓存
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
        /** _server 端点：模型级用量接口（f:31 + 窗口参数，需 x-server-id / x-server-instance 头） */
        private const val SERVER_ENDPOINT = "https://opencode.ai/_server"
        /** 窗口模型用量的 server function id（与请求头 x-server-id 一致，取自网页端实测） */
        private const val MODEL_USAGE_SERVER_ID = "ba154d05c4028a885b8c753f9def7e45d87eb982e65fa8b14254cbe636168914"
        /** 模型级用量单请求超时（毫秒）：只影响增强数据，避免拖慢主窗口刷新 */
        private const val MODEL_REQUEST_TIMEOUT_MS = 5_000L
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

                val pct = extractNumberAfterKey(body, "usagePercent")?.toFloatOrNull()
                val reset = extractNumberAfterKey(body, "resetInSec")?.toLongOrNull()
                // usage/limit 是页面新增的用量与限额字段（单位以服务端定义为准，缺失不影响窗口识别）
                // exactKey=true：避免 "usage" 误命中 "usagePercent" 前缀
                val usage = extractNumberAfterKey(body, "usage", exactKey = true)?.toLongOrNull()
                val limit = extractNumberAfterKey(body, "limit", exactKey = true)?.toLongOrNull()
                if (pct != null && reset != null) {
                    result[field] = ScrapedWindow(pct, reset, usage, limit)
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