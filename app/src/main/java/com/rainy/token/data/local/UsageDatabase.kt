package com.rainy.token.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.long

@Database(
    entities = [UsageRecordEntity::class],
    version = 3,
    exportSchema = false
)
abstract class UsageDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var INSTANCE: UsageDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_records ADD COLUMN requestCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN userType TEXT")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN userId TEXT")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN userName TEXT")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep all existing rows in their original (including CSV) scope. Recreate the table
                // to replace the global ID key with a scoped key and the legacy integer cost with USD.
                db.execSQL(
                    """
                    CREATE TABLE usage_records_new (
                        id TEXT NOT NULL,
                        workspaceId TEXT NOT NULL,
                        timeCreated INTEGER NOT NULL,
                        timeUpdated INTEGER NOT NULL,
                        model TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        inputTokens INTEGER NOT NULL,
                        outputTokens INTEGER NOT NULL,
                        reasoningTokens INTEGER NOT NULL,
                        cacheReadTokens INTEGER NOT NULL,
                        cacheWrite5mTokens INTEGER NOT NULL,
                        cacheWrite1hTokens INTEGER NOT NULL,
                        cost REAL,
                        keyId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        enrichmentPlan TEXT NOT NULL,
                        requestCount INTEGER NOT NULL DEFAULT 1,
                        userType TEXT,
                        userId TEXT,
                        userName TEXT,
                        durationMs INTEGER,
                        timeToFirstTokenMs INTEGER,
                        outcome TEXT,
                        statusCode INTEGER,
                        errorCode TEXT,
                        country TEXT,
                        city TEXT,
                        reasoningEffort TEXT,
                        requestedModel TEXT,
                        cacheWriteTokensUnspecified INTEGER,
                        PRIMARY KEY(workspaceId, id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO usage_records_new (
                        id, workspaceId, timeCreated, timeUpdated, model, provider,
                        inputTokens, outputTokens, reasoningTokens, cacheReadTokens,
                        cacheWrite5mTokens, cacheWrite1hTokens, cost, keyId, sessionId,
                        enrichmentPlan, requestCount, userType, userId, userName
                    )
                    SELECT
                        id, workspaceId, timeCreated, timeUpdated, model, provider,
                        inputTokens, outputTokens, reasoningTokens, cacheReadTokens,
                        cacheWrite5mTokens, cacheWrite1hTokens, cost / $LEGACY_COST_UNITS_PER_USD,
                        keyId, sessionId, enrichmentPlan, requestCount, userType, userId, userName
                    FROM usage_records
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE usage_records")
                db.execSQL("ALTER TABLE usage_records_new RENAME TO usage_records")
                db.execSQL("CREATE INDEX index_usage_records_workspaceId_timeCreated_id ON usage_records (workspaceId, timeCreated, id)")
                db.execSQL("CREATE INDEX index_usage_records_workspaceId_model_timeCreated ON usage_records (workspaceId, model, timeCreated)")
            }
        }

        fun getInstance(context: Context): UsageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UsageDatabase::class.java,
                    "usage_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}

private const val LEGACY_COST_UNITS_PER_USD = 100_000_000.0

/** Only the old DataStore cache encodes cost as integer units. New rows already contain USD. */
internal fun decodeLegacyUsageCache(json: Json, raw: String): List<UsageRecord> =
    json.decodeFromString(ListSerializer(LegacyUsageRecordSerializer), raw)

private object LegacyUsageRecordSerializer : JsonTransformingSerializer<UsageRecord>(UsageRecord.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val values = element as? JsonObject ?: return element
        val rawCost = values["cost"] as? JsonPrimitive ?: return element
        return JsonObject(values + ("cost" to JsonPrimitive(rawCost.long / LEGACY_COST_UNITS_PER_USD)))
    }
}

/**
 * One-time migration from old DataStore JSON cache to Room.
 *
 * Called by [UsageCache] on first access. Reads the old JSON blob from DataStore,
 * deserializes it, inserts into Room, then clears the old key.
 *
 * A SharedPreferences flag prevents re-running if the old data was already consumed
 * (e.g. if the DataStore was cleared but the flag was set).
 *
 * @return true if migration ran and inserted records, false if no migration was needed.
 */
suspend fun migrateDataStoreToRoom(
    context: Context,
    dataStore: DataStore<Preferences>,
    dao: UsageDao,
    json: Json
): Boolean {
    val prefs = context.getSharedPreferences("usage_cache_migration", Context.MODE_PRIVATE)
    if (prefs.getBoolean("migrated_to_room", false)) return false

    val cacheKey = stringPreferencesKey("usage_cache_v1")
    val raw = dataStore.data.map { it[cacheKey] }.first() ?: run {
        // No old data — mark as migrated so we never check again
        prefs.edit().putBoolean("migrated_to_room", true).apply()
        return false
    }

    val records = runCatching { decodeLegacyUsageCache(json, raw) }.getOrElse {
        // Leave an unreadable legacy payload intact; do not clear data we couldn't import.
        return false
    }

    if (records.isNotEmpty()) {
        val entities = records.map { it.toEntity() }
        dao.insertAll(entities)
    }

    // Clear the old key only after Room has copied the rows, then mark the import complete.
    dataStore.edit { it.remove(cacheKey) }
    prefs.edit().putBoolean("migrated_to_room", true).apply()
    return records.isNotEmpty()
}
