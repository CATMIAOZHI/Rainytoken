package com.rainy.token.data.repository

import com.rainy.token.data.local.UsageRecord
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.CookieEntry
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.inject.Singleton

/**
 * 单页抓取结果。
 *
 * 前两个字段保持 (records, nextCursor) 顺序，兼容既有 `val (records, next) = parse(...)` 解构。
 *
 * @param rawCount 服务端原始返回条数（解析前），用于判断「是否满页」——不能用 records.size，
 *                 否则个别记录解析失败会被误判为「到底」并静默截断同步。
 * @param droppedCount 解析失败被丢弃的条数，用于把解析异常暴露成同步结果而不是静默吞掉。
 */
data class UsagePage(
    val records: List<UsageRecord>,
    val nextCursor: String?,
    val rawCount: Int = 0,
    val droppedCount: Int = 0
)

/**
 * CommandCode 用量记录仓库。
 *
 * 调 JSON API 分页抓取 usage 记录：
 *   GET https://api.commandcode.ai/internal/usage?limit=100
 *   GET https://api.commandcode.ai/internal/usage?limit=100&cursor=<base64>
 *
 * cursor 是末条记录的 { createdAt, id } 的 base64 编码（服务端 nextCursor 目前恒为 null，
 * 因此由客户端按末条自编码回退）。第一页不用 cursor。
 * 注意：服务端只保留最近约 24 小时的明细（滚动窗口），超过窗口的历史无法通过该接口取回。
 */
