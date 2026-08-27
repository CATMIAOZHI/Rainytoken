package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OpenCodeGoRepository.parseWindows].
 *
 * Uses synthetic HTML snippets that mimic the SolidJS SSR hydration data format.
 * All tests are pure JVM — no Android framework, no network.
 */
class OpenCodeGoRepositoryTest {

    /**
     * Build a minimal hydration HTML with the given window data.
     *
     * The real format looks like:
     *   rollingUsage:$R[0]={status:"ok",usagePercent:42,resetInSec:12345}
     *
     * @param usage 可选：页面新增的用量字段（usage）
     * @param limit 可选：页面新增的限额字段（limit）
     */
    private fun buildHtml(
        rolling: Pair<Int, Long>? = Pair(42, 3600),
        weekly: Pair<Int, Long>? = Pair(15, 86400),
        monthly: Pair<Int, Long>? = Pair(80, 2592000),
        usage: Long? = null,
        limit: Long? = null
    ): String {
        fun windowJs(name: String, data: Pair<Int, Long>?): String {
            if (data == null) return ""
            val extra = if (usage != null && limit != null) ",usage:$usage,limit:$limit" else ""
            return """$name:${'$'}R[0]={status:"ok",usagePercent:${data.first},resetInSec:${data.second}$extra}"""
        }

        return """
        <html>
        <body>
        <script>
        ${windowJs("rollingUsage", rolling)}
        ${windowJs("weeklyUsage", weekly)}
        ${windowJs("monthlyUsage", monthly)}
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    @Test
    fun `parses all three windows correctly`() {
        val html = buildHtml(
            rolling = Pair(42, 3600),
            weekly = Pair(15, 86400),
            monthly = Pair(80, 2592000)
        )
        val result = OpenCodeGoRepository.parseWindows(html)

        assertEquals(3, result.size)
        assertNotNull(result["rollingUsage"])
        assertEquals(42f, result["rollingUsage"]!!.usagePercent)
        assertEquals(3600L, result["rollingUsage"]!!.resetInSec)

        assertNotNull(result["weeklyUsage"])
        assertEquals(15f, result["weeklyUsage"]!!.usagePercent)
        assertEquals(86400L, result["weeklyUsage"]!!.resetInSec)

        assertNotNull(result["monthlyUsage"])
        assertEquals(80f, result["monthlyUsage"]!!.usagePercent)
        assertEquals(2592000L, result["monthlyUsage"]!!.resetInSec)
    }

    @Test
    fun `parses rolling usage only`() {
        val html = buildHtml(weekly = null, monthly = null)
        val result = OpenCodeGoRepository.parseWindows(html)

        assertEquals(1, result.size)
        assertNotNull(result["rollingUsage"])
        assertEquals(42f, result["rollingUsage"]!!.usagePercent)
    }

    @Test
    fun `returns empty map for HTML without hydration data`() {
        val html = "<html><body><h1>Welcome</h1></body></html>"
        val result = OpenCodeGoRepository.parseWindows(html)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty map for empty string`() {
        val result = OpenCodeGoRepository.parseWindows("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles nested objects in hydration data`() {
        val html = """
        <html><body><script>
        rollingUsage:${'$'}R[0]={status:"ok",metadata:{version:2},usagePercent:42,resetInSec:3600}
        </script></body></html>
        """.trimIndent()
        val result = OpenCodeGoRepository.parseWindows(html)

        assertEquals(1, result.size)
        assertNotNull(result["rollingUsage"])
        assertEquals(42f, result["rollingUsage"]!!.usagePercent)
        assertEquals(3600L, result["rollingUsage"]!!.resetInSec)
    }

    @Test
    fun `skips window when usagePercent is missing`() {
        val html = """
        <html><body><script>
        rollingUsage:${'$'}R[0]={status:"ok",resetInSec:3600}
        </script></body></html>
        """.trimIndent()
        val result = OpenCodeGoRepository.parseWindows(html)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips window when resetInSec is missing`() {
        val html = """
        <html><body><script>
        rollingUsage:${'$'}R[0]={status:"ok",usagePercent:42}
        </script></body></html>
        """.trimIndent()
        val result = OpenCodeGoRepository.parseWindows(html)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles zero percent and reset`() {
        val html = buildHtml(
            rolling = Pair(0, 0),
            weekly = null,
            monthly = null
        )
        val result = OpenCodeGoRepository.parseWindows(html)
        assertEquals(1, result.size)
        assertEquals(0f, result["rollingUsage"]!!.usagePercent)
        assertEquals(0L, result["rollingUsage"]!!.resetInSec)
    }

    @Test
    fun `handles large reset values`() {
        val html = buildHtml(
            rolling = Pair(99, 9999999999L),
            weekly = null,
            monthly = null
        )
        val result = OpenCodeGoRepository.parseWindows(html)
        assertEquals(1, result.size)
        assertEquals(99f, result["rollingUsage"]!!.usagePercent)
        assertEquals(9999999999L, result["rollingUsage"]!!.resetInSec)
    }

    @Test
    fun `does not match field without dollar R prefix`() {
        val html = """
        <html><body><script>
        rollingUsage:{usagePercent:42,resetInSec:3600}
        </script></body></html>
        """.trimIndent()
        val result = OpenCodeGoRepository.parseWindows(html)
        // Should not match because the prefix "rollingUsage:$R[" is missing
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parses new page format with decimal percent and usage fields`() {
        // 新页面（2026-08）实测格式：usagePercent 为小数，并新增 usage/limit 字段
        val html = """
        <html><body><script>
        rollingUsage:${'$'}R[37]={status:"ok",resetInSec:1406,usagePercent:1.1,usage:13314666,limit:1200000000}
        weeklyUsage:${'$'}R[38]={status:"ok",resetInSec:339417,usagePercent:2.5,usage:74497156,limit:3000000000}
        monthlyUsage:${'$'}R[39]={status:"ok",resetInSec:1229614,usagePercent:7.2,usage:434167747,limit:6000000000}
        </script></body></html>
        """.trimIndent()
        val result = OpenCodeGoRepository.parseWindows(html)

        assertEquals(3, result.size)

        val rolling = result["rollingUsage"]!!
        assertEquals(1.1f, rolling.usagePercent)
        assertEquals(1406L, rolling.resetInSec)
        assertEquals(13314666L, rolling.usage)
        assertEquals(1200000000L, rolling.limit)

        val weekly = result["weeklyUsage"]!!
        assertEquals(2.5f, weekly.usagePercent)
        assertEquals(339417L, weekly.resetInSec)
        assertEquals(74497156L, weekly.usage)
        assertEquals(3000000000L, weekly.limit)

        val monthly = result["monthlyUsage"]!!
        assertEquals(7.2f, monthly.usagePercent)
        assertEquals(1229614L, monthly.resetInSec)
        assertEquals(434167747L, monthly.usage)
        assertEquals(6000000000L, monthly.limit)
    }

    @Test
    fun `keeps decimal percent without rounding to int`() {
        // 回归保护：usagePercent 为小数时不能被 toIntOrNull 丢掉
        val html = """
        <html><body><script>
        rollingUsage:${'$'}R[0]={status:"ok",resetInSec:60,usagePercent:0.5}
        </script></body></html>
        """.trimIndent()
        val result = OpenCodeGoRepository.parseWindows(html)

        assertEquals(1, result.size)
        assertEquals(0.5f, result["rollingUsage"]!!.usagePercent)
        assertEquals(60L, result["rollingUsage"]!!.resetInSec)
    }

    @Test
    fun `tolerates missing usage and limit fields`() {
        // 旧页面/旧缓存格式：无 usage/limit，不应影响窗口解析
        val html = buildHtml(
            rolling = Pair(42, 3600),
            weekly = null,
            monthly = null
        )
        val result = OpenCodeGoRepository.parseWindows(html)

        assertEquals(1, result.size)
        assertEquals(42f, result["rollingUsage"]!!.usagePercent)
        assertNull(result["rollingUsage"]!!.usage)
        assertNull(result["rollingUsage"]!!.limit)
    }
}