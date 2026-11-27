package com.rainy.token.data.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStorageTest {
    @Test
    fun `legacy DataStore converts raw cost while preserving cached fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val persisted = """
            [{
                "id":"legacy-id","workspaceId":"commandcode","timeCreated":123,"timeUpdated":456,
                "model":"model","provider":"provider","inputTokens":20,"outputTokens":8,
                "reasoningTokens":2,"cacheReadTokens":12,"cacheWrite5mTokens":3,
                "cost":165153,"keyId":"","sessionId":"old-session"
            }]
        """.trimIndent()

        val record = decodeLegacyUsageCache(json, persisted).single()

        assertEquals(0.00165153, requireNotNull(record.cost), 0.0)
        assertEquals("old-session", record.sessionId)
        assertEquals(456L, record.timeUpdated)
        assertEquals(3L, record.cacheWrite5mTokens)
        assertEquals(1L, record.requestCount)
        assertNull(record.cacheWriteTokensUnspecified)
        assertEquals(record, record.toEntity().toDomain())
    }

    @Test
    fun `request row round trips optional fields without treating null cost or unknown TTL as zero`() {
        val record = UsageRecord(
            id = "server-id", workspaceId = "${OPEN_CODE_LOGS_SCOPE_PREFIX}test",
            timeCreated = 123L, timeUpdated = 456L, model = "model", provider = "provider",
            inputTokens = 20L, outputTokens = 8L, reasoningTokens = 2L, cacheReadTokens = 12L,
            cost = null, keyId = "key", sessionId = "session", durationMs = 15L,
            timeToFirstTokenMs = 3L, outcome = "error", statusCode = 503, errorCode = "error-code",
            country = "US", city = "Test City", reasoningEffort = "high", requestedModel = "alias",
            cacheWriteTokensUnspecified = 5L
        )

        val restored = record.toEntity().toDomain()
        assertEquals(record, restored)
        assertEquals(42L, restored.totalTokens)
        assertEquals(30L, restored.copy(workspaceId = "commandcode").totalTokens)
        assertEquals(0L, restored.cacheWrite5mTokens)
        assertEquals(0L, restored.cacheWrite1hTokens)
        assertEquals(0L, restored.pricedRequests)
        assertEquals(1L, restored.copy(cost = 0.0).pricedRequests)
    }

    @Test
    fun `chart totals retain USD precision and distinguish unknown cost from zero`() {
        val unpriced = UsageRecord(
            id = "unknown", workspaceId = "${OPEN_CODE_LOGS_SCOPE_PREFIX}test",
            timeCreated = 100L, timeUpdated = 100L, model = "model", provider = "provider",
            inputTokens = 0L, outputTokens = 0L, reasoningTokens = 0L, cacheReadTokens = 0L,
            cost = null, keyId = "", sessionId = ""
        )
        val free = unpriced.copy(id = "free", cost = 0.0)
        val billed = unpriced.copy(id = "billed", model = "other", cost = 0.00000000125)
        val bucket = ChartAggregator.aggregateHourly(listOf(unpriced, free, billed)).single()
        val zeroBucket = bucket.byModel.getValue("model")

        assertEquals(0.00000000125, bucket.totalCost, 0.0)
        assertEquals(2L, bucket.pricedRequests)
        assertEquals(3L, bucket.totalRequests)
        assertEquals(0.0, zeroBucket.cost, 0.0)
        assertEquals(1L, zeroBucket.pricedRequests)
        assertEquals(2L, zeroBucket.requests)
    }
}
