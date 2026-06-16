package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
     * 解析 SolidJS SSR hydration 输出。匹配模式形如：
     *   `rollingUsage:$R[0]={usagePercent:42,resetInSec:12345}`
     *
     * 用精确前缀 "field:$R[" 定位 hydration 数据中的字段声明，
     * 避免命中 HTML 其他位置（如 JS 代码注释、模板字符串）的同名文本。
     * 用括号计数匹配闭合 "}"，正确处理嵌套对象。
     */
    private fun parseWindows(html: String): Map<String, ScrapedWindow> {
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

    private data class ScrapedWindow(val usagePercent: Int, val resetInSec: Long)

    companion object {
        private val SCRAPED_FIELDS = listOf("rollingUsage", "weeklyUsage", "monthlyUsage")
    }
}