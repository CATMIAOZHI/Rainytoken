package com.rainy.token.ui.dashboard

import com.rainy.token.domain.service.ServiceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dashboardItemVisible] 纯逻辑测试：隐藏的服务商连同其专属用量卡 / 热力图入口一起隐藏。
 */
class DashboardItemVisibleTest {

    @Test
    fun `service card hides with its provider`() {
        val hidden = setOf(ServiceType.DEEPSEEK)
        assertFalse(dashboardItemVisible("service:deepseek", hidden))
        assertTrue(dashboardItemVisible("service:claude", hidden))
    }

    @Test
    fun `unknown service key stays visible`() {
        // fromStorageKey 返回 null 时 !in hidden 为 true，卡片保持可见
        assertTrue(dashboardItemVisible("service:not_a_service", setOf(ServiceType.DEEPSEEK)))
    }

    @Test
    fun `ocgo usage and heatmap hide together`() {
        val hidden = setOf(ServiceType.OPENCODE_GO)
        assertFalse(dashboardItemVisible("usage:opencode_go", hidden))
        assertFalse(dashboardItemVisible("heatmap", hidden))
        assertTrue(dashboardItemVisible("usage:commandcode_go", hidden))
    }

    @Test
    fun `ccgo usage hides with commandcode`() {
        val hidden = setOf(ServiceType.COMMANDCODE_GO)
        assertFalse(dashboardItemVisible("usage:commandcode_go", hidden))
        assertTrue(dashboardItemVisible("usage:opencode_go", hidden))
    }

    @Test
    fun `unknown item id always visible`() {
        assertTrue(dashboardItemVisible("some_future_card", ServiceType.entries.toSet()))
    }

    @Test
    fun `nothing hidden shows everything`() {
        val ids = listOf(
            "service:opencode_go", "service:commandcode_go", "service:deepseek",
            "service:codex", "service:claude", "service:ollama",
            "usage:opencode_go", "usage:commandcode_go", "heatmap"
        )
        assertTrue(ids.all { dashboardItemVisible(it, emptySet()) })
    }
}