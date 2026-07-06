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
     */
    private fun buildHtml(
        rolling: Pair<Int, Long>? = Pair(42, 3600),
        weekly: Pair<Int, Long>? = Pair(15, 86400),
        monthly: Pair<Int, Long>? = Pair(80, 2592000)
    ): String {
        fun windowJs(name: String, data: Pair<Int, Long>?): String {
            if (data == null) return ""
            return """$name:${'$'}R[0]={status:"ok",usagePercent:${data.first},resetInSec:${data.second}}"""
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
        assertEquals(42, result["rollingUsage"]!!.usagePercent)
        assertEquals(3600L, result["rollingUsage"]!!.resetInSec)

        assertNotNull(result["weeklyUsage"])
        assertEquals(15, result["weeklyUsage"]!!.usagePercent)
        assertEquals(86400L, result["weeklyUsage"]!!.resetInSec)

        assertNotNull(result["monthlyUsage"])
        assertEquals(80, result["monthlyUsage"]!!.usagePercent)
        assertEquals(2592000L, result["monthlyUsage"]!!.resetInSec)
    }

    @Test
    fun `parses rolling usage only`() {
        val html = buildHtml(weekly = null, monthly = null)
        val result = OpenCodeGoRepository.parseWindows(html)

        assertEquals(1, result.size)
        assertNotNull(result["rollingUsage"])
        assertEquals(42, result["rollingUsage"]!!.usagePercent)
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
        assertEquals(42, result["rollingUsage"]!!.usagePercent)
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
        assertEquals(0, result["rollingUsage"]!!.usagePercent)
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
        assertEquals(99, result["rollingUsage"]!!.usagePercent)
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
}