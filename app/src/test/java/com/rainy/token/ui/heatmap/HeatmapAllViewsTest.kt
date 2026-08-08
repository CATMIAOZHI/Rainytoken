package com.rainy.token.ui.heatmap

import com.rainy.token.data.local.UsageRecord
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [HeatmapViewModel.Companion.computeAllViews]（最近 365 天窗口与年份模式）。
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class HeatmapAllViewsTest {

    private fun rec(ts: Long, tokens: Long = 100L): UsageRecord = UsageRecord(
        id = "r$ts",
        workspaceId = "w",
        timeCreated = ts,
        timeUpdated = ts,
        model = "m",
        provider = "p",
        inputTokens = tokens,
        outputTokens = 0,
        reasoningTokens = 0,
        cacheReadTokens = 0,
        cost = 0,
        keyId = "k",
        sessionId = "s",
    )

    private fun dayTsOf(ts: Long, useUtc8: Boolean): Long {
        val zone = if (useUtc8) ZoneOffset.ofHours(8) else ZoneOffset.UTC
        return Instant.ofEpochMilli(ts).atOffset(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun startOfWeekTs(ts: Long): Long {
        val zone = ZoneOffset.UTC
        val dow = Instant.ofEpochMilli(ts).atOffset(zone).dayOfWeek.value % 7 // Sunday=7 → 0
        return ts - dow * 86_400_000L
    }

    // ── 最近 365 天窗口 ──

    @Test
    fun `recent window covers 365 days ending today`() {
        val now = System.currentTimeMillis()
        val today = dayTsOf(now, useUtc8 = false)
        // 窗口内：今天、昨天；窗口外：366 天前（不应计入）
        val records = listOf(
            rec(today, 10),
            rec(today - 86_400_000L, 20),
            rec(today - 366L * 86_400_000L, 999),
        )
        val result = HeatmapViewModel.computeAllViews(records, useUtc8 = false, year = RECENT_YEAR)

        assertEquals(365, result.dailyData.size)
        assertEquals(today - 364L * 86_400_000L, result.dailyData.first().dayTs)
        assertEquals(today, result.dailyData.last().dayTs)
        // 窗口外记录不计入：总 token = 10 + 20
        assertEquals(30L, result.dailyData.sumOf { it.tokens })
    }

    @Test
    fun `recent cumulative ends at window total`() {
        val now = System.currentTimeMillis()
        val today = dayTsOf(now, useUtc8 = false)
        val records = listOf(
            rec(today, 10),
            rec(today - 86_400_000L, 20),
        )
        val result = HeatmapViewModel.computeAllViews(records, useUtc8 = false, year = RECENT_YEAR)

        assertEquals(30L, result.cumulativeData.last().tokens)
        assertEquals(365, result.cumulativeData.size)
        // 累计视图首日 = 窗口首日（自身 token，非 0）
        assertEquals(0L, result.cumulativeData.first().tokens)
    }

    @Test
    fun `recent week view aligned to window weeks`() {
        val now = System.currentTimeMillis()
        val today = dayTsOf(now, useUtc8 = false)
        val firstWeekStart = startOfWeekTs(today - 364L * 86_400_000L)
        val lastWeekStart = startOfWeekTs(today)
        val expectedWeeks = ((lastWeekStart - firstWeekStart) / 86_400_000L).toInt() / 7 + 1

        val result = HeatmapViewModel.computeAllViews(emptyList(), useUtc8 = false, year = RECENT_YEAR)

        assertEquals(expectedWeeks, result.weeklyData.size)
        assertEquals(firstWeekStart, result.weeklyData.first().weekStartTs)
        assertEquals(lastWeekStart, result.weeklyData.last().weekStartTs)
    }

    // ── 年份模式回归 ──

    @Test
    fun `year mode unaffected by recent window`() {
        val rec2024 = rec(Instant.parse("2024-06-15T00:00:00Z").toEpochMilli(), 5)
        val rec2025 = rec(Instant.parse("2025-06-15T00:00:00Z").toEpochMilli(), 7)

        val result = HeatmapViewModel.computeAllViews(listOf(rec2024, rec2025), useUtc8 = false, year = 2024)

        assertEquals(366, result.dailyData.size) // 2024 闰年完整自然年
        assertEquals(5L, result.dailyData.sumOf { it.tokens }) // 2025 年记录不计入
        assertEquals(5L, result.cumulativeData.last().tokens)
    }
}