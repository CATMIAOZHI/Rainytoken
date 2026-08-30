package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CommandCodeGoRepository] 的计划 → 月度额度映射。
 *
 * 2026-08 官网更新了计划体系（GOAT 等新计划），旧映射缺少
 * individual-goat 会导致 GOAT 用户月度总量/已用计算缺失。
 */
class CommandCodeGoPlanMappingTest {

    @Test
    fun `goat plan maps to 70 usd monthly quota`() {
        assertEquals(70.0, CommandCodeGoRepository.planMonthlyQuota("individual-goat")!!, 0.001)
    }

    @Test
    fun `go plan maps to 10 usd monthly quota`() {
        assertEquals(10.0, CommandCodeGoRepository.planMonthlyQuota("individual-go")!!, 0.001)
    }

    @Test
    fun `pro plan maps to 80 usd monthly quota`() {
        assertEquals(80.0, CommandCodeGoRepository.planMonthlyQuota("individual-pro")!!, 0.001)
    }

    @Test
    fun `max plans map to 150 and 300 usd monthly quota`() {
        assertEquals(150.0, CommandCodeGoRepository.planMonthlyQuota("individual-max")!!, 0.001)
        assertEquals(300.0, CommandCodeGoRepository.planMonthlyQuota("individual-ultra")!!, 0.001)
    }

    @Test
    fun `unknown plan returns null quota`() {
        assertNull(CommandCodeGoRepository.planMonthlyQuota("individual-unknown"))
        assertNull(CommandCodeGoRepository.planMonthlyQuota(null))
    }

    @Test
    fun `plan id lookup is case insensitive`() {
        assertEquals(70.0, CommandCodeGoRepository.planMonthlyQuota("INDIVIDUAL-GOAT")!!, 0.001)
        assertEquals("GOAT", CommandCodeGoRepository.planDisplayName("Individual-Goat"))
    }

    @Test
    fun `unknown plan display name falls back to raw id`() {
        assertEquals("mystery-plan", CommandCodeGoRepository.planDisplayName("mystery-plan"))
        assertEquals("", CommandCodeGoRepository.planDisplayName(null))
    }
}