package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** JVM fixtures for the documented Go quota API. No Android dependencies or live requests. */
class OpenCodeGoRepositoryTest {

    /** 固定"现在"，保证倒计时断言稳定。 */
    private val now = 1_700_000_000_000L

    /** 构造 /inference/go/v1/usage 响应；参数为 null 时该窗口不出现。 */
    private fun usageJson(
        rolling: String? = """{"status":"ok","percent":42,"resetsAt":"${Instant.ofEpochMilli(now + 3_600_000)}"}""",
        weekly: String? = """{"status":"rate-limited","percent":100,"resetsAt":"${Instant.ofEpochMilli(now + 86_400_000)}"}""",
        monthly: String? = """{"status":"ok","percent":80,"resetsAt":"${Instant.ofEpochMilli(now + 2_592_000_000)}"}"""
    ): String {
        val parts = listOfNotNull(
            rolling?.let { """"rolling":$it""" },
            weekly?.let { """"weekly":$it""" },
            monthly?.let { """"monthly":$it""" }
        )
        return """{"usage":{${parts.joinToString(",")}}}"""
    }

    // ─────────────── parseUsageResponse（余额 API JSON）───────────────

    @Test
    fun `parses all three windows from usage api`() {
        val result = OpenCodeGoRepository.parseUsageResponse(usageJson(), now)

        assertEquals(3, result.size)
        val rolling = result["rollingUsage"]!!
        assertEquals(42f, rolling.usagePercent)
        assertEquals(UsageWindowStatus.OK, rolling.status)
        assertEquals(3600L, rolling.resetInSec)
        assertEquals(now + 3_600_000L, rolling.resetAtMillis)

        assertEquals(100f, result["weeklyUsage"]!!.usagePercent)
        assertEquals(UsageWindowStatus.RATE_LIMITED, result["weeklyUsage"]!!.status)
        assertEquals(86_400L, result["weeklyUsage"]!!.resetInSec)
        assertEquals(80f, result["monthlyUsage"]!!.usagePercent)
        assertEquals(2_592_000L, result["monthlyUsage"]!!.resetInSec)
    }

    @Test
    fun `parses rolling window only`() {
        val result = OpenCodeGoRepository.parseUsageResponse(usageJson(weekly = null, monthly = null), now)
        assertEquals(1, result.size)
        assertNotNull(result["rollingUsage"])
        assertNull(result["weeklyUsage"])
    }

    @Test
    fun `keeps window when resetsAt is missing`() {
        // resetsAt 可选：窗口仍展示百分比，只是没有倒计时
        val json = """{"usage":{"rolling":{"status":"ok","percent":42}}}"""
        val result = OpenCodeGoRepository.parseUsageResponse(json, now)
        assertEquals(1, result.size)
        val rolling = result["rollingUsage"]!!
        assertEquals(42f, rolling.usagePercent)
        assertNull(rolling.resetInSec)
        assertNull(rolling.resetAtMillis)
    }

