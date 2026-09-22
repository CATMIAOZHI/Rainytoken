package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for [OpenCodeGoRepository.parseUsageResponse] / [OpenCodeGoRepository.parseModelRows] /
 * [OpenCodeGoRepository.windowQuotaRaw].
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class OpenCodeGoRepositoryTest {

    /** 固定"现在"，保证倒计时断言稳定。 */
    private val now = 1_700_000_000_000L

    /** 构造 /zen/go/v1/usage 响应；参数为 null 时该窗口不出现。 */
    private fun usageJson(
        rolling: String? = """{"status":"ok","percent":42,"resetsAt":"${Instant.ofEpochMilli(now + 3_600_000)}"}""",
        weekly: String? = """{"status":"ok","percent":15,"resetsAt":"${Instant.ofEpochMilli(now + 86_400_000)}"}""",
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
        assertEquals(3600L, rolling.resetInSec)
        assertEquals(now + 3_600_000L, rolling.resetAtMillis)

        assertEquals(15f, result["weeklyUsage"]!!.usagePercent)
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