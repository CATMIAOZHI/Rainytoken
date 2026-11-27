package com.rainy.token.ui.heatmap

import com.rainy.token.data.local.UsageRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [HeatmapViewModel.Companion.computeTotalRequests].
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class HeatmapInsightsTest {

    private fun rec(requests: Long = 1L): UsageRecord = UsageRecord(
        id = "row",
        workspaceId = "w",
        timeCreated = 0L,
        timeUpdated = 0L,
        model = "m",
        provider = "p",
        inputTokens = 1,
        outputTokens = 0,
        reasoningTokens = 0,
        cacheReadTokens = 0,
        cost = 0.0,
        keyId = "k",
        sessionId = "s",
        requestCount = requests,
    )

    @Test
    fun `empty cache has no synced requests`() {
        assertEquals(0L, HeatmapViewModel.computeTotalRequests(emptyList()))
    }

    @Test
    fun `request count sums daily rows rather than counting them`() {
        assertEquals(124L, HeatmapViewModel.computeTotalRequests(listOf(rec(120), rec(3), rec())))
    }

    @Test
    fun `request count can exceed Int limit`() {
        assertEquals(3_000_000_007L, HeatmapViewModel.computeTotalRequests(listOf(rec(3_000_000_000L), rec(7))))
    }
}
