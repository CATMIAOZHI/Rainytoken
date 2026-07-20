package com.rainy.token.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [formatAmount], [formatResetInSec], [formatResetForWidget], [normalizeWindowLabel].
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class FormatUtilsTest {

    // ── formatAmount ──

    @Test
    fun `formatAmount integer has no decimal point`() {
        assertEquals("5", formatAmount(5.0))
        assertEquals("0", formatAmount(0.0))
        assertEquals("100", formatAmount(100.0))
    }

    @Test
    fun `formatAmount decimal keeps 2 places`() {
        assertEquals("5.20", formatAmount(5.20))
        assertEquals("0.50", formatAmount(0.5))
        assertEquals("99.99", formatAmount(99.99))
    }

    @Test
    fun `formatAmount rounds to 2 places`() {
        assertEquals("1.23", formatAmount(1.234))
        assertEquals("1.24", formatAmount(1.235))
    }

    @Test
    fun `formatAmount large integer`() {
        assertEquals("10000", formatAmount(10000.0))
    }

    @Test
    fun `formatAmount negative integer`() {
        assertEquals("-5", formatAmount(-5.0))
    }

    @Test
    fun `formatAmount negative decimal`() {
        assertEquals("-5.20", formatAmount(-5.2))
    }

    // ── formatResetInSec ──

    @Test
    fun `formatResetInSec zero returns dash`() {
        assertEquals("—", formatResetInSec(0))
    }

    @Test
    fun `formatResetInSec negative returns dash`() {
        assertEquals("—", formatResetInSec(-1))
        assertEquals("—", formatResetInSec(-100))
    }

    @Test
    fun `formatResetInSec minutes only`() {
        assertEquals("5 分", formatResetInSec(300))
        assertEquals("1 分", formatResetInSec(60))
        assertEquals("59 分", formatResetInSec(59 * 60))
    }

    @Test
    fun `formatResetInSec hours and minutes`() {
        assertEquals("1 小时 0 分", formatResetInSec(3600))
        assertEquals("1 小时 30 分", formatResetInSec(3600 + 1800))
        assertEquals("23 小时 59 分", formatResetInSec(86399))
    }

    @Test
    fun `formatResetInSec days and hours`() {
        assertEquals("1 天 0 小时", formatResetInSec(86400))
        assertEquals("1 天 1 小时", formatResetInSec(86400 + 3600))
        assertEquals("7 天 12 小时", formatResetInSec(7 * 86400 + 12 * 3600))
    }

    @Test
    fun `formatResetInSec large value`() {
        assertEquals("30 天 0 小时", formatResetInSec(30 * 86400L))
    }

    // ── formatResetForWidget ──

    @Test
    fun `formatResetForWidget zero returns empty string`() {
        assertEquals("", formatResetForWidget(0))
    }

    @Test
    fun `formatResetForWidget negative returns empty string`() {
        assertEquals("", formatResetForWidget(-1))
    }

    @Test
    fun `formatResetForWidget same logic as formatResetInSec for positive values`() {
        assertEquals("5 分", formatResetForWidget(300))
        assertEquals("1 小时 0 分", formatResetForWidget(3600))
        assertEquals("1 天 0 小时", formatResetForWidget(86400))
    }

    // ── normalizeWindowLabel ──

    @Test
    fun `normalizeWindowLabel weekly to Chinese`() {
        assertEquals("每周", normalizeWindowLabel("weekly"))
        assertEquals("每周", normalizeWindowLabel("Weekly"))
        assertEquals("每周", normalizeWindowLabel("WEEKLY"))
    }

    @Test
    fun `normalizeWindowLabel non-weekly passes through`() {
        assertEquals("5h", normalizeWindowLabel("5h"))
        assertEquals("monthly", normalizeWindowLabel("monthly"))
        assertEquals("30d", normalizeWindowLabel("30d"))
    }

    @Test
    fun `normalizeWindowLabel empty string`() {
        assertEquals("", normalizeWindowLabel(""))
    }
}