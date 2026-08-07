package com.rainy.token.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [formatCodexPrimaryLabel].
 *
 * Tests the Codex primary window label translation logic,
 * including null/unknown fallback behavior.
 */
class FormatCodexPrimaryLabelTest {

    @Test
    fun `5h stays as 5h`() {
        assertEquals("5h", formatCodexPrimaryLabel("5h"))
    }

    @Test
    fun `7d maps to weekly label`() {
        assertEquals("Weekly", formatCodexPrimaryLabel("7d"))
        assertEquals("每周", formatCodexPrimaryLabel("7d", weeklyLabel = "每周"))
        // 中性标识（durationLabel 输出）
        assertEquals("Weekly", formatCodexPrimaryLabel("weekly"))
        assertEquals("每周", formatCodexPrimaryLabel("weekly", weeklyLabel = "每周"))
    }

    @Test
    fun `weekly Chinese stays`() {
        assertEquals("每周", formatCodexPrimaryLabel("每周", weeklyLabel = "每周"))
        assertEquals("Weekly", formatCodexPrimaryLabel("每周"))
    }

    @Test
    fun `30d maps to monthly label`() {
        assertEquals("Monthly", formatCodexPrimaryLabel("30d"))
        assertEquals("每月", formatCodexPrimaryLabel("30d", monthlyLabel = "每月"))
        // 中性标识（durationLabel 输出）
        assertEquals("Monthly", formatCodexPrimaryLabel("monthly"))
        assertEquals("每月", formatCodexPrimaryLabel("monthly", monthlyLabel = "每月"))
    }

    @Test
    fun `monthly Chinese stays`() {
        assertEquals("每月", formatCodexPrimaryLabel("每月", monthlyLabel = "每月"))
        assertEquals("Monthly", formatCodexPrimaryLabel("每月"))
    }

    @Test
    fun `usage maps to usage label`() {
        assertEquals("Usage", formatCodexPrimaryLabel("usage"))
        assertEquals("用量", formatCodexPrimaryLabel("usage", usageLabel = "用量"))
    }

    @Test
    fun `null defaults to 5h`() {
        assertEquals("5h", formatCodexPrimaryLabel(null))
    }

    @Test
    fun `unknown label passes through`() {
        assertEquals("custom", formatCodexPrimaryLabel("custom"))
        assertEquals("15d", formatCodexPrimaryLabel("15d"))
    }

    @Test
    fun `case insensitive`() {
        assertEquals("5h", formatCodexPrimaryLabel("5H"))
        assertEquals("Weekly", formatCodexPrimaryLabel("7D"))
        assertEquals("Monthly", formatCodexPrimaryLabel("30D"))
    }
}