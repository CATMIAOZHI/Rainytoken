package com.rainy.token.data.repository

import com.rainy.token.data.local.ChartAggregator
import com.rainy.token.data.local.diffDailyExport
import com.rainy.token.data.local.toEntity
import com.rainy.token.data.repository.RepositoryError.OpenCodeUsageForbiddenReason
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OpenCodeUsageRepositoryTest {
    private val header = "day,user_type,user_id,user_name,provider,model,requests,input_tokens," +
        "output_tokens,cache_read_tokens,cache_write_5m_tokens,cache_write_1h_tokens,cost_micro_cents,cost_usd"
    private val row = "2026-11-24,member,user_fixture,Name,opencode,example,57,402118,31877,880012,210,31,214500000,2.145"

    private fun parse(csv: String, scope: String = "opencode-csv:fixture") = OpenCodeUsageCsv.parse(csv.reader(), scope)

    @Test
    fun `maps official fields and exact micro cents across Room conversion`() {
        val record = parse("$header\r\n$row\r\n").single()
        assertEquals(Instant.parse("2026-11-24T00:00:00Z").toEpochMilli(), record.timeCreated)
        assertEquals("member", record.userType)
        assertEquals("user_fixture", record.userId)
        assertEquals("Name", record.userName)
        assertEquals("opencode", record.provider)
        assertEquals("example", record.model)
        assertEquals(57L, record.requestCount)
        assertEquals(402118L, record.inputTokens)
        assertEquals(31877L, record.outputTokens)
        assertEquals(880012L, record.cacheReadTokens)
        assertEquals(1314007L, record.totalTokens)
        assertEquals(210L, record.cacheWrite5mTokens)
        assertEquals(31L, record.cacheWrite1hTokens)
        assertEquals(214500000L, record.cost)
        assertEquals(2.145, record.costUsd, 0.0)
        assertEquals(record, record.toEntity().toDomain())
        assertEquals(57L, ChartAggregator.aggregateDaily(listOf(record)).single().totalRequests)
    }

    @Test
    fun `reads CRLF, UTF8 BOM, escaped quotes, comma, multiline and formula escaping`() {
        val quoted = row.replace("Name", "\"'@Ada, \"\"Team\"\"\r\nops\"").replace("opencode,example", "'+provider,'-model")
        val blankName = row.replace("user_fixture,Name", "user_other,").replace("member", "service_account")
        val apostrophe = row.replace("user_fixture,Name", "user_third,'Ada")
        val data = parse("\uFEFF$header\r\n\r\n$quoted\r\n$blankName\r\n$apostrophe")
        assertEquals("@Ada, \"Team\"\r\nops", data[0].userName)
        assertEquals("+provider", data[0].provider)
        assertEquals("-model", data[0].model)
        assertEquals("", data[1].userName)
        assertEquals("'Ada", data[2].userName)
        assertTrue(parse("$header\r\n\r\n").isEmpty())
    }

    @Test
    fun `invalid export fails atomically rather than dropping bad rows`() {
        val invalid = listOf(
            "",
            "${header.substringBeforeLast(',')}\r\n$row",
            "$header\r\n$row\r\n${row.substringBeforeLast(',')}",
            "$header\r\n${row.replace("Name", "\"unclosed")}",
            "$header\r\n${row.replace("Name", "\"closed\"extra")}",
            "$header\r\n${row.replace(",57,", ",bogus,")}",
            "$header\r\n$row\r\n$row"
        )
        invalid.forEach { csv ->
            assertEquals(RepositoryError.ParseErrorReason.MALFORMED_RESPONSE,
                assertThrows(RepositoryError.ParseError::class.java) { parse(csv) }.reason)
        }
    }

    @Test
    fun `snapshot diffs today's counters and historical holes without rewriting unchanged rows`() {
        val first = parse("$header\n$row").single().toEntity()
        val changed = parse("$header\n${row.replace(",57,", ",59,")}").single().toEntity()
        assertEquals(first.id, changed.id)
        val backfill = parse("$header\n${row.replace("2026-11-24", "2026-11-21")}").single().toEntity()
        val older = parse("$header\n${row.replace("2026-11-24", "2026-11-20")}").single().toEntity()
        val removed = parse("$header\n${row.replace("user_fixture", "user_removed")}").single().toEntity()
        val diff = diffDailyExport(listOf(first, older, removed), listOf(changed, backfill, older))
        assertEquals(listOf(backfill), diff.inserts)
        assertEquals(listOf(changed), diff.updates)
        assertEquals(listOf(removed.id), diff.deletes)
        val unchanged = diffDailyExport(listOf(changed, backfill), listOf(backfill, changed))
        assertTrue(unchanged.inserts.isEmpty() && unchanged.updates.isEmpty() && unchanged.deletes.isEmpty())
    }

    @Test
    fun `scope depends only on the trimmed Go bearer key`() {
        val first = Credential.SessionCredential(service = ServiceType.OPENCODE_GO, apiKey = "fixture-A")
        val scope = OpenCodeUsageRepository.cacheScope(first)!!
        assertTrue("fixture-A" !in scope)
        assertEquals(scope, OpenCodeUsageRepository.cacheScope(first.copy(apiKey = " fixture-A ", workspaceId = "old")))
        assertNotEquals(scope, OpenCodeUsageRepository.cacheScope(first.copy(apiKey = "fixture-B")))
        assertNotEquals(parse("$header\n$row", scope).single().id, parse("$header\n$row", "other").single().id)
        assertNull(OpenCodeUsageRepository.cacheScope(first.copy(apiKey = null)))
    }

    private fun httpError(code: Int, body: String = "", retryAfter: String? = null): RepositoryError =
        Response.Builder().code(code).message("test").protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://example.invalid/").build())
            .body(body.toResponseBody())
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .build().use { OpenCodeUsageRepository.httpError(it) }

    @Test
    fun `503 means retry later not invalid credentials or an empty export`() {
        assertTrue(httpError(503) is RepositoryError.OpenCodeUsageSummaryUnavailable)
        assertTrue(httpError(400) is RepositoryError.OpenCodeUsageBadRequest)
        assertTrue(httpError(401) is RepositoryError.InvalidCredential)
        assertEquals(17L, (httpError(429, retryAfter = "17") as RepositoryError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `403 distinguishes supported hints without guessing for a bare forbidden`() {
        assertEquals(OpenCodeUsageForbiddenReason.MISSING_PERMISSION,
            (httpError(403, """{"message":"Usage: Read permission required"}""") as RepositoryError.OpenCodeUsageForbidden).reason)
        assertEquals(OpenCodeUsageForbiddenReason.NOT_OWNER_OR_ADMIN,
            (httpError(403, """{"message":"Must be a workspace owner or admin"}""") as RepositoryError.OpenCodeUsageForbidden).reason)
        listOf(
            """{"_tag":"Forbidden"}""",
            """{"message":"Permission denied"}""",
            """{"message":"Use an owner or admin key with Usage › Read"}"""
        ).forEach { body ->
            assertEquals(OpenCodeUsageForbiddenReason.UNSPECIFIED,
                (httpError(403, body) as RepositoryError.OpenCodeUsageForbidden).reason)
        }
    }
}
