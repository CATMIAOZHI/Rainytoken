package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    companion object {
        private const val SETTINGS_URL = "https://ollama.com/settings"
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

}