@Singleton
class CommandCodeUsageRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository
) {
    private val apiBase = "https://api.commandcode.ai"

    companion object {
        const val PAGE_SIZE = 100
        /** cost / DENOM = USD */
        const val COST_DENOM = 100_000_000L
        /** CCGO 用量数据在 UsageCache 中的 workspaceId 区分键 */
        const val CCGO_WORKSPACE_ID = "commandcode"

        private val json = Json { ignoreUnknownKeys = true }

        // ===== 纯解析逻辑（无网络依赖，可直接单测）=====

        /**
         * 解析 JSON 响应。
         *
         * 后端 2026-08 起改为新格式：
         *   - 顶层新增 nextCursor / limit / periodBasis / window；
         *     分页游标由服务端 nextCursor 直接给出（缺失时回退按末条自编码，兼容旧格式）。
         *   - 每条记录新增 durationTotal / status / message / type / mode；
         *     tokensTotal / creditsTotal 已移除。
         *   - meta 新增 totalCost / inputCost / outputCost / cacheCost / traceId；
         *     provider / cacheReadInputTokens 已移除。
         */
        internal fun parseUsageResponse(body: String): UsagePage {
            val root = json.parseToJsonElement(body).jsonObject
            val usages = root["usages"]?.jsonArray
                ?: return UsagePage(emptyList(), null, 0, 0)

            val records = usages.mapNotNull { elem ->
                parseUsageObject(elem.jsonObject)
            }
            val rawCount = usages.size
            val dropped = rawCount - records.size

            // 新格式：优先用服务端游标；缺失或显式 null 时回退按末条自编码（兼容旧格式）
            // 注意：JSON null 的 jsonPrimitive.content 是字符串 "null"，必须用 is JsonNull 拦截，
            // 否则会把 "null" 当游标传给服务端（被忽略→返回第一页），导致 fullSync 死循环。
            val serverCursor = root["nextCursor"]?.takeIf { it !is JsonNull }
                ?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
            // ★ 满页判定必须用服务端原始条数 rawCount，不能用解析后的 records.size：
            //   任意一条记录解析失败就会让解析后条数少 1，旧实现据此判定「到底」并静默中断整次同步，
            //   本地因此永久留下空洞（2026-09 实测确认的成因之一）。
            val nextCursor = serverCursor ?: if (rawCount >= PAGE_SIZE) {
                records.lastOrNull()?.let { encodeCursor(it.id, it.timeCreated) }
            } else null

            return UsagePage(records, nextCursor, rawCount, dropped)
        }

        /**
         * 解析单条 usage 对象。
         *
         * cost 来源：新格式 meta.totalCost（美元数值）；旧格式顶层 creditsTotal（兼容）。
         * provider：新格式已移除，缺失时为空字符串；旧格式 meta.provider 兼容读取。
         * cacheReadInputTokens：新格式已移除，缺失时按 0（tokensIn 不再拆分缓存命中）。
         */
        internal fun parseUsageObject(obj: JsonObject): UsageRecord? {
            val id = obj["id"]?.jsonPrimitive?.content ?: return null
            val createdAt = obj["createdAt"]?.jsonPrimitive?.content ?: return null
            val timeCreated = parseIsoDate(createdAt) ?: return null

            val tokensIn = obj["tokensIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val tokensOut = obj["tokensOut"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

            val meta = obj["meta"]?.jsonObject
            val creditsTotal = meta?.get("totalCost")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: obj["creditsTotal"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: 0.0
            val cost = (creditsTotal * COST_DENOM).toLong()

            val model = meta?.get("model")?.jsonPrimitive?.content ?: ""
            val provider = meta?.get("provider")?.jsonPrimitive?.content ?: ""
            val cacheReadInputTokens = meta?.get("cacheReadInputTokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

            // CCGO 的 tokensIn 是总输入（缓存命中 + 未命中），按 OCGO 惯例拆分为 inputTokens（未命中）和 cacheReadTokens（命中）
            // 新格式无缓存 token 数时无法拆分，inputTokens 直接取 tokensIn
            val inputMissTokens = (tokensIn - cacheReadInputTokens).coerceAtLeast(0)

            return UsageRecord(
                id = id,
                workspaceId = CCGO_WORKSPACE_ID,
                timeCreated = timeCreated,
                timeUpdated = timeCreated,
                model = model,
                provider = provider,
                inputTokens = inputMissTokens,
                outputTokens = tokensOut,
                reasoningTokens = 0L,
                cacheReadTokens = cacheReadInputTokens,
                cacheWrite5mTokens = 0L,
                cacheWrite1hTokens = 0L,
                cost = cost,
                keyId = "",
                sessionId = "",
                enrichmentPlan = ""
            )
        }

        private fun parseIsoDate(iso: String): Long? {
            // 处理末尾 Z 和时区偏移
            val normalized = iso
                .replace("Z", "X")
                .replace(Regex("""[+-]\d{2}:\d{2}$"""), "X")
            return try {
                // SimpleDateFormat 非线程安全，每次创建新实例
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'X'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(normalized)?.time
                    ?: run {
                        val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'X'", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        sdf2.parse(normalized)?.time
                    }
            } catch (_: Exception) { null }
        }

        /** 从记录信息编码为 base64 cursor */
        private fun encodeCursor(id: String, timeCreated: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val createdAt = sdf.format(java.util.Date(timeCreated))
            val cursorJson = """{"createdAt":"$createdAt","id":"$id"}"""
            return Base64.getUrlEncoder().withoutPadding().encodeToString(cursorJson.toByteArray())
        }
    }

    private suspend fun getCookieHeader(): String {
        val c = credentialRepository.get(ServiceType.COMMANDCODE_GO)
            ?: throw RepositoryError.InvalidCredential()
        if (c !is Credential.SessionCredential) {
            throw RepositoryError.InvalidCredential()
        }
        // 优先用 cookies 列表，否则尝试从 authCookie 字段解析
        if (c.cookies.isNotEmpty()) {
            return c.cookies.joinToString("; ") { "${it.name}=${it.value}" }
        }
        // fallback: 如果用户通过旧版接口存了 authCookie，尝试恢复
        throw RepositoryError.InvalidCredential()
    }

    /**
     * 获取指定游标页的用量记录。
     * cursor=null 为最新页。
     * 返回 [UsagePage]：是否到底由服务端原始条数（rawCount）判定，
     * 不能用解析后条数 —— 个别记录解析失败会让解析后条数变少而被误判为「到底」。
     */
    suspend fun fetchPage(cursor: String?): Result<UsagePage> =
        withContext(Dispatchers.IO) {
            val cookieHeader = try {
                getCookieHeader()
            } catch (e: RepositoryError) {
                return@withContext Result.failure(e)
            }

            val url = buildString {
                append("$apiBase/internal/usage?limit=$PAGE_SIZE")
                if (cursor != null) append("&cursor=$cursor")
            }

            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader)
                .header("Accept", "application/json")
                .header("Origin", "https://commandcode.ai")
                .header("Referer", "https://commandcode.ai/")
                .get()
                .build()

            val response = try {
                okHttpClient.newCall(request).execute()
            } catch (e: IOException) {
                return@withContext Result.failure(RepositoryError.Network(e))
            }

            response.use { resp ->
                val body = try {
                    resp.body?.string()
                } catch (e: IOException) {
                    // 连接中途断开时 string() 会抛 IOException：不能让异常穿出 fetchPage
                    //（前台同步挂在 viewModelScope 上，未捕获会直接崩主线程）
                    return@withContext Result.failure(RepositoryError.Network(e))
                }
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) {
                        val detail = if (body != null && body.length < 200) "：$body" else ""
                        return@withContext Result.failure(RepositoryError.InvalidCredential(
                            "HTTP ${resp.code}$detail"
                        ))
                    }
                    return@withContext Result.failure(RepositoryError.ServerError(resp.code))
                }

                if (body == null) return@withContext Result.failure(
                    RepositoryError.ParseError(RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空")
                )

                val page = try {
                    parseUsageResponse(body)
                } catch (e: Exception) {
                    // 200 但响应不是预期 JSON（网关 HTML / 维护页等）：降级为解析错误，
                    // 不能让异常穿出 fetchPage —— 前台同步挂在 viewModelScope 上，会直接崩主线程。
                    return@withContext Result.failure(
                        RepositoryError.ParseError(
                            RepositoryError.ParseErrorReason.MALFORMED_RESPONSE,
                            e.message ?: "unparsable usage response"
                        )
                    )
                }
                Result.success(page)
            }
        }
}