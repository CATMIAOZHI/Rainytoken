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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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
 * usage 端点用 claude-cli User-Agent + anthropic-beta 列表。
 *
 * 「一键激活用量」未实现：Claude 的订阅配额只能通过官方 messages 端点消耗额度来触发，
 * 会真实扣减用户额度，故不提供该入口（模型列表接口随之也不需要）。
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

    // ── Token 刷新 ──

    private fun tokenNeedsRefresh(cred: Credential.ClaudeCredential): Boolean =
        cred.expiresAt > 0L && System.currentTimeMillis() >= cred.expiresAt - REFRESH_BUFFER_MS

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
        private const val TOKEN_URL = "https://api.anthropic.com/v1/oauth/token"

        /** Claude Code CLI 的 OAuth client id（公开常量，与官方 CLI 同源）。 */
        private const val CLAUDE_CODE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

        /** usage 探针的 User-Agent（opencodex vendor-probes-oauth.ts 同款）。 */
        private const val USAGE_USER_AGENT = "claude-cli/2.1.63 (external, cli)"
        /** usage 探针的 anthropic-beta 列表（与 opencodex 逐项一致）。 */
        private const val USAGE_BETA =
            "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14," +
                "context-management-2025-06-27,prompt-caching-scope-2026-01-05"

        private const val REFRESH_BUFFER_MS = 5L * 60 * 1000
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

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
