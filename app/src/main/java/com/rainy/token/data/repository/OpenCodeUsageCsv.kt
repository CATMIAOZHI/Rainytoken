package com.rainy.token.data.repository

import com.rainy.token.data.local.UsageRecord
import java.io.PushbackReader
import java.io.Reader
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal object OpenCodeUsageCsv {
    private val required = listOf(
        "day", "user_type", "user_id", "user_name", "provider", "model", "requests", "input_tokens",
        "output_tokens", "cache_read_tokens", "cache_write_5m_tokens", "cache_write_1h_tokens",
        "cost_micro_cents", "cost_usd"
    )

    fun parse(reader: Reader, scope: String): List<UsageRecord> {
        val rows = rows(reader).iterator()
        if (!rows.hasNext()) malformed("Missing CSV header")
        val header = rows.next()
        val columns = header.withIndex().associate { (index, name) -> name to index }
        if (columns.size != header.size || !required.all(columns::containsKey)) {
            malformed("Missing or duplicate usage export columns")
        }

        val records = mutableListOf<UsageRecord>()
        val ids = hashSetOf<String>()
        while (rows.hasNext()) {
            val cells = rows.next()
            val rowNumber = records.size + 2
            if (cells.size != header.size) malformed("Wrong column count on CSV record $rowNumber")
            fun cell(name: String) = cells[columns.getValue(name)]
            fun text(name: String) = unescapeFormula(cell(name))
            fun number(name: String): Long = cell(name).toLongOrNull()?.takeIf { it >= 0 }
                ?: malformed("Invalid $name on CSV record $rowNumber")
            val day = try {
                LocalDate.parse(cell("day"))
            } catch (_: DateTimeParseException) {
                malformed("Invalid date on CSV record $rowNumber")
            }
            val userType = text("user_type")
            val rawUserId = cell("user_id")
            val provider = text("provider")
            val model = text("model")
            if (userType !in setOf("member", "service_account") || rawUserId.isBlank() ||
                provider.isBlank() || model.isBlank()
            ) malformed("Incomplete usage identity on CSV record $rowNumber")
            // Length-prefix fields to avoid collisions. Use the wire spelling for textual identity
            // (escaping can make an original apostrophe indistinguishable from a protective one).
            val id = listOf(scope, day.toString(), userType, rawUserId, cell("provider"), cell("model"))
                .joinToString("") { "${it.length}:$it" }
            if (!ids.add(id)) malformed("Duplicate usage identity on CSV record $rowNumber")
            val timestamp = day.toEpochDay() * OpenCodeUsageRepository.MILLIS_PER_DAY
            records += UsageRecord(
                id = id,
                workspaceId = scope,
                timeCreated = timestamp,
                timeUpdated = timestamp,
                model = model,
                provider = provider,
                requestCount = number("requests"),
                inputTokens = number("input_tokens"),
                outputTokens = number("output_tokens"),
                reasoningTokens = 0,
                cacheReadTokens = number("cache_read_tokens"),
                cacheWrite5mTokens = number("cache_write_5m_tokens"),
                cacheWrite1hTokens = number("cache_write_1h_tokens"),
                // `cost_usd` represents the same value; use the exact integer for all aggregation.
                cost = number("cost_micro_cents"),
                keyId = "",
                sessionId = "",
                userType = userType,
                userId = text("user_id"),
                userName = text("user_name")
            )
        }
        return records
    }

    private fun unescapeFormula(value: String): String =
        if (value.length >= 2 && value[0] == '\'' && value[1] in "=+-@") value.substring(1) else value

    private enum class FieldState { START, PLAIN, QUOTED, AFTER_QUOTE }

    /** Read CSV records, preserving embedded commas, escaped quotes and multiline quoted cells. */
    private fun rows(input: Reader): Sequence<List<String>> = sequence {
        val reader = PushbackReader(input.buffered())
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var state = FieldState.START
        fun finishField() {
            row.add(field.toString())
            field.clear()
            state = FieldState.START
        }
        val first = reader.read()
        if (first >= 0 && first != 0xfeff) reader.unread(first)
        while (true) {
            val next = reader.read()
            if (next < 0) break
            val ch = next.toChar()
            if (state == FieldState.QUOTED) {
                if (ch == '"') state = FieldState.AFTER_QUOTE else field.append(ch)
                continue
            }
            when (ch) {
                ',' -> finishField()
                '\r', '\n' -> {
                    // Ignore physically empty lines, not a row with empty quoted fields/columns.
                    if (state != FieldState.START || row.isNotEmpty()) {
                        finishField()
                        yield(row.toList())
                        row.clear()
                    }
                    if (ch == '\r') {
                        val following = reader.read()
                        if (following >= 0 && following != '\n'.code) reader.unread(following)
                    }
                }
                '"' -> when (state) {
                    FieldState.START -> state = FieldState.QUOTED
                    FieldState.AFTER_QUOTE -> {
                        field.append('"')
                        state = FieldState.QUOTED
                    }
                    else -> malformed("Unexpected quote in CSV field")
                }
                else -> {
                    if (state == FieldState.AFTER_QUOTE) malformed("Unexpected content after CSV closing quote")
                    field.append(ch)
                    state = FieldState.PLAIN
                }
            }
        }
        if (state == FieldState.QUOTED) malformed("Unclosed quoted CSV field")
        if (state != FieldState.START || row.isNotEmpty()) {
            finishField()
            yield(row.toList())
        }
    }

    private fun malformed(detail: String): Nothing =
        throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.MALFORMED_RESPONSE, detail)
}
