package com.rainy.token.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClaudeRepository.parseUsageResponse]
 * and [ClaudeRepository.parseResetEpochMillis].
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
}
