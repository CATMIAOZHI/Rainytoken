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
    fun `7d maps to weekly Chinese`() {
        assertEquals("每周", formatCodexPrimaryLabel("7d"))
    }

    @Test
    fun `weekly Chinese stays`() {
        assertEquals("每周", formatCodexPrimaryLabel("每周"))
    }

    @Test
    fun `30d maps to monthly Chinese`() {
        assertEquals("每月", formatCodexPrimaryLabel("30d"))
    }

    @Test
    fun `monthly Chinese stays`() {
        assertEquals("每月", formatCodexPrimaryLabel("每月"))
    }

    @Test
    fun `usage maps to Chinese`() {
        assertEquals("用量", formatCodexPrimaryLabel("usage"))
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
        assertEquals("每周", formatCodexPrimaryLabel("7D"))
        assertEquals("每月", formatCodexPrimaryLabel("30D"))
    }
}