package com.rainy.token.data.repository

import com.rainy.token.data.local.UsageRecord
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * OpenCode 使用量统计仓库。
 *
 * 通过 POST `/_server` 调用 SolidStart server function `getUsageInfo`，
 * 解析 flight 响应中内嵌的 `$R[N]={id:"usg_...", ...}` hydration 数据。
 *
 * 翻页协议：
 * - cursor=0 → 最新 50 条
 * - cursor++ → 逐页回溯
 * - 当某页返回 0 条或 <50 条时到底
 */
@Singleton
class OpenCodeUsageRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository
) {
    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private const val SERVER_ID = "bfd684bfc2e4eed05cd0b518f5e4eafd3f3376e3938abb9e536e7c03df831e5c"
        private const val PAGE_SIZE = 50
        /** cost / DENOM = USD */
        const val COST_DENOM = 100_000_000L
    }

    private suspend fun getCredential(): Credential.SessionCredential {
        val c = credentialRepository.get(ServiceType.OPENCODE_GO)
            ?: throw RepositoryError.InvalidCredential()
        if (c !is Credential.SessionCredential || c.authCookie.isNullOrBlank() || c.workspaceId.isNullOrBlank()) {
            throw RepositoryError.InvalidCredential()
        }
        return c
    }

    /**
     * 获取指定游标页的用量记录。
     * cursor=0 为最新页。
     */
    suspend fun fetchPage(cursor: Int): Result<List<UsageRecord>> = withContext(Dispatchers.IO) {
        val credential = try {
            getCredential()
        } catch (e: RepositoryError) {
            return@withContext Result.failure(e)
        }

        val bodyJson = """{"t":{"t":9,"i":0,"l":2,"a":[{"t":1,"s":"${credential.workspaceId}"},{"t":0,"s":$cursor}],"o":0},"f":31,"m":[]}"""

        val request = Request.Builder()
            .url("https://opencode.ai/_server")
            .header("Content-Type", "application/json")
            .header("Cookie", "auth=${credential.authCookie}")
            .header("x-server-id", SERVER_ID)
            .header("x-server-instance", "server-fn:94")
            .header("Referer", "https://opencode.ai/workspace/${credential.workspaceId}/usage")
            .header("Origin", "https://opencode.ai")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext Result.failure(RepositoryError.InvalidCredential())
                }
                return@withContext Result.failure(RepositoryError.ServerError(resp.code))
            }

            val body = resp.body?.string() ?: return@withContext Result.failure(
                RepositoryError.ParseError("响应体为空")
            )

            val records = parseUsageRecords(body)
            Result.success(records)
        }
    }

    /**
     * 解析 SolidStart flight 响应中的 usage 记录。
     *
     * 响应格式为 JS 代码片段，usage 数据嵌在 `$R[N]={id:"usg_",...}` 中。
     * 匹配策略：找 `id:"usg_` 定位每条记录，用括号计数提取完整对象。
     */
    private fun parseUsageRecords(body: String): List<UsageRecord> {
        val records = mutableListOf<UsageRecord>()
        val marker = "id:\"usg_"
        var searchFrom = 0

        while (true) {
            val idIdx = body.indexOf(marker, searchFrom)
            if (idIdx < 0) break

            // 向前找最近的 '{'
            val braceStart = body.lastIndexOf('{', idIdx)
            if (braceStart < 0 || idIdx - braceStart > 200) {
                searchFrom = idIdx + 1
                continue
            }

            val braceEnd = findMatchingBrace(body, braceStart) ?: run {
                searchFrom = idIdx + 1
                continue
            }

            val obj = body.substring(braceStart, braceEnd + 1)
            val record = parseUsageObject(obj)
            if (record != null) {
                records.add(record)
            }
            searchFrom = braceEnd + 1
        }

        return records
    }

    private fun parseUsageObject(obj: String): UsageRecord? {
        val id = extractString(obj, "id") ?: return null
        if (!id.startsWith("usg_")) return null

        val workspaceId = extractString(obj, "workspaceID") ?: return null
        val model = extractString(obj, "model") ?: return null
        val provider = extractString(obj, "provider") ?: return null
        val keyId = extractString(obj, "keyID") ?: ""
        val sessionId = extractString(obj, "sessionID") ?: ""

        val timeCreated = extractDate(obj, "timeCreated") ?: 0L
        val timeUpdated = extractDate(obj, "timeUpdated") ?: 0L
        val inputTokens = extractNumber(obj, "inputTokens")
        val outputTokens = extractNumber(obj, "outputTokens")
        val reasoningTokens = extractNumber(obj, "reasoningTokens")
        val cacheReadTokens = extractNumber(obj, "cacheReadTokens")
        val cacheWrite5m = extractNullableNumber(obj, "cacheWrite5mTokens")
        val cacheWrite1h = extractNullableNumber(obj, "cacheWrite1hTokens")
        val cost = extractNumber(obj, "cost")
        val plan = extractString(obj, "plan")

        return UsageRecord(
            id = id,
            workspaceId = workspaceId,
            timeCreated = timeCreated,
            timeUpdated = timeUpdated,
            model = model,
            provider = provider,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWrite5mTokens = cacheWrite5m ?: 0,
            cacheWrite1hTokens = cacheWrite1h ?: 0,
            cost = cost,
            keyId = keyId,
            sessionId = sessionId,
            enrichmentPlan = plan ?: ""
        )
    }

    // ---- 解析工具 ----

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

    private fun extractString(obj: String, key: String): String? {
        // 匹配 "key":"value" 或 key:"value"
        val pattern = Regex("""["']?${Regex.escape(key)}["']?\s*:\s*"([^"]*)"""")
        return pattern.find(obj)?.groupValues?.get(1)
    }

    private fun extractNumber(obj: String, key: String): Long {
        val pattern = Regex("""["']?${Regex.escape(key)}["']?\s*:\s*(-?\d+)""")
        return pattern.find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun extractNullableNumber(obj: String, key: String): Long? {
        // 先检查是否为 null
        val nullPattern = Regex("""["']?${Regex.escape(key)}["']?\s*:\s*null""")
        if (nullPattern.containsMatchIn(obj)) return null
        val num = extractNumber(obj, key)
        return if (num == 0L && !Regex("""["']?${Regex.escape(key)}["']?\s*:\s*0""").containsMatchIn(obj)) null
        else num
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun extractDate(obj: String, key: String): Long? {
        // 匹配模式: key:new Date("2026-06-15T20:46:31.000Z")
        val pattern = Regex("""["']?${Regex.escape(key)}["']?\s*:\s*(?:\$\w+\[?\d*\]?=\s*)?new Date\("([^"]+)"\)""")
        return pattern.find(obj)?.groupValues?.get(1)?.let {
            runCatching { dateFormat.parse(it)?.time }.getOrNull()
        }
    }
}