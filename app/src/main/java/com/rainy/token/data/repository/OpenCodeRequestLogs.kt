package com.rainy.token.data.repository

import com.rainy.token.data.local.UsageRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Only the explicit allowlist below is mapped to storage; arbitrary request/response data is ignored. */
internal object OpenCodeRequestLogs {
    // Not a data class: its generated toString() would expose the cursor and records.
    class Page(val records: List<UsageRecord>, val nextCursor: String?, val until: Long?)

    private const val UNKNOWN = "unknown"

    fun parse(text: String, scope: String): Page {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            // JSON exception messages can quote the response, including sensitive unknown fields.
            malformed("Invalid OpenCode request-logs JSON")
        } ?: throw RepositoryError.ParseError(
            RepositoryError.ParseErrorReason.NOT_JSON_OBJECT, "Expected an OpenCode request-logs JSON object"
        )
        val items = root["items"] as? JsonArray ?: malformed("Missing OpenCode request-logs items array")
        val cursor = when (val value = root["nextCursor"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.content
                ?: malformed("Invalid OpenCode request-logs cursor")
            else -> malformed("Invalid OpenCode request-logs cursor")
        }
        val until = when (root["until"]) {
            null, JsonNull -> null
            else -> root.timestamp("until") ?: malformed("Invalid OpenCode request-logs until")
        }
        return Page(items.map { item ->
            val obj = item as? JsonObject ?: malformed("Invalid OpenCode request-logs item")
            obj.toRecord(scope)
        }, cursor, until)
    }

    private fun JsonObject.toRecord(scope: String): UsageRecord {
        val id = text("id") ?: malformed("Missing OpenCode request-log ID")
        val startedAt = timestamp("startedAt") ?: malformed("Invalid OpenCode request-log start time")
        val requestedModel = text("requestedModel")
        return UsageRecord(
            id = id,
            workspaceId = scope, // The remote workspace ID is not a local cache partition.
            timeCreated = startedAt,
            // Zero means the API did not provide a valid end time; equality with start can be real.
            timeUpdated = timestamp("finishedAt")?.takeIf { it >= startedAt } ?: 0L,
            model = text("model") ?: requestedModel ?: UNKNOWN,
            provider = text("provider") ?: UNKNOWN,
            inputTokens = nonnegativeLong("inputTokens") ?: 0L,
            outputTokens = nonnegativeLong("outputTokens") ?: 0L,
            reasoningTokens = nonnegativeLong("reasoningTokens") ?: 0L,
            cacheReadTokens = nonnegativeLong("cacheReadTokens") ?: 0L,
            cost = (this["cost"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 },
            keyId = "",
            sessionId = text("sessionID").orEmpty(),
            durationMs = nonnegativeLong("durationMs"),
            timeToFirstTokenMs = nonnegativeLong("timeToFirstTokenMs"),
            outcome = text("outcome"),
            statusCode = nonnegativeLong("statusCode")?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
            errorCode = text("errorCode"),
            country = text("country"),
            city = text("city"),
            reasoningEffort = text("reasoningEffort"),
            requestedModel = requestedModel,
            cacheWriteTokensUnspecified = nonnegativeLong("cacheWriteTokens")
        )
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeUnless(String::isBlank)

    private fun JsonObject.nonnegativeLong(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

    private fun JsonObject.timestamp(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }

    fun malformed(detail: String): Nothing =
        throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.MALFORMED_RESPONSE, detail)
}
