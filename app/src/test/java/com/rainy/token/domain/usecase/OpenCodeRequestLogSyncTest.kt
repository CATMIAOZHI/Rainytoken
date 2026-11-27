package com.rainy.token.domain.usecase

import com.rainy.token.data.local.UsageRecord
import com.rainy.token.data.repository.OpenCodeRequestLogs
import com.rainy.token.data.repository.OpenCodeUsageRepository
import com.rainy.token.data.repository.RepositoryError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeRequestLogSyncTest {
    private val now = 1_800_000_000_000L
    private val retention = 30L * 24 * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val full = OpenCodeLogsWindow(now - retention, now)

    @Test
    fun `first manual stale and clock rollback windows use exact retention floor`() {
        assertEquals(full, openCodeLogsWindow(now, true, 0))
        assertEquals(full, openCodeLogsWindow(now, false, now - 3000))
        assertEquals(full, openCodeLogsWindow(now, true, now + 1000))
        assertEquals(full, openCodeLogsWindow(now, true, full.since + hour - 1))
        assertEquals(full, openCodeLogsWindow(now, true, full.since - 1))
        assertEquals(OpenCodeLogsWindow(0L, 123L), openCodeLogsWindow(123, false, 0))
    }

    @Test
    fun `dormant account with old local rows advances using a fully completed empty window`() = runBlocking {
        val cache = FixtureCache(record("historical", full.since + 1))
        val previousCheckpoint = now - 3 * hour
        cache.checkpoint(previousCheckpoint)
        val previousWindow = openCodeLogsWindow(now - hour, true, cache.completedUntil)
        assertEquals(previousCheckpoint - hour, previousWindow.since)
        syncOpenCodeRequestLogs(previousWindow, { _, _, _ ->
            parsePage(emptyList(), null, previousWindow.until)
        }, cache::insert, cache::count, cache::checkpoint)
        assertEquals(full.since + 1, cache.latest)
        assertEquals(OpenCodeLogsWindow(now - 2 * hour, now), openCodeLogsWindow(now, true, cache.completedUntil))
    }

    @Test
    fun `freezes server until and keeps paging through known and empty pages`() = runBlocking {
        val acceptedUntil = now - 7000
        val known = record("known", now - 8000)
        val cache = FixtureCache(known, record("history", full.since - 1))
        val cursorA = "{\"fixture\":\"雪+ ?/#\"}"
        val cursorB = "cursor-b"
        val cursorC = "cursor-c"
        val calls = mutableListOf<Triple<Long, Long, String?>>()
        val result = syncOpenCodeRequestLogs(full, { since, until, cursor ->
            calls.add(Triple(since, until, cursor))
            when (cursor) {
                null -> parsePage(listOf(known), cursorA, acceptedUntil)
                cursorA -> parsePage(emptyList(), cursorB, acceptedUntil)
                cursorB -> parsePage(listOf(record("boundary", full.since), known), cursorC, acceptedUntil)
                cursorC -> parsePage(listOf(record("late", acceptedUntil)), null, acceptedUntil)
                else -> error("Unexpected fixture cursor")
            }
        }, cache::insert, cache::count, cache::checkpoint)
        assertEquals(listOf(
            Triple(full.since, full.until, null), Triple(full.since, acceptedUntil, cursorA),
            Triple(full.since, acceptedUntil, cursorB), Triple(full.since, acceptedUntil, cursorC)
        ), calls)
        assertEquals(SyncResult(inserted = 2, totalCount = 4, scanned = 4, pages = 4), result)
        assertEquals(acceptedUntil, cache.completedUntil)
        assertTrue(cache.records.containsKey("history"))
    }

    @Test
    fun `only null ends pagination and blank cursor strings are encoded as-is`() = runBlocking {
        val cache = FixtureCache()
        val cursors = mutableListOf<String?>()
        val spaces = " \t "
        val result = syncOpenCodeRequestLogs(full, { since, until, cursor ->
            val url = OpenCodeUsageRepository.pageRequest("fixture-key", since, until, cursor).url
            val encodedThenDecoded = url.queryParameter("cursor")
            assertEquals(cursor, encodedThenDecoded)
            assertEquals(cursor != null, "cursor" in url.queryParameterNames)
            cursors.add(encodedThenDecoded)
            when (encodedThenDecoded) {
                null -> parsePage(emptyList(), "", now)
                "" -> parsePage(emptyList(), spaces, now)
                spaces -> parsePage(emptyList(), null, now)
                else -> error("Unexpected fixture cursor")
            }
        }, cache::insert, cache::count, cache::checkpoint)
        assertEquals(listOf(null, "", spaces), cursors)
        assertEquals(3, result.pages)
        assertEquals(now, cache.completedUntil)
    }

    @Test
    fun `completed empty results use the authoritative checkpoint next time`() = runBlocking {
        val cache = FixtureCache()
        val result = syncOpenCodeRequestLogs(full, { _, _, _ ->
            OpenCodeRequestLogs.parse("""{"items":[],"until":${now - 7000}}""", SCOPE)
        }, cache::insert, cache::count, cache::checkpoint)
        assertEquals(SyncResult(inserted = 0, pages = 1), result)
        assertEquals(now - 7000, cache.completedUntil)
        assertEquals(now - 7000 - hour, openCodeLogsWindow(now, true, cache.completedUntil).since)
    }

    @Test
    fun `failed first sync keeps successful pages but does not set a checkpoint`() = runBlocking {
        val cache = FixtureCache()
        val first = record("first", now - 1000)
        val result = runCatching { syncOpenCodeRequestLogs(full, { _, _, cursor ->
            if (cursor == null) parsePage(listOf(first), "next-fixture", now)
            else throw RepositoryError.RateLimited()
        }, cache::insert, cache::count, cache::checkpoint) }
        assertTrue(result.exceptionOrNull() is RepositoryError.RateLimited)
        assertEquals(setOf("first"), cache.records.keys)
        assertEquals(full, openCodeLogsWindow(now, true, cache.completedUntil))
    }

    @Test
    fun `cursor cycles fail and partial newer pages cannot jump past previous checkpoint`() = runBlocking {
        val cache = FixtureCache(record("known", now - 90_000))
        cache.checkpoint(now - 60_000)
        val result = runCatching { syncOpenCodeRequestLogs(full, { _, _, cursor ->
            when (cursor) {
                null -> parsePage(listOf(record("new", now - 1000)), "fixture-a", now)
                "fixture-a" -> parsePage(emptyList(), "fixture-b", now)
                "fixture-b" -> parsePage(emptyList(), "fixture-a", now)
                else -> error("Unexpected fixture cursor")
            }
        }, cache::insert, cache::count, cache::checkpoint) }
        assertTrue(result.exceptionOrNull() is RepositoryError.ParseError)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("fixture-a"))
        assertEquals(now - 60_000, cache.completedUntil)
        assertEquals(now - 60_000 - hour, openCodeLogsWindow(now, true, cache.completedUntil).since)
    }

    @Test
    fun `cancellation during a later page leaves the existing checkpoint intact`() = runBlocking {
        val cache = FixtureCache()
        cache.checkpoint(now - hour)
        val waitingOnNextPage = CompletableDeferred<Unit>()
        val job = launch {
            syncOpenCodeRequestLogs(full, { _, _, cursor ->
                if (cursor == null) parsePage(listOf(record("partial", now - 1000)), "fixture-next", now)
                else {
                    waitingOnNextPage.complete(Unit)
                    awaitCancellation()
                }
            }, cache::insert, cache::count, cache::checkpoint)
        }
        waitingOnNextPage.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(now - hour, cache.completedUntil)
        assertEquals(1, cache.count())
    }

    @Test
    fun `changed window or out of range row cannot advance the checkpoint`() = runBlocking {
        val cache = FixtureCache()
        val result = runCatching { syncOpenCodeRequestLogs(full, { _, _, cursor ->
            if (cursor == null) parsePage(emptyList(), "fixture-next", now - 1000)
            else parsePage(emptyList(), null, now)
        }, cache::insert, cache::count, cache::checkpoint) }
        assertTrue(result.exceptionOrNull() is RepositoryError.ParseError)
        val outOfRange = runCatching { syncOpenCodeRequestLogs(full, { _, _, _ ->
            parsePage(listOf(record("early", full.since - 1)), null, now)
        }, cache::insert, cache::count, cache::checkpoint) }
        assertTrue(outOfRange.exceptionOrNull() is RepositoryError.ParseError)
        assertNull(cache.latest)
        assertEquals(0L, cache.completedUntil)
    }

    private fun record(id: String, timestamp: Long): UsageRecord =
        OpenCodeRequestLogs.parse("""{"items":[{"id":${JsonPrimitive(id)},"startedAt":$timestamp}]}""", SCOPE)
            .records.single()

    private fun parsePage(rows: List<UsageRecord>, cursor: String?, until: Long): OpenCodeRequestLogs.Page {
        val items = rows.joinToString(",") { """{"id":${JsonPrimitive(it.id)},"startedAt":${it.timeCreated}}""" }
        return OpenCodeRequestLogs.parse("""{"items":[$items],"nextCursor":${JsonPrimitive(cursor)},"until":$until}""", SCOPE)
    }

    private class FixtureCache(vararg initial: UsageRecord) {
        val records = initial.associateBy { it.id }.toMutableMap()
        val latest: Long? get() = records.values.maxOfOrNull { it.timeCreated }
        var completedUntil: Long = 0L
            private set
        fun insert(page: List<UsageRecord>): Int = page.count { records.putIfAbsent(it.id, it) == null }
        fun count(): Int = records.size
        fun checkpoint(until: Long) { completedUntil = until }
    }

    companion object {
        private const val SCOPE = "opencode-logs:fixture"
    }
}
