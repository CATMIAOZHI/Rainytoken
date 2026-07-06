package com.rainy.token.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Database(
    entities = [UsageRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class UsageDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var INSTANCE: UsageDatabase? = null

        fun getInstance(context: Context): UsageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UsageDatabase::class.java,
                    "usage_database"
                ).build().also { INSTANCE = it }
            }
        }
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

    val records = runCatching {
        json.decodeFromString(ListSerializer(UsageRecord.serializer()), raw)
    }.getOrDefault(emptyList())

    if (records.isNotEmpty()) {
        val entities = records.map { it.toEntity() }
        dao.insertAll(entities)
    }

    // Mark migrated and clear old key to free space
    prefs.edit().putBoolean("migrated_to_room", true).apply()
    dataStore.edit { it.remove(cacheKey) }
    return records.isNotEmpty()
}