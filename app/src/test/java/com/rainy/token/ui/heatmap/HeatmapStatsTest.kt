package com.rainy.token.ui.heatmap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [HeatmapViewModel.Companion.computeStats].
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class HeatmapStatsTest {

    private fun days(vararg tokens: Long): List<HeatmapDayData> =
        tokens.mapIndexed { i, t -> HeatmapDayData(dayTs = i.toLong(), tokens = t, level = 0) }

    // ── 累计 token ──

    @Test
    fun `total sums all days`() {
        assertEquals(17L, HeatmapViewModel.computeStats(days(0, 5, 0, 3, 7, 0, 2)).totalTokens)
        assertEquals(0L, HeatmapViewModel.computeStats(days(0, 0, 0)).totalTokens)
        assertEquals(0L, HeatmapViewModel.computeStats(emptyList()).totalTokens)
    }

    // ── 峰值 token ──

    @Test
    fun `peak is max single day`() {
        assertEquals(7L, HeatmapViewModel.computeStats(days(0, 5, 0, 3, 7, 0, 2)).peakTokens)
        assertEquals(0L, HeatmapViewModel.computeStats(days(0, 0, 0)).peakTokens)
        assertEquals(100L, HeatmapViewModel.computeStats(days(100)).peakTokens)
    }

    // ── 当前连续天数 ──

    @Test
    fun `current streak counts from last day backward`() {
        // 末尾是 2（>0），往前数只有它自己（前一个 0 断档）→ 1
        assertEquals(1, HeatmapViewModel.computeStats(days(0, 5, 0, 3, 7, 0, 2)).currentStreak)
        // 末尾断档 → 0
        assertEquals(0, HeatmapViewModel.computeStats(days(5, 3, 0)).currentStreak)
        // 全零 → 0
        assertEquals(0, HeatmapViewModel.computeStats(days(0, 0, 0)).currentStreak)
        // 全连续 → 全部天数
        assertEquals(3, HeatmapViewModel.computeStats(days(2, 3, 4)).currentStreak)
        // 单天 → 1
        assertEquals(1, HeatmapViewModel.computeStats(days(100)).currentStreak)
        // 空列表 → 0
        assertEquals(0, HeatmapViewModel.computeStats(emptyList()).currentStreak)
    }

    // ── 最长连续天数 ──

    @Test
    fun `max streak is longest run`() {
        // 最长连续段是 (3,7) 共 2 天
        assertEquals(2, HeatmapViewModel.computeStats(days(0, 5, 0, 3, 7, 0, 2)).maxStreak)
        assertEquals(2, HeatmapViewModel.computeStats(days(5, 3, 0)).maxStreak)
        assertEquals(0, HeatmapViewModel.computeStats(days(0, 0, 0)).maxStreak)
        assertEquals(3, HeatmapViewModel.computeStats(days(2, 3, 4)).maxStreak)
        assertEquals(1, HeatmapViewModel.computeStats(days(100)).maxStreak)
        assertEquals(0, HeatmapViewModel.computeStats(emptyList()).maxStreak)
    }
}
