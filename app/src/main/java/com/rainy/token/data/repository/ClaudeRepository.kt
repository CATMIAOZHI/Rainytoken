package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.model.TriggerSummary
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Singleton

/**
 * Claude（Claude Code / Pro·Max 订阅）配额仓库。
 *
 * 余额（5h 会话窗口 + 每周窗口 + 模型级周窗口）通过 **OAuth token** 直查官方接口：
 * `GET https://api.anthropic.com/api/oauth/usage`（Bearer 鉴权）。
 *
 * 凭据形态与 Codex 同构：用户粘贴 Claude Code 的 `~/.claude/.credentials.json`，
 * 内含 `accessToken` + `refreshToken`，token 到期自动用 refresh_token 续期。
 *
 * 请求头指纹对齐 opencodex（`src/providers/quota/vendor-probes-oauth.ts`）：
 * usage 端点用 claude-cli User-Agent + anthropic-beta 列表；
 * 一键激活（messages 端点）额外补齐 Claude Code CLI 的完整头集合，
 * 并把 Claude Code 身份声明作为 system 首个 block。
 *
 * 不在类上加 @Inject constructor —— 在 [com.rainy.token.di.NetworkModule] 里 @Provides 显式提供。
 */
@Singleton
class ClaudeRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 刷新结果 */
    private sealed class RefreshResult {
        data class Success(val cred: Credential.ClaudeCredential) : RefreshResult()
        data class Failure(val reason: String) : RefreshResult()
    }

    /**
     * 拉取配额：`GET /api/oauth/usage`。
     *
     * 响应形如
     * `{"five_hour":{"utilization":12.5,"resets_at":"2026-…"},"seven_day":{…},
     *   "seven_day_opus":{…},"limits":[{"kind":"weekly_scoped","percent":…}]}`。
     */
    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.CLAUDE)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 Claude Code 凭据"))
        if (credential !is Credential.ClaudeCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential("凭据类型不匹配"))
        }
        if (credential.accessToken.isBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential("未配置 access token"))
        }

        val effectiveCred = if (tokenNeedsRefresh(credential)) {
            DebugLog.i(TAG, "access_token 即将过期，尝试刷新（expiresAt=${credential.expiresAt}）")
            when (val r = refreshToken(credential)) {
                is RefreshResult.Success -> {
                    DebugLog.i(TAG, "token 刷新成功，新 expiresAt=${r.cred.expiresAt}")
                    credentialRepository.save(r.cred)
                    r.cred
                }
                is RefreshResult.Failure -> {
                    DebugLog.e(TAG, "token 主动刷新失败: ${r.reason}")
                    credential
                }
            }
        } else credential

        val usageResult = try {
            fetchUsageJson(effectiveCred.accessToken)
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
                        try {
                            fetchUsageJson(r.cred.accessToken)
                        } catch (e2: RepositoryError) {
                            return@withContext Result.failure(e2)
                        } catch (e2: IOException) {
                            return@withContext Result.failure(RepositoryError.Network(e2))
                        } catch (e2: Throwable) {
                            return@withContext Result.failure(RepositoryError.Unknown(e2))
                        }
                    }
                    is RefreshResult.Failure -> {
                        DebugLog.e(TAG, "二次刷新也失败: ${r.reason}")
                        return@withContext Result.failure(RepositoryError.InvalidCredential(r.reason))
                    }
                }
            } else return@withContext Result.failure(e)
        } catch (e: Throwable) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        val quota = parseUsageResponse(usageResult)
        if (quota.isEmpty) {
            return@withContext Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "解析失败：未找到任何 Claude 配额窗口"
                )
            )
        }

        val config = ServiceConfigProvider.get(ServiceType.CLAUDE)
        val primary = quota.fiveHour ?: quota.weekly ?: quota.modelWindows.first()
        val extras = buildMap {
            quota.fiveHour?.let { w ->
                put("fiveHour.pct", w.percent.toString())
                w.resetAtMillis?.let { put("fiveHour.resetAt", it.toString()) }
            }
            quota.weekly?.let { w ->
                put("weekly.pct", w.percent.toString())
                w.resetAtMillis?.let { put("weekly.resetAt", it.toString()) }
            }
            quota.modelWindows.forEachIndexed { index, w ->
                put("model_$index.label", w.label)
                put("model_$index.pct", w.percent.toString())
                w.resetAtMillis?.let { put("model_$index.resetAt", it.toString()) }
            }
            credential.subscriptionType?.takeIf { it.isNotBlank() }?.let { put("plan", it) }
        }
        val balance = ServiceBalance(
            service = ServiceType.CLAUDE,
            amount = primary.percent.toDouble(),
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = null,
            totalQuota = null,
            nextResetAt = primary.resetAtMillis,
            extras = extras
        )
        balanceCache.put(ServiceType.CLAUDE, balance)
        credentialRepository.save(effectiveCred.copy(lastVerifiedAt = System.currentTimeMillis()))
        Result.success(balance)
    }

    /**
     * 从 models.dev/api.json 获取 Claude 可用模型列表（provider key = "anthropic"）。
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
                val root = json.parseToJsonElement(
                    resp.body?.string() ?: throw RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空"
                    )
                ) as? JsonObject
                    ?: throw RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.NOT_JSON_OBJECT, "响应根节点不是 JSON 对象"
                    )
                val provider = root["anthropic"] as? JsonObject
                val modelsObj = provider?.get("models") as? JsonObject
                modelsObj?.keys?.toList()?.sorted()
                    ?: throw RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.NO_MODELS, "未找到 Claude 模型列表"
                    )
            }
            if (models.isEmpty()) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(RepositoryError.ParseErrorReason.MODELS_EMPTY, "模型列表为空")
                )
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
     * 一键激活用量：用 OAuth token 向 messages 端点发一条最小请求。
     *
     * 请求指纹与 Claude Code CLI 对齐（否则 OAuth token 会被判定为非官方客户端）：
     * `anthropic-beta` + Claude Code 头集合 + 稳定 session id + system 首块身份声明。
     */
    suspend fun triggerUsage(model: String): Result<TriggerSummary> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.CLAUDE)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 Claude Code 凭据"))
        if (credential !is Credential.ClaudeCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential("凭据类型不匹配"))
        }

        val effectiveCred = ensureToken(credential)
            ?: return@withContext Result.failure(
                TriggerError("token 刷新失败", "", TriggerErrorReason.TOKEN_REFRESH)
            )

        DebugLog.i(TAG, "triggerUsage: model=$model")
        val body = """
            {"model":"$model","max_tokens":32,
             "system":[{"type":"text","text":"$CLAUDE_CODE_SYSTEM_INSTRUCTION"}],
             "messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}
        """.trimIndent().toRequestBody(JSON_MEDIA_TYPE)

        try {
            val first = postMessages(effectiveCred.accessToken, body)
            if (first.success) {
                Result.success(parseMessagesResponse(first.body, model))
            } else if (first.code == 401 || first.code == 403) {
                // access token 已失效（可能只是本地 expiresAt 不准），二次刷新后重试一次
                DebugLog.w(TAG, "triggerUsage: HTTP ${first.code}，尝试二次刷新")
                val refreshed = ensureToken(effectiveCred, force = true)
                    ?: return@withContext Result.failure(
                        TriggerError("token 刷新失败", first.body, TriggerErrorReason.TOKEN_REFRESH)
                    )
                val retry = postMessages(refreshed.accessToken, body)
                if (retry.success) {
                    Result.success(parseMessagesResponse(retry.body, model))
                } else {
                    DebugLog.e(TAG, "triggerUsage retry failed: HTTP ${retry.code}")
                    Result.failure(TriggerError("HTTP ${retry.code}", retry.body.ifBlank { "" }))
                }
            } else {
                DebugLog.e(TAG, "triggerUsage failed: HTTP ${first.code}")
                Result.failure(TriggerError("HTTP ${first.code}", first.body.ifBlank { "" }))
            }
        } catch (e: IOException) {
            DebugLog.e(TAG, "triggerUsage 网络异常: ${e.message}")
            Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            DebugLog.e(TAG, "triggerUsage 异常: ${e::class.simpleName}: ${e.message}")
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    /** messages 请求结果（不抛错，由调用方按 code 决定后续）。 */
    private data class MessagesResponse(val code: Int, val body: String) {
        val success: Boolean get() = code in 200..299
    }

    /** 执行一次 messages 请求。 */
    private fun postMessages(accessToken: String, body: okhttp3.RequestBody): MessagesResponse {
        val request = claudeCodeRequest(MESSAGES_API, accessToken).post(body).build()
        okHttpClient.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            DebugLog.i(TAG, "messages: HTTP ${resp.code}, body=${bodyStr.take(500)}")
            return MessagesResponse(resp.code, bodyStr)
        }
    }

    // ── 网络 ──

    /** `GET /api/oauth/usage`，头指纹对齐 opencodex 的 Claude usage 探针。 */
    private fun fetchUsageJson(accessToken: String): JsonObject {
        val request = Request.Builder().url(USAGE_API)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("User-Agent", USAGE_USER_AGENT)
            .header("anthropic-beta", USAGE_BETA)
            .header("Authorization", "Bearer $accessToken")
            .get().build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.e(TAG, "fetchUsageJson failed: HTTP ${resp.code}")
                throw when (resp.code) {
                    401, 403 -> RepositoryError.InvalidCredential("HTTP ${resp.code}")
                    429 -> RepositoryError.RateLimited(resp.header("Retry-After")?.toLongOrNull())
                    else -> RepositoryError.ServerError(resp.code)
                }
            }
            val text = resp.body?.string()
                ?: throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空")
            return json.parseToJsonElement(text) as? JsonObject
                ?: throw RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NOT_JSON_OBJECT, "响应根节点不是 JSON 对象"
                )
        }
    }

    /** Claude Code CLI 指纹请求（messages 端点）。 */
    private fun claudeCodeRequest(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "application/json")
            .header("User-Agent", CLAUDE_CODE_USER_AGENT)
            .header("Authorization", "Bearer $accessToken")
            .header("anthropic-beta", ANTHROPIC_OAUTH_BETA)
            .header("X-App", "cli")
            .header("X-Stainless-Retry-Count", "0")
            .header("X-Stainless-Runtime", "node")
            .header("X-Stainless-Lang", "js")
            .header("X-Stainless-Timeout", "600")
            .header("X-Stainless-Arch", STAINLESS_ARCH)
            .header("X-Stainless-OS", STAINLESS_OS)
            .header("X-Stainless-Package-Version", STAINLESS_PACKAGE_VERSION)
            .header("X-Stainless-Runtime-Version", STAINLESS_RUNTIME_VERSION)
            .header("X-Claude-Code-Session-Id", claudeCodeSessionId(accessToken))
            .header("x-client-request-id", randomClientRequestId())

    // ── Token 刷新 ──

    private fun tokenNeedsRefresh(cred: Credential.ClaudeCredential): Boolean =
        cred.expiresAt > 0L && System.currentTimeMillis() >= cred.expiresAt - REFRESH_BUFFER_MS

    /**
     * 确保 access_token 有效，返回刷新后的凭据或 null（刷新失败时）。
     *
     * 刷新成功后**必须落盘**：Anthropic 的 refresh token 会被轮换，且有复用检测
     * （旧 refresh token 再用会直接失效），丢弃新 token 会让用户凭据报废。
     *
     * @param force 忽略 expiresAt 判断，强制刷新（用于收到 401/403 后的重试）
     */
    private suspend fun ensureToken(
        credential: Credential.ClaudeCredential,
        force: Boolean = false
    ): Credential.ClaudeCredential? {
        if (!force && !tokenNeedsRefresh(credential)) return credential
        DebugLog.i(TAG, "ensureToken: 刷新 token（force=$force）")
        return when (val r = refreshToken(credential)) {
            is RefreshResult.Success -> {
                credentialRepository.save(r.cred)
                r.cred
            }
            is RefreshResult.Failure -> {
                DebugLog.e(TAG, "ensureToken: token 刷新失败: ${r.reason}")
                null
            }
        }
    }

    private suspend fun refreshToken(cred: Credential.ClaudeCredential): RefreshResult =
        refreshTokenBlocking(cred)

    private fun refreshTokenBlocking(cred: Credential.ClaudeCredential): RefreshResult {
        if (cred.refreshToken.isBlank()) return RefreshResult.Failure("未配置 refresh_token")
        val body = """
            {"grant_type":"refresh_token","client_id":"$CLAUDE_CODE_CLIENT_ID",
             "refresh_token":"${cred.refreshToken}"}
        """.trimIndent().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(TOKEN_URL)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body).build()
        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string()
                if (!resp.isSuccessful) {
                    DebugLog.e(TAG, "token refresh failed: HTTP ${resp.code} | ${bodyStr?.take(200)}")
                    return@use RefreshResult.Failure("HTTP ${resp.code}")
                }
                if (bodyStr.isNullOrBlank()) return@use RefreshResult.Failure("响应体为空")
                val root = json.parseToJsonElement(bodyStr) as? JsonObject
                    ?: return@use RefreshResult.Failure("刷新响应不是 JSON 对象")
                val access = (root["access_token"] as? JsonPrimitive)?.contentOrNull
                if (access.isNullOrBlank()) return@use RefreshResult.Failure("刷新响应缺少 access_token")
                val refresh = (root["refresh_token"] as? JsonPrimitive)?.contentOrNull
                val expiresIn = (root["expires_in"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                val account = root["account"] as? JsonObject
                val uuid = (account?.get("uuid") as? JsonPrimitive)?.contentOrNull
                RefreshResult.Success(
                    cred.copy(
                        accessToken = access,
                        refreshToken = refresh.orEmpty().ifBlank { cred.refreshToken },
                        expiresAt = expiresIn?.takeIf { it.isFinite() && it >= 0 }
                            ?.let { System.currentTimeMillis() + (it * 1000).toLong() - REFRESH_BUFFER_MS }
                            ?: cred.expiresAt,
                        accountId = uuid?.takeIf { it.isNotBlank() } ?: cred.accountId,
                        lastVerifiedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "token refresh exception: ${e::class.simpleName}: ${e.message}")
            RefreshResult.Failure("网络异常: ${e::class.simpleName}")
        }
    }

    companion object {
        private const val TAG = "Claude"
        private const val USAGE_API = "https://api.anthropic.com/api/oauth/usage"
        private const val MESSAGES_API = "https://api.anthropic.com/v1/messages"
        private const val TOKEN_URL = "https://api.anthropic.com/v1/oauth/token"
        private const val MODELS_API = "https://models.dev/api.json"

        /** Claude Code CLI 的 OAuth client id（公开常量，与官方 CLI 同源）。 */
        private const val CLAUDE_CODE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CLAUDE_CODE_USER_AGENT = "@anthropic-ai/sdk/0.74.0"
        /** usage 探针的 User-Agent（opencodex vendor-probes-oauth.ts 同款）。 */
        private const val USAGE_USER_AGENT = "claude-cli/2.1.63 (external, cli)"
        /** usage 探针的 anthropic-beta 列表（与 opencodex 逐项一致）。 */
        private const val USAGE_BETA =
            "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14," +
                "context-management-2025-06-27,prompt-caching-scope-2026-01-05"
        /** messages 端点的 anthropic-beta（opencodex ANTHROPIC_OAUTH_BETA）。 */
        private const val ANTHROPIC_OAUTH_BETA = "claude-code-20250219,oauth-2025-04-20"
        /** OAuth 请求的 system 首个 block：Claude Code 身份声明。 */
        private const val CLAUDE_CODE_SYSTEM_INSTRUCTION =
            "You are a Claude agent, built on Anthropic's Claude Agent SDK."

        // X-Stainless-* 指纹：对齐 Claude Code CLI（@anthropic-ai/sdk 0.74.0 / node）
        private const val STAINLESS_ARCH = "arm64"
        private const val STAINLESS_OS = "linux"
        private const val STAINLESS_PACKAGE_VERSION = "0.74.0"
        private const val STAINLESS_RUNTIME_VERSION = "22.14.0"

        private const val REFRESH_BUFFER_MS = 5L * 60 * 1000
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * 由 token 派生稳定的 Claude Code session id（UUIDv4 形状）。
         * 与 opencodex `claudeCodeSessionId` 同算法：sha256("claude-code-session:" + token)。
         */
        internal fun claudeCodeSessionId(token: String): String {
            val seed = token.ifBlank { "rainytoken-anon" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("claude-code-session:$seed".toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            val variant = ((hex[16].digitToInt(16) and 0x3) or 0x8).toString(16)
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-" +
                "$variant${hex.substring(17, 20)}-${hex.substring(20, 32)}"
        }

        private fun randomClientRequestId(): String = java.util.UUID.randomUUID().toString()

        /**
         * 解析 `GET /api/oauth/usage` 响应。
         *
         * - `five_hour` → 5h 会话窗口；`seven_day` → 每周窗口
         * - `seven_day_fable` / `seven_day_opus` / `seven_day_sonnet` → 模型级周窗口
         * - `limits[]` 中 `kind == "weekly_scoped"` 的其他模型窗口作为补充（去重）
         * - `utilization` 缺失或非数字 → 跳过；有值则钳制到 0..100
         * - `resets_at` 支持 ISO-8601 与 epoch（秒/毫秒自动识别）
         */
        internal fun parseUsageResponse(root: JsonObject): ClaudeQuota {
            fun bucket(key: String): ClaudeUsageWindow? {
                val node = root[key] as? JsonObject ?: return null
                val percent = (node["utilization"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() } ?: return null
                return ClaudeUsageWindow(
                    label = key,
                    percent = percent.coerceIn(0.0, 100.0).toFloat(),
                    resetAtMillis = parseResetEpochMillis(
                        (node["resets_at"] as? JsonPrimitive)?.contentOrNull
                    )
                )
            }

            val modelWindows = mutableListOf<ClaudeUsageWindow>()
            for ((key, label) in listOf(
                "seven_day_fable" to "Fable",
                "seven_day_opus" to "Opus",
                "seven_day_sonnet" to "Sonnet"
            )) {
                val w = bucket(key) ?: continue
                modelWindows += w.copy(label = label)
            }

            val knownLabels = modelWindows.map { it.label.lowercase(Locale.US) }.toMutableSet()
            (root["limits"] as? JsonArray)?.forEach { raw ->
                val limit = raw as? JsonObject ?: return@forEach
                val kind = (limit["kind"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.US)
                if (kind != "weekly_scoped") return@forEach
                val percent = (limit["percent"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() } ?: return@forEach
                val scope = limit["scope"] as? JsonObject
                val model = scope?.get("model") as? JsonObject
                val rawLabel = (model?.get("display_name") as? JsonPrimitive)?.contentOrNull?.trim()
                if (rawLabel.isNullOrBlank()) return@forEach
                val shortLabel = when {
                    rawLabel.contains("fable", ignoreCase = true) -> "Fable"
                    rawLabel.contains("opus", ignoreCase = true) -> "Opus"
                    rawLabel.contains("sonnet", ignoreCase = true) -> "Sonnet"
                    else -> rawLabel
                }
                if (!knownLabels.add(shortLabel.lowercase(Locale.US))) return@forEach
                modelWindows += ClaudeUsageWindow(
                    label = shortLabel,
                    percent = percent.coerceIn(0.0, 100.0).toFloat(),
                    resetAtMillis = parseResetEpochMillis(
                        (limit["resets_at"] as? JsonPrimitive)?.contentOrNull
                    )
                )
            }

            return ClaudeQuota(
                fiveHour = bucket("five_hour"),
                weekly = bucket("seven_day"),
                modelWindows = modelWindows
            )
        }

        /** `resets_at` → epoch millis：数字（秒/毫秒）或 ISO-8601；无法解析返回 null。 */
        internal fun parseResetEpochMillis(raw: String?): Long? {
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

/**
 * Claude 配额解析结果。
 * @param fiveHour 5h 会话窗口（`five_hour`）
 * @param weekly 每周窗口（`seven_day`）
 * @param modelWindows 模型级周窗口（Opus / Sonnet / Fable 等）
 */
internal data class ClaudeQuota(
    val fiveHour: ClaudeUsageWindow? = null,
    val weekly: ClaudeUsageWindow? = null,
    val modelWindows: List<ClaudeUsageWindow> = emptyList()
) {
    val isEmpty: Boolean
        get() = fiveHour == null && weekly == null && modelWindows.isEmpty()
}

/**
 * 单个配额窗口。
 * @param label 窗口标识（模型级窗口为模型短名）
 * @param percent 已用百分比（0..100）
 * @param resetAtMillis 重置时刻（epoch millis；null = 接口未返回）
 */
internal data class ClaudeUsageWindow(
    val label: String,
    val percent: Float,
    val resetAtMillis: Long? = null
)

/**
 * 解析 Claude messages 响应，提取回复文本与 token 用量。
 */
internal fun parseMessagesResponse(responseBody: String, model: String): TriggerSummary {
    val json = Json { ignoreUnknownKeys = true }
    return try {
        val root = json.parseToJsonElement(responseBody) as? JsonObject
        val content = root?.get("content") as? JsonArray
        val text = content?.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
            (obj["text"] as? JsonPrimitive)?.contentOrNull
        }?.joinToString("")
        val usage = root?.get("usage") as? JsonObject
        TriggerSummary(
            model = model,
            reply = text?.takeIf { it.isNotBlank() },
            inputTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.contentOrNull,
            outputTokens = (usage?.get("output_tokens") as? JsonPrimitive)?.contentOrNull,
            totalTokens = null,
            parseFailed = text == null
        )
    } catch (_: Exception) {
        TriggerSummary(model = model, reply = null, parseFailed = true)
    }
}
