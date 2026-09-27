package com.rainy.token.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClaudeRepository.parseUsageResponse],
 * [ClaudeRepository.parseResetEpochMillis], [ClaudeRepository.claudeCodeSessionId]
 * and [parseMessagesResponse].
 *
 * **JsonNull safety red line**: 显式 JSON null（`"utilization": null`）必须通过
 * `as? JsonPrimitive` 安全跳过，不能走 `jsonPrimitive`（会抛异常）。
 *
 * 全部为纯 JVM 测试 —— 不依赖 Android 框架、不联网。
 */
class ClaudeRepositoryTest {

    private fun parse(json: String): ClaudeQuota {
        val root = Json.parseToJsonElement(json) as JsonObject
        return ClaudeRepository.parseUsageResponse(root)
    }

    // ── parseUsageResponse：基本窗口 ──

    @Test
    fun `parse five hour and seven day windows`() {
        val quota = parse(
            """
            {"five_hour":{"utilization":12.5,"resets_at":"2026-09-25T12:00:00Z"},
             "seven_day":{"utilization":40,"resets_at":1760000000}}
            """.trimIndent()
        )
        assertNotNull(quota.fiveHour)
        val fiveHour = requireNotNull(quota.fiveHour)
        assertEquals(12.5f, fiveHour.percent, 0.001f)
        assertEquals(
            java.time.Instant.parse("2026-09-25T12:00:00Z").toEpochMilli(),
            fiveHour.resetAtMillis
        )
        assertTrue(quota.modelWindows.isEmpty())
        val weekly = requireNotNull(quota.weekly)
        assertEquals(40f, weekly.percent, 0.001f)
        assertEquals(1760000000L * 1000, weekly.resetAtMillis)
    }

    @Test
    fun `percent is clamped to valid range`() {
        val quota = parse(
            """{"five_hour":{"utilization":150},"seven_day":{"utilization":-3}}"""
        )
        assertEquals(100f, quota.fiveHour!!.percent, 0.001f)
        assertEquals(0f, quota.weekly!!.percent, 0.001f)
    }

    @Test
    fun `model level buckets are mapped to short labels`() {
        val quota = parse(
            """
            {"seven_day_fable":{"utilization":10},
             "seven_day_opus":{"utilization":20},
             "seven_day_sonnet":{"utilization":30}}
            """.trimIndent()
        )
        assertEquals(listOf("Fable", "Opus", "Sonnet"), quota.modelWindows.map { it.label })
        assertEquals(20f, quota.modelWindows[1].percent, 0.001f)
    }

    @Test
    fun `limits weekly_scoped entries are appended`() {
        val quota = parse(
            """
            {"seven_day_opus":{"utilization":1},
             "limits":[
               {"kind":"weekly_scoped","percent":7,
                "scope":{"model":{"display_name":"Opus 4.1"}}},
               {"kind":"weekly_scoped","percent":9.5,
                "scope":{"model":{"display_name":"Haiku 4"}}}
             ]}
            """.trimIndent()
        )
        // Opus 已由 seven_day_opus 提供 → 去重；Haiku 为新增
        assertEquals(listOf("Opus", "Haiku 4"), quota.modelWindows.map { it.label })
        assertEquals(9.5f, quota.modelWindows[1].percent, 0.001f)
    }

    @Test
    fun `limits with other kind are ignored`() {
        val quota = parse(
            """
            {"limits":[
               {"kind":"daily","percent":50,"scope":{"model":{"display_name":"Opus"}}},
               {"kind":"weekly_scoped","percent":5,"scope":{"model":{"display_name":""}}}
             ]}
            """.trimIndent()
        )
        assertTrue(quota.modelWindows.isEmpty())
        assertTrue(quota.isEmpty)
    }

    @Test
    fun `explicit json null utilization is skipped without throwing`() {
        val quota = parse(
            """
            {"five_hour":{"utilization":null,"resets_at":null},
             "seven_day":null}
            """.trimIndent()
        )
        assertNull(quota.fiveHour)
        assertNull(quota.weekly)
        assertTrue(quota.isEmpty)
    }

    @Test
    fun `non numeric utilization is skipped`() {
        val quota = parse("""{"five_hour":{"utilization":"abc"}}""")
        assertNull(quota.fiveHour)
    }

    @Test
    fun `empty object yields empty quota`() {
        assertTrue(parse("{}").isEmpty)
    }

    // ── parseResetEpochMillis ──

    @Test
    fun `reset epoch seconds become millis`() {
        assertEquals(1_760_000_000_000L, ClaudeRepository.parseResetEpochMillis("1760000000"))
    }

    @Test
    fun `reset epoch millis stay millis`() {
        assertEquals(1_760_000_000_000L, ClaudeRepository.parseResetEpochMillis("1760000000000"))
    }

    @Test
    fun `reset iso 8601 is parsed`() {
        assertEquals(
            1_760_000_000_000L,
            ClaudeRepository.parseResetEpochMillis("2025-10-09T08:53:20Z")
        )
    }

    @Test
    fun `reset invalid returns null`() {
        assertNull(ClaudeRepository.parseResetEpochMillis(null))
        assertNull(ClaudeRepository.parseResetEpochMillis(""))
        assertNull(ClaudeRepository.parseResetEpochMillis("   "))
        assertNull(ClaudeRepository.parseResetEpochMillis("not-a-date"))
        assertNull(ClaudeRepository.parseResetEpochMillis("0"))
    }

    // ── claudeCodeSessionId ──

    @Test
    fun `session id has uuidv4 shape and is stable`() {
        val id = ClaudeRepository.claudeCodeSessionId("sk-ant-oat01-example")
        val regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue("unexpected session id: $id", regex.matches(id))
        assertEquals(id, ClaudeRepository.claudeCodeSessionId("sk-ant-oat01-example"))
    }

    @Test
    fun `session id differs per token`() {
        val a = ClaudeRepository.claudeCodeSessionId("token-a")
        val b = ClaudeRepository.claudeCodeSessionId("token-b")
        assertTrue(a != b)
    }

    @Test
    fun `session id for blank token is still valid`() {
        val id = ClaudeRepository.claudeCodeSessionId("")
        assertTrue(id.matches(Regex("^[0-9a-f-]{36}$")))
    }

    // ── parseMessagesResponse ──

    @Test
    fun `messages response extracts text and tokens`() {
        val summary = parseMessagesResponse(
            """
            {"content":[{"type":"text","text":"hi there"}],
             "usage":{"input_tokens":4,"output_tokens":2}}
            """.trimIndent(),
            "claude-sonnet-4-5"
        )
        assertEquals("claude-sonnet-4-5", summary.model)
        assertEquals("hi there", summary.reply)
        assertEquals("4", summary.inputTokens)
        assertEquals("2", summary.outputTokens)
        assertEquals(false, summary.parseFailed)
    }

    @Test
    fun `messages response without text marks parse failed`() {
        val summary = parseMessagesResponse("""{"usage":{"input_tokens":1}}""", "m")
        assertNull(summary.reply)
        assertEquals(true, summary.parseFailed)
    }

    @Test
    fun `messages response with empty content keeps parse ok`() {
        val summary = parseMessagesResponse("""{"content":[]}""", "m")
        assertNull(summary.reply)
        assertEquals(false, summary.parseFailed)
    }

    @Test
    fun `messages response malformed marks parse failed`() {
        val summary = parseMessagesResponse("<html>oops</html>", "m")
        assertEquals(true, summary.parseFailed)
        assertNull(summary.reply)
    }
}
