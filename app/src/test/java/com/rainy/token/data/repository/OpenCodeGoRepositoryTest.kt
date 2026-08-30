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

    // ─────────────── parseModelRows（_server 模型级用量）───────────────

    /** 真实 response 形态的 flight 片段：monthly 窗口、6 个模型。 */
    private fun monthlyFlight(): String =
        """
        ;0x000003bc;((self.${'$'}R=self.${'$'}R||{})["server-fn:2"]=[],(${'$'}R=>${'$'}R[0]={usage:1889462439,limit:6000000000,usagePercent:31.5,rows:${'$'}R[1]=[${'$'}R[2]={model:"deepseek-v4-flash",name:"DeepSeek V4 Flash",cost:1002607535,quotaCost:2005215070,multiplier:2,estimated:!0,contributionPercent:30.5},${'$'}R[3]={model:"muse-spark-1.2-contributor",name:"Muse Spark 1.2 Contributor",cost:52650887,quotaCost:52650887,multiplier:1,estimated:!1,contributionPercent:0.8},${'$'}R[4]={model:"deepseek-v4-flash-vision-exp",name:"DeepSeek V4 Flash Vision Exp",cost:3721059,quotaCost:14884236,multiplier:4,estimated:!0,contributionPercent:0.2},${'$'}R[5]={model:"mimo-v2.5",name:"MiMo V2.5",cost:247184,quotaCost:247184,multiplier:1,estimated:!0,contributionPercent:0},${'$'}R[6]={model:"minimax-m3",name:"MiniMax M3",cost:11310,quotaCost:11310,multiplier:1,estimated:!1,contributionPercent:0},${'$'}R[7]={model:"hy3",name:"Hy3",cost:2676,quotaCost:335,multiplier:0.125,estimated:!1,contributionPercent:0}]})(${'$'}R["server-fn:2"]))
        """.trimIndent()

    @Test
    fun `parses monthly model rows with all fields`() {
        val result = OpenCodeGoRepository.parseModelRows(monthlyFlight())
        assertNotNull(result)
        val usage = result!!
        assertEquals(1889462439L, usage.usage)
        assertEquals(6000000000L, usage.limit)
        assertEquals(31.5f, usage.usagePercent)
        assertEquals(6, usage.rows.size)

        val deepseek = usage.rows[0]
        assertEquals("deepseek-v4-flash", deepseek.model)
        assertEquals("DeepSeek V4 Flash", deepseek.name)
        assertEquals(1002607535L, deepseek.cost)
        assertEquals(2005215070L, deepseek.quotaCost)
        assertEquals(2.0, deepseek.multiplier, 0.0)
        assertTrue(deepseek.estimated)
        assertEquals(30.5, deepseek.contributionPercent, 0.0)

        val muse = usage.rows[1]
        assertEquals("muse-spark-1.2-contributor", muse.model)
        assertEquals(52650887L, muse.cost)
        assertEquals(1.0, muse.multiplier, 0.0)
        assertTrue(!muse.estimated)
        assertEquals(0.8, muse.contributionPercent, 0.0)
    }

    @Test
    fun `parses fractional multiplier`() {
        // hy3 multiplier:0.125
        val usage = OpenCodeGoRepository.parseModelRows(monthlyFlight())!!
        val hy3 = usage.rows.first { it.model == "hy3" }
        assertEquals(0.125, hy3.multiplier, 0.0)
        assertEquals(335L, hy3.quotaCost)
    }

    @Test
    fun `parses rolling window with single model`() {
        val flight = """
            ;0x0000011b;((self.${'$'}R=self.${'$'}R||{})["server-fn:2"]=[],(${'$'}R=>${'$'}R[0]={usage:331489076,limit:1200000000,usagePercent:27.6,rows:${'$'}R[1]=[${'$'}R[2]={model:"deepseek-v4-flash",name:"DeepSeek V4 Flash",cost:165744538,quotaCost:331489076,multiplier:2,estimated:!1,contributionPercent:27.6}]})(${'$'}R["server-fn:2"]))
        """.trimIndent()
        val usage = OpenCodeGoRepository.parseModelRows(flight)!!
        assertEquals(1, usage.rows.size)
        assertEquals("deepseek-v4-flash", usage.rows[0].model)
        assertEquals(331489076L, usage.rows[0].quotaCost)
        assertTrue(!usage.rows[0].estimated)
    }

    @Test
    fun `returns null for non-flight input`() {
        assertNull(OpenCodeGoRepository.parseModelRows(""))
        assertNull(OpenCodeGoRepository.parseModelRows("<html>no data</html>"))
        assertNull(OpenCodeGoRepository.parseModelRows(";0x00000001;{}"))
    }

    @Test
    fun `handles missing rows gracefully`() {
        val flight =
            """
            ;0x000003bc;((self.${'$'}R=self.${'$'}R||{})["server-fn:2"]=[],(${'$'}R=>${'$'}R[0]={usage:100,limit:200,usagePercent:50,rows:${'$'}R[1]=[]})(${'$'}R["server-fn:2"]))
            """.trimIndent()
        val usage = OpenCodeGoRepository.parseModelRows(flight)!!
        assertTrue(usage.rows.isEmpty())
        assertEquals(100L, usage.usage)
        assertEquals(50f, usage.usagePercent)
    }

    // ─────────────── windowQuotaRaw（窗口配额，网页实测口径）───────────────

    @Test
    fun `window quota for deepseek matches web across all windows`() {
        // 实测：DeepSeek multiplier=2，5h=12 亿→$6、周=30 亿→$15、月=60 亿→$30（与网页表格一致）
        assertEquals(600_000_000L, OpenCodeGoRepository.windowQuotaRaw(1_200_000_000L, 2.0))
        assertEquals(1_500_000_000L, OpenCodeGoRepository.windowQuotaRaw(3_000_000_000L, 2.0))
        assertEquals(3_000_000_000L, OpenCodeGoRepository.windowQuotaRaw(6_000_000_000L, 2.0))
    }

    @Test
    fun `window quota for hy3 fractional multiplier`() {
        // 实测：Hy3 multiplier=0.125，月 limit=60 亿→$480（与网页表格一致）
        assertEquals(48_000_000_000L, OpenCodeGoRepository.windowQuotaRaw(6_000_000_000L, 0.125))
    }

    @Test
    fun `window quota falls back to 60 usd on invalid multiplier`() {
        assertEquals(6_000_000_000L, OpenCodeGoRepository.windowQuotaRaw(3_000_000_000L, 0.0))
        assertEquals(6_000_000_000L, OpenCodeGoRepository.windowQuotaRaw(3_000_000_000L, -1.0))
    }

    @Test
    fun `window quota with multiplier one equals window limit`() {
        // Muse Spark multiplier=1：配额 = 窗口 limit 本身
        assertEquals(1_200_000_000L, OpenCodeGoRepository.windowQuotaRaw(1_200_000_000L, 1.0))
        assertEquals(6_000_000_000L, OpenCodeGoRepository.windowQuotaRaw(6_000_000_000L, 1.0))
    }
}