    @Test
    fun `skips window when percent is missing or invalid`() {
        val json = """{"usage":{"rolling":{"status":"ok","resetsAt":"2026-08-12T20:00:00Z"},"weekly":{"percent":"abc"}}}"""
        val result = OpenCodeGoRepository.parseUsageResponse(json, now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parses epoch seconds and epoch millis resetsAt`() {
        val seconds = (now + 3_600_000L) / 1000
        val viaSeconds = OpenCodeGoRepository.parseUsageResponse(
            """{"usage":{"rolling":{"percent":10,"resetsAt":$seconds}}}""", now
        )
        assertEquals(3600L, viaSeconds["rollingUsage"]!!.resetInSec)

        val viaMillis = OpenCodeGoRepository.parseUsageResponse(
            """{"usage":{"rolling":{"percent":10,"resetsAt":${now + 3_600_000L}}}}""", now
        )
        assertEquals(3600L, viaMillis["rollingUsage"]!!.resetInSec)
    }

    @Test
    fun `clamps percent into valid range`() {
        val over = OpenCodeGoRepository.parseUsageResponse(
            """{"usage":{"rolling":{"percent":150}}}""", now
        )
        assertEquals(100f, over["rollingUsage"]!!.usagePercent)

        val under = OpenCodeGoRepository.parseUsageResponse(
            """{"usage":{"rolling":{"percent":-5}}}""", now
        )
        assertEquals(0f, under["rollingUsage"]!!.usagePercent)
    }

    @Test
    fun `keeps decimal percent without rounding to int`() {
        val result = OpenCodeGoRepository.parseUsageResponse(
            """{"usage":{"rolling":{"percent":1.1,"resetsAt":"${Instant.ofEpochMilli(now + 60_000)}"}}}""", now
        )
        assertEquals(1.1f, result["rollingUsage"]!!.usagePercent)
        assertEquals(60L, result["rollingUsage"]!!.resetInSec)
    }

    @Test
    fun `coerces past reset time to zero`() {
        val result = OpenCodeGoRepository.parseUsageResponse(
            """{"usage":{"rolling":{"percent":5,"resetsAt":"${Instant.ofEpochMilli(now - 60_000)}"}}}""", now
        )
        assertEquals(0L, result["rollingUsage"]!!.resetInSec)
    }

    @Test
    fun `returns empty map for empty or invalid json`() {
        assertTrue(OpenCodeGoRepository.parseUsageResponse("", now).isEmpty())
        assertTrue(OpenCodeGoRepository.parseUsageResponse("<html>login</html>", now).isEmpty())
        assertTrue(OpenCodeGoRepository.parseUsageResponse("""{"foo":1}""", now).isEmpty())
    }


    @Test
    fun `determines rate limit from status and not rounded percent`() {
        val result = OpenCodeGoRepository.parseUsageResponse(
            usageJson(
                rolling = """{"status":"rate-limited","percent":99}""",
                weekly = """{"status":"ok","percent":100}""",
                monthly = """{"status":"new-value","percent":70}"""
            ),
            now
        )
        assertEquals(UsageWindowStatus.RATE_LIMITED, result["rollingUsage"]!!.status)
        assertEquals(99f, result["rollingUsage"]!!.usagePercent)
        assertEquals(UsageWindowStatus.OK, result["weeklyUsage"]!!.status)
        assertNull(result["monthlyUsage"]!!.status)
        assertEquals(70f, result["monthlyUsage"]!!.usagePercent)
    }

    @Test
    fun `uses documented quota endpoint with bearer header and no cookie`() {
        val request = OpenCodeGoRepository.usageRequest("fake-fixture-key")
        assertEquals("GET", request.method)
        assertEquals("https://opencode.ai/inference/go/v1/usage", request.url.toString())
        assertEquals("Bearer fake-fixture-key", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.header("Cookie"))
        assertNull(request.body)
    }

    @Test
    fun `preserves quota HTTP error meanings`() {
        assertTrue(OpenCodeGoRepository.usageHttpError(401, null) is RepositoryError.InvalidCredential)
        assertTrue(OpenCodeGoRepository.usageHttpError(403, null) is RepositoryError.OpenCodeGoSubscriptionRequired)
        val serverError = OpenCodeGoRepository.usageHttpError(503, null) as RepositoryError.ServerError
        assertEquals(503, serverError.code)
    }

    @Test
    fun `parses retry-after delay seconds and HTTP date`() {
        val instant = Instant.parse("2026-11-21T15:00:00.500Z")
        fun retryAfter(header: String?): Long? =
            (OpenCodeGoRepository.usageHttpError(429, header, instant) as RepositoryError.RateLimited).retryAfterSeconds

        assertEquals(120L, retryAfter("120"))
        assertEquals(60L, retryAfter("Sat, 21 Nov 2026 15:01:00 GMT"))
        assertEquals(0L, retryAfter("Sat, 21 Nov 2026 15:00:00 GMT"))
        assertNull(retryAfter(null))
        assertNull(retryAfter("-1"))
        assertNull(retryAfter("invalid"))
    }
}
