package com.rainy.token.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CodexRepository.parseUsageWindows], [CodexRepository.durationLabel],
 * and [parseSseResponse].
 *
 * **JsonNull safety red line**: tests verify that explicit JSON null values
 * (used_percent = null) are handled correctly via `as? JsonPrimitive` / `as? JsonObject`
 * casts, not `?.jsonPrimitive` which would throw on JsonNull.
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class CodexRepositoryTest {

    // ── durationLabel ──

    @Test
    fun `durationLabel null returns Usage`() {
        assertEquals("Usage", CodexRepository.durationLabel(null))
    }

    @Test
    fun `durationLabel weekly threshold`() {
        // 10079 minutes = ~7 days. 7 * 86400 = 604800 seconds = 10080 minutes ≥ 10079
        assertEquals("每周", CodexRepository.durationLabel(604800L))
    }

    @Test
    fun `durationLabel just under weekly stays as days`() {
        // 10078 minutes = 604680 seconds → 604680/86400 = 6.99... → 6d
        assertEquals("6d", CodexRepository.durationLabel(604680L))
    }

    @Test
    fun `durationLabel daily`() {
        assertEquals("1d", CodexRepository.durationLabel(86400L))
        assertEquals("2d", CodexRepository.durationLabel(2 * 86400L))
    }

    @Test
    fun `durationLabel just under daily stays as hours`() {
        // 1438 minutes = 86280 seconds → 86280/3600 = 23.96 → 23h
        assertEquals("23h", CodexRepository.durationLabel(86280L))
    }

    @Test
    fun `durationLabel hourly`() {
        assertEquals("1h", CodexRepository.durationLabel(3600L))
        assertEquals("5h", CodexRepository.durationLabel(5 * 3600L))
    }

    @Test
    fun `durationLabel just under hourly stays as minutes`() {
        // 59 minutes = 3540 seconds → 3540/60 = 59 → 59m
        assertEquals("59m", CodexRepository.durationLabel(3540L))
    }

    @Test
    fun `durationLabel sub-minute defaults to 1m`() {
        assertEquals("1m", CodexRepository.durationLabel(30L))
        assertEquals("1m", CodexRepository.durationLabel(1L))
    }

    // ── parseUsageWindows ──

    @Test
    fun `parseUsageWindows extracts primary and secondary windows`() {
        val window = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(42.0),
                "reset_at" to JsonPrimitive(1719500400L),
                "limit_window_seconds" to JsonPrimitive(18000L)
            )),
            "secondary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(15.0),
                "reset_at" to JsonPrimitive(1719932400L),
                "limit_window_seconds" to JsonPrimitive(604800L)
            ))
        ))
        val data = JsonObject(mapOf("rate_limit" to window))
        val windows = CodexRepository.parseUsageWindows(data)
        assertEquals(2, windows.size)
        assertEquals("5h", windows[0].label)
        assertEquals(58, windows[0].remainingPct)  // 100 - 42 = 58
        assertEquals("每周", windows[1].label)
        assertEquals(85, windows[1].remainingPct)  // 100 - 15 = 85
    }

    @Test
    fun `parseUsageWindows handles used_percent null via JsonNull`() {
        // JsonNull red line: used_percent = null should be skipped (as? JsonPrimitive returns null)
        val window = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonNull,
                "reset_at" to JsonPrimitive(1719500400L),
                "limit_window_seconds" to JsonPrimitive(18000L)
            )),
            "secondary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(15.0),
                "reset_at" to JsonPrimitive(1719932400L),
                "limit_window_seconds" to JsonPrimitive(604800L)
            ))
        ))
        val data = JsonObject(mapOf("rate_limit" to window))
        val windows = CodexRepository.parseUsageWindows(data)
        // Only secondary_window should be parsed (primary skipped due to JsonNull used_percent)
        assertEquals(1, windows.size)
        assertEquals("每周", windows[0].label)
    }

    @Test
    fun `parseUsageWindows handles missing rate_limit`() {
        val data = JsonObject(emptyMap())
        val windows = CodexRepository.parseUsageWindows(data)
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `parseUsageWindows handles null rate_limit`() {
        val data = JsonObject(mapOf("rate_limit" to JsonNull))
        val windows = CodexRepository.parseUsageWindows(data)
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `parseUsageWindows calculates remaining percentage`() {
        val window = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(42.0),
                "reset_at" to JsonPrimitive(1719500400L),
                "limit_window_seconds" to JsonPrimitive(18000L)
            ))
        ))
        val data = JsonObject(mapOf("rate_limit" to window))
        val windows = CodexRepository.parseUsageWindows(data)
        assertEquals(1, windows.size)
        assertEquals(58, windows[0].remainingPct)  // 100 - 42 = 58
        assertEquals("5h", windows[0].label)
    }

    @Test
    fun `parseUsageWindows clamps remaining to 0-100`() {
        val window = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(150.0),  // over 100%
                "reset_at" to JsonPrimitive(1719500400L),
                "limit_window_seconds" to JsonPrimitive(18000L)
            ))
        ))
        val data = JsonObject(mapOf("rate_limit" to window))
        val windows = CodexRepository.parseUsageWindows(data)
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].remainingPct)  // (100 - 150).coerceIn(0, 100) = 0
    }

    @Test
    fun `parseUsageWindows extracts reset_at timestamp in millis`() {
        val resetAtSec = 1719500400L
        val window = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(42.0),
                "reset_at" to JsonPrimitive(resetAtSec),
                "limit_window_seconds" to JsonPrimitive(18000L)
            ))
        ))
        val data = JsonObject(mapOf("rate_limit" to window))
        val windows = CodexRepository.parseUsageWindows(data)
        assertEquals(1, windows.size)
        assertEquals(resetAtSec * 1000L, windows[0].resetAt)
    }

    @Test
    fun `parseUsageWindows handles null reset_at`() {
        val window = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(42.0),
                "reset_at" to JsonNull,
                "limit_window_seconds" to JsonPrimitive(18000L)
            ))
        ))
        val data = JsonObject(mapOf("rate_limit" to window))
        val windows = CodexRepository.parseUsageWindows(data)
        assertEquals(1, windows.size)
        assertEquals(null, windows[0].resetAt)
    }

    @Test
    fun `parseUsageWindows parses additional_rate_limits`() {
        val primaryWindow = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(10.0),
                "reset_at" to JsonPrimitive(1719500400L),
                "limit_window_seconds" to JsonPrimitive(18000L)
            ))
        ))
        val additionalRl = JsonObject(mapOf(
            "primary_window" to JsonObject(mapOf(
                "used_percent" to JsonPrimitive(20.0),
                "reset_at" to JsonPrimitive(1719932400L),
                "limit_window_seconds" to JsonPrimitive(604800L)
            ))
        ))
        val data = JsonObject(mapOf(
            "rate_limit" to primaryWindow,
            "additional_rate_limits" to kotlinx.serialization.json.JsonArray(
                listOf(JsonObject(mapOf("rate_limit" to additionalRl)))
            )
        ))
        val windows = CodexRepository.parseUsageWindows(data)
        assertEquals(2, windows.size)
    }

    @Test
    fun `parseUsageWindows skips missing window keys`() {
        // rate_limit with no primary_window or secondary_window
        val data = JsonObject(mapOf("rate_limit" to JsonObject(emptyMap())))
        val windows = CodexRepository.parseUsageWindows(data)
        assertTrue(windows.isEmpty())
    }

    // ── parseSseResponse ──

    @Test
    fun `parseSseResponse extracts output text and usage from completed event`() {
        val sse = """
            data: {"type":"response.output_text.done","text":"Hello, world!"}
            data: {"type":"response.completed","response":{"id":"resp_123","usage":{"input_tokens":"100","output_tokens":"50"}}}
            data: [DONE]
        """.trimIndent()

        val result = parseSseResponse(sse, "gpt-5.6")
        assertTrue(result.contains("Hello, world!"))
        assertTrue(result.contains("input=100"))
        assertTrue(result.contains("output=50"))
        assertTrue(result.contains("resp_123"))
        assertTrue(result.contains("gpt-5.6"))
    }

    @Test
    fun `parseSseResponse handles empty output text`() {
        val sse = """
            data: {"type":"response.completed","response":{"id":"resp_456","usage":{"input_tokens":"10","output_tokens":"5"}}}
            data: [DONE]
        """.trimIndent()

        val result = parseSseResponse(sse, "o4-mini")
        assertTrue(result.contains("(空)"))
        assertTrue(result.contains("input=10"))
        assertTrue(result.contains("output=5"))
    }

    @Test
    fun `parseSseResponse handles no usage data`() {
        val sse = """
            data: {"type":"response.output_text.done","text":"Hi there"}
            data: {"type":"response.completed","response":{"id":"resp_789"}}
            data: [DONE]
        """.trimIndent()

        val result = parseSseResponse(sse, "gpt-5.6")
        assertTrue(result.contains("Hi there"))
        // Should not contain usage line when no usage data
        assertTrue(!result.contains("用量:"))
    }

    @Test
    fun `parseSseResponse handles empty input`() {
        val result = parseSseResponse("", "gpt-5.6")
        assertTrue(result.contains("(空)"))
        assertTrue(result.contains("gpt-5.6"))
    }

    @Test
    fun `parseSseResponse skips non-data lines`() {
        val sse = """
            event: response.completed
            data: {"type":"response.output_text.done","text":"Test reply"}
            data: {"type":"response.completed","response":{"id":"resp_abc","usage":{"input_tokens":"200","output_tokens":"100"}}}
            
            data: [DONE]
        """.trimIndent()

        val result = parseSseResponse(sse, "o4-mini")
        assertTrue(result.contains("Test reply"))
        assertTrue(result.contains("input=200"))
        assertTrue(result.contains("output=100"))
    }

    @Test
    fun `parseSseResponse handles malformed JSON gracefully`() {
        val sse = """
            data: {invalid json}
            data: {"type":"response.output_text.done","text":"Good"}
            data: {"type":"response.completed","response":{"id":"resp_ok","usage":{"input_tokens":"1","output_tokens":"1"}}}
            data: [DONE]
        """.trimIndent()

        val result = parseSseResponse(sse, "gpt-5.6")
        // Malformed line should be skipped, valid ones parsed
        assertTrue(result.contains("Good"))
        assertTrue(result.contains("input=1"))
    }

    @Test
    fun `parseSseResponse contains activation success header`() {
        val sse = """
            data: {"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":"0","output_tokens":"0"}}}
            data: [DONE]
        """.trimIndent()

        val result = parseSseResponse(sse, "gpt-5.6")
        assertTrue(result.contains("✓ 激活成功"))
    }
}