package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for [CommandCodeUsageRepository] 的纯解析逻辑。
 *
 * 覆盖 2026-08 后端新格式（cost 改由 meta.totalCost 提供、服务端 nextCursor
 * 分页、provider / creditsTotal / cacheReadInputTokens 移除）以及旧格式兼容。
 * 全部为纯 JVM 测试，无网络。
 */
class CommandCodeUsageRepositoryTest {

    /** 生成一条 usage 记录 JSON（新格式字段）。 */
    private fun usageJson(
        id: String,
        tokensIn: Long,
        tokensOut: Long,
        totalCost: Number,
        provider: String? = null,
        cacheReadInputTokens: Long? = null
    ): String = buildString {
        append("{\"id\":\"").append(id).append("\",")
        append("\"createdAt\":\"2026-08-28T20:27:30.400Z\",")
        append("\"tokensIn\":\"").append(tokensIn).append("\",")
        append("\"tokensOut\":\"").append(tokensOut).append("\",")
        append("\"durationTotal\":\"2960\",")
        append("\"status\":\"completed\",")
        append("\"message\":null,")
        append("\"meta\":{")
        append("\"totalCost\":").append(totalCost).append(",")
        append("\"inputCost\":0.001,")
        append("\"outputCost\":0.0002,")
        append("\"cacheCost\":0.0003,")
        append("\"model\":\"deepseek/deepseek-v4-flash\",")
        append("\"traceId\":\"trace-1\"")
        if (provider != null) append(",\"provider\":\"").append(provider).append("\"")
        if (cacheReadInputTokens != null) append(",\"cacheReadInputTokens\":").append(cacheReadInputTokens)
        append("},")
        append("\"type\":\"api\",")
        append("\"mode\":\"agent\"}")
    }

    private fun newFormatBody(
        totalCost: Number = 0.001651536,
        tokensIn: Long = 46314,
        tokensOut: Long = 118,
        provider: String? = null,
        cacheReadInputTokens: Long? = null,
        nextCursor: String? = null,
        count: Int = 1
    ): String = buildString {
        append("""{"usages":[""")
        for (i in 0 until count) {
            if (i > 0) append(",")
            append(usageJson("rec-$i", tokensIn + i, tokensOut, totalCost, provider, cacheReadInputTokens))
        }
        append("]")
        if (nextCursor != null) append(""","nextCursor":"$nextCursor"""")
        append("""}""")
    }

    @Test
    fun `new format parses cost from meta totalCost`() {
        val (records, next) = CommandCodeUsageRepository.parseUsageResponse(
            newFormatBody(totalCost = 0.001651536, tokensIn = 46314, tokensOut = 118)
        )
        assertEquals(1, records.size)
        val r = records.first()
        assertEquals((0.001651536 * CommandCodeUsageRepository.COST_DENOM).toLong(), r.cost)
        assertEquals(46314L, r.inputTokens)
        assertEquals(118L, r.outputTokens)
        assertEquals(0L, r.cacheReadTokens)
        assertEquals("", r.provider)
        assertEquals("deepseek/deepseek-v4-flash", r.model)
        assertNull(next)
    }

    @Test
    fun `new format accepts integer totalCost`() {
        val (records, _) = CommandCodeUsageRepository.parseUsageResponse(
            newFormatBody(totalCost = 0)
        )
        assertEquals(1, records.size)
        assertEquals(0L, records.first().cost)
    }

    @Test
    fun `new format uses server nextCursor`() {
        val (_, next) = CommandCodeUsageRepository.parseUsageResponse(
            newFormatBody(nextCursor = "eyJjdXJzb3I")
        )
        assertEquals("eyJjdXJzb3I", next)
    }

    @Test
    fun `falls back to computed cursor on full page without server cursor`() {
        val (records, next) = CommandCodeUsageRepository.parseUsageResponse(
            newFormatBody(count = CommandCodeUsageRepository.PAGE_SIZE, totalCost = 0.001, nextCursor = null)
        )
        assertEquals(CommandCodeUsageRepository.PAGE_SIZE, records.size)
        assertNotNull(next)
        val decoded = String(Base64.getUrlDecoder().decode(next!!))
        assertTrue(decoded.contains("\"id\":\"rec-${CommandCodeUsageRepository.PAGE_SIZE - 1}\""))
    }

    @Test
    fun `empty page when usages missing`() {
        val (records, next) = CommandCodeUsageRepository.parseUsageResponse("""{"limit":100}""")
        assertTrue(records.isEmpty())
        assertNull(next)
    }

    @Test
    fun `json null nextCursor is treated as no cursor on small page`() {
        // 服务端满页/末页可能返回显式 "nextCursor":null，旧实现会解析成字符串 "null" 导致死循环
        val body = """{"usages":[${usageJson("rec-0", 100, 10, 0.001)}],"nextCursor":null}"""
        val (records, next) = CommandCodeUsageRepository.parseUsageResponse(body)
        assertEquals(1, records.size)
        assertNull(next)
    }

    @Test
    fun `json null nextCursor falls back to computed cursor on full page`() {
        // 关键回归场景：满页 + nextCursor:null → 必须回退自编码游标，而不是返回 "null" 字符串
        val body = buildString {
            append("""{"usages":[""")
            for (i in 0 until CommandCodeUsageRepository.PAGE_SIZE) {
                if (i > 0) append(",")
                append(usageJson("rec-$i", 100L + i, 10L, 0.001))
            }
            append("""],"nextCursor":null}""")
        }
        val (records, next) = CommandCodeUsageRepository.parseUsageResponse(body)
        assertEquals(CommandCodeUsageRepository.PAGE_SIZE, records.size)
        assertNotNull(next)
        val decoded = String(Base64.getUrlDecoder().decode(next!!))
        assertTrue(decoded.contains("\"id\":\"rec-${CommandCodeUsageRepository.PAGE_SIZE - 1}\""))
    }

    @Test
    fun `legacy format stays compatible`() {
        val body = """
            {"usages":[{"id":"old-1","createdAt":"2026-08-20T10:00:00.000Z",
            "tokensIn":"500","tokensOut":"50","tokensTotal":"550",
            "creditsTotal":"0.01",
            "meta":{"model":"gpt-4o","provider":"morph","cacheReadInputTokens":200}}]}
        """.trimIndent()
        val (records, _) = CommandCodeUsageRepository.parseUsageResponse(body)
        assertEquals(1, records.size)
        val r = records.first()
        assertEquals((0.01 * CommandCodeUsageRepository.COST_DENOM).toLong(), r.cost)
        assertEquals(300L, r.inputTokens)   // 500 - 200
        assertEquals(200L, r.cacheReadTokens)
        assertEquals("morph", r.provider)
    }
}