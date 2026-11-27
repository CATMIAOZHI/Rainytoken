package com.rainy.token.data.repository

import com.rainy.token.data.repository.RepositoryError.OpenCodeUsageForbiddenReason
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeUsageRepositoryTest {
    private val scope = "opencode-logs:fixture"

    @Test
    fun `decodes request-log fields with dollar cost and no invented cache buckets`() {
        val page = OpenCodeRequestLogs.parse("""
            {"retentionDays":30,"until":1800000000123,"items":[{
              "id":"fixture-request","workspaceID":"fixture-remote-workspace","sessionID":"fixture-session",
              "startedAt":1800000000000,"finishedAt":1800000000100,"durationMs":100,
              "requestedModel":"fixture-alias","model":"fixture-model","provider":"fixture-provider",
              "outcome":"succeeded","statusCode":200,"timeToFirstTokenMs":39,"reasoningEffort":"low",
              "inputTokens":25,"outputTokens":11,"reasoningTokens":3,"cacheReadTokens":8,
              "cacheWriteTokens":5,"cost":0.000001725,"country":"XX","city":"Fixture City",
              "headers":{"authorization":"fixture-should-not-map"},"body":"fixture-should-not-map",
              "metadata":{"unused":"fixture-should-not-map"}
            }],"nextCursor":null}
        """.trimIndent(), scope)
        val record = page.records.single()
        assertEquals(1800000000123L, page.until)
        assertNull(page.nextCursor)
        assertEquals("fixture-request", record.id)
        assertEquals(scope, record.workspaceId)
        assertEquals("fixture-session", record.sessionId)
        assertEquals(1800000000000L, record.timeCreated)
        assertEquals(1800000000100L, record.timeUpdated)
        assertEquals(100L, record.durationMs)
        assertEquals(39L, record.timeToFirstTokenMs)
        assertEquals("fixture-model", record.model)
        assertEquals("fixture-alias", record.requestedModel)
        assertEquals("fixture-provider", record.provider)
        assertEquals("succeeded", record.outcome)
        assertEquals(200, record.statusCode)
        assertEquals(25L, record.inputTokens)
        assertEquals(11L, record.outputTokens)
        assertEquals(3L, record.reasoningTokens)
        assertEquals(8L, record.cacheReadTokens)
        assertEquals(5L, record.cacheWriteTokensUnspecified)
        assertEquals(0L, record.cacheWrite5mTokens)
        assertEquals(0L, record.cacheWrite1hTokens)
        assertEquals(0.000001725, record.cost!!, 0.0)
        assertEquals(1L, record.requestCount)
        assertEquals("low", record.reasoningEffort)
        assertEquals("XX", record.country)
        assertEquals("Fixture City", record.city)
        assertFalse(record.toString().contains("fixture-should-not-map"))
    }

    @Test
    fun `requests with missing optional fields are retained and HTTP 200 may be cancelled`() {
        val page = OpenCodeRequestLogs.parse("""
            {"items":[
              {"id":"fixture-rejected","startedAt":1800000000000,"outcome":"rejected","statusCode":429,
               "errorCode":"fixture-throttled","requestedModel":"fixture-alias","model":null,
               "inputTokens":null,"cacheWriteTokens":null,"cost":null},
              {"id":"fixture-cancelled","startedAt":1800000000001,"outcome":"cancelled","statusCode":200,
               "finishedAt":1800000000001,"cost":0}
            ]}
        """.trimIndent(), scope)
        val (rejected, cancelled) = page.records
        assertNull(page.nextCursor)
        assertEquals("fixture-alias", rejected.model)
        assertEquals("fixture-throttled", rejected.errorCode)
        assertEquals("rejected", rejected.outcome)
        assertEquals("unknown", rejected.provider)
        assertEquals("", rejected.sessionId)
        assertEquals(0L, rejected.timeUpdated)
        assertEquals(0L, rejected.inputTokens)
        assertNull(rejected.cacheWriteTokensUnspecified)
        assertNull(rejected.cost)
        assertEquals(200, cancelled.statusCode)
        assertEquals("cancelled", cancelled.outcome)
        assertEquals("unknown", cancelled.model)
        assertEquals(cancelled.timeCreated, cancelled.timeUpdated)
        assertEquals(0.0, cancelled.cost!!, 0.0)
    }

    @Test
    fun `does not silently drop malformed required records or treat an invalid cursor as bottom`() {
        listOf(
            """{"items":[{"startedAt":1800000000000}]}""",
            """{"items":[{"id":"fixture-log","startedAt":"1800000000000"}]}""",
            """{"items":[{"id":"fixture-log","startedAt":null}]}""",
            """{"items":null}""",
            """{"items":[],"nextCursor":{}}""",
            """{"items":[],"nextCursor":8}"""
        ).forEach { json ->
            assertThrows(RepositoryError.ParseError::class.java) { OpenCodeRequestLogs.parse(json, scope) }
        }
        val err = assertThrows(RepositoryError.ParseError::class.java) {
            OpenCodeRequestLogs.parse("""{"nextCursor":"fixture-cursor-do-not-report","items":~}""", scope)
        }
        assertFalse(err.message.orEmpty().contains("fixture-cursor-do-not-report"))
        assertNull(err.cause)
    }

    @Test
    fun `round trips opaque cursor with one query encoding and only the bearer header`() {
        val cursor = OpenCodeRequestLogs.parse("""
            {"items":[],"nextCursor":" {\"opaque\":\"雪+/%&?#=\"} "}
        """.trimIndent(), scope).nextCursor
        assertEquals(" {\"opaque\":\"雪+/%&?#=\"} ", cursor)
        val request = OpenCodeUsageRepository.pageRequest("fixture-api-key", 1799999999900L, 1800000000000L, cursor)
        assertEquals("GET", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("opencode.ai", request.url.host)
        assertEquals("/console/api/request-logs", request.url.encodedPath)
        assertEquals("inference", request.url.queryParameter("category"))
        assertEquals("100", request.url.queryParameter("limit"))
        assertEquals("1799999999900", request.url.queryParameter("since"))
        assertEquals("1800000000000", request.url.queryParameter("until"))
        assertEquals(cursor, request.url.queryParameter("cursor"))
        assertEquals(setOf("category", "limit", "since", "until", "cursor"), request.url.queryParameterNames)
        assertEquals("Bearer fixture-api-key", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.header("x-org-id"))
        assertNull(request.header("Cookie"))
        val initial = OpenCodeUsageRepository.pageRequest("fixture-api-key", 1L, 2L, null)
        assertFalse(initial.url.queryParameterNames.contains("cursor"))
    }

    @Test
    fun `scope depends only on the trimmed Go bearer key and excludes legacy caches`() {
        val credential = Credential.SessionCredential(service = ServiceType.OPENCODE_GO, apiKey = "fixture-one")
        val initial = OpenCodeUsageRepository.cacheScope(credential)!!
        assertTrue(initial.matches(Regex("opencode-logs:[a-f0-9]{64}")))
        assertEquals(initial, OpenCodeUsageRepository.cacheScope(credential.copy(
            apiKey = " fixture-one ", workspaceId = "fixture-workspace", lastVerifiedAt = 42L
        )))
        assertNotEquals(initial, OpenCodeUsageRepository.cacheScope(credential.copy(apiKey = "fixture-other")))
        assertNull(OpenCodeUsageRepository.cacheScope(credential.copy(apiKey = " ")))
    }

    @Test
    fun `http errors preserve status mapping and 403 hints`() {
        assertTrue(httpError(400) is RepositoryError.OpenCodeUsageBadRequest)
        assertTrue(httpError(401) is RepositoryError.InvalidCredential)
        assertTrue(httpError(503) is RepositoryError.OpenCodeUsageSummaryUnavailable)
        assertEquals(8L, (httpError(429, retryAfter = "8") as RepositoryError.RateLimited).retryAfterSeconds)
        assertNull((httpError(429, retryAfter = "-1") as RepositoryError.RateLimited).retryAfterSeconds)
        assertEquals(502, (httpError(502) as RepositoryError.ServerError).code)
        assertEquals(OpenCodeUsageForbiddenReason.UNSPECIFIED, forbidden("Forbidden"))
        assertEquals(OpenCodeUsageForbiddenReason.MISSING_PERMISSION, forbidden("Requires usage:read"))
        assertEquals(OpenCodeUsageForbiddenReason.NOT_OWNER_OR_ADMIN, forbidden("Must be owner or administrator"))
    }

    private fun forbidden(body: String) = (httpError(403, body) as RepositoryError.OpenCodeUsageForbidden).reason

    private fun httpError(code: Int, body: String = "", retryAfter: String? = null): RepositoryError =
        Response.Builder().request(Request.Builder().url("https://example.invalid/").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .apply { retryAfter?.let { header("Retry-After", it) } }.body(body.toResponseBody())
            .build().use { OpenCodeUsageRepository.httpError(it) }
}
