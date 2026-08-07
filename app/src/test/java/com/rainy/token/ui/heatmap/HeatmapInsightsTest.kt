package com.rainy.token.ui.heatmap

import com.rainy.token.data.local.UsageRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [HeatmapViewModel.Companion.computeInsights].
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class HeatmapInsightsTest {

    private fun rec(ts: Long, id: String = "r$ts"): UsageRecord = UsageRecord(
        id = id,
        workspaceId = "w",
        timeCreated = ts,
        timeUpdated = ts,
        model = "m",
        provider = "p",
        inputTokens = 1,
        outputTokens = 0,
        reasoningTokens = 0,
        cacheReadTokens = 0,
        cost = 0,
        keyId = "k",
        sessionId = "s",
    )

    /** 生成指定 UTC 时刻（如 "2024-01-01T10:00:00Z"）的记录 */
    private fun recAt(utc: String, id: String = "r$utc"): UsageRecord =
        rec(Instant.parse(utc).toEpochMilli(), id)

    // ── 总请求次数 ──

    @Test
    fun `empty records yield zero requests and no hours`() {
        val insights = HeatmapViewModel.computeInsights(emptyList())
        assertEquals(0, insights.totalRequests)
        assertEquals(emptyList<Int>(), insights.topHours)
    }

    @Test
    fun `total requests equals record count`() {
        val records = listOf(recAt("2024-01-01T10:00:00Z"), recAt("2024-01-02T11:00:00Z"), recAt("2024-01-03T12:00:00Z"))
        assertEquals(3, HeatmapViewModel.computeInsights(records).totalRequests)
    }

    // ── Top3 时段（降序，并列小时小优先）──

    @Test
    fun `top hours sorted by count descending`() {
        // hour 10 ×2、14 ×3、9 ×5、22 ×1 → [9, 14, 10]
        val records = listOf(
            recAt("2024-01-01T10:00:00Z"), recAt("2024-01-02T10:00:00Z"),
            recAt("2024-01-01T14:00:00Z"), recAt("2024-01-02T14:00:00Z"), recAt("2024-01-03T14:00:00Z"),
            recAt("2024-01-01T09:00:00Z"), recAt("2024-01-02T09:00:00Z"), recAt("2024-01-03T09:00:00Z"), recAt("2024-01-04T09:00:00Z"), recAt("2024-01-05T09:00:00Z"),
            recAt("2024-01-01T22:00:00Z"),
        )
        assertEquals(listOf(9, 14, 10), HeatmapViewModel.computeInsights(records).topHours)
    }

    @Test
    fun `ties broken by smaller hour first`() {
        // hour 5 ×2、7 ×2、3 ×1 → 并列的 5 排在 7 前面 → [5, 7, 3]
        val records = listOf(
            recAt("2024-01-01T05:00:00Z"), recAt("2024-01-02T05:00:00Z"),
            recAt("2024-01-01T07:00:00Z"), recAt("2024-01-02T07:00:00Z"),
            recAt("2024-01-01T03:00:00Z"),
        )
        assertEquals(listOf(5, 7, 3), HeatmapViewModel.computeInsights(records).topHours)
    }

    @Test
    fun `fewer than three hours returns shorter list`() {
        val records = listOf(recAt("2024-01-01T10:00:00Z"), recAt("2024-01-02T10:00:00Z"), recAt("2024-01-03T10:00:00Z"))
        assertEquals(listOf(10), HeatmapViewModel.computeInsights(records).topHours)
    }

    // ── 时区口径（与热力图分桶一致）──

    @Test
    fun `utc8 buckets by utc plus eight hours`() {
        // UTC 16:30 → UTC+8 次日 00:30（hour 0）；UTC 23:59 → UTC+8 07:59（hour 7）
        val records = listOf(
            recAt("2024-01-01T16:30:00Z"),
            recAt("2024-01-01T23:59:00Z"),
        )
        val insights = HeatmapViewModel.computeInsights(records, useUtc8 = true)
        assertEquals(2, insights.totalRequests)
        // 两个时段各 1 次，并列按小时升序
        assertEquals(listOf(0, 7), insights.topHours)
    }

    @Test
    fun `utc mode buckets by utc hour`() {
        val records = listOf(recAt("2024-01-01T16:30:00Z"))
        val insights = HeatmapViewModel.computeInsights(records, useUtc8 = false)
        assertEquals(listOf(16), insights.topHours)
    }
}
