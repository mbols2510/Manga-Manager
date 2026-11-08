package com.mbols2510.mangamanager.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Database(entities = [UpdateEntity::class, SyncState::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun updateDao(): UpdateDao

    companion object {
        const val NAME = "manga_manager.db"

        /** The app's on-disk database (excluded from backup by the data extraction rules). */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}

/** Id and notified flag of a stored update. */
data class IdNotified(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "notified") val notified: Boolean,
)

@Dao
interface UpdateDao {
    @Upsert
    suspend fun upsertAll(items: List<UpdateEntity>)

    /** The cached feed, newest first, at most [limit] rows ([MAX_CACHED_UPDATES] in production). */
    @Query("SELECT * FROM updates ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun feed(limit: Int): Flow<List<UpdateEntity>>

    /**
     * The cache cap: deletes rows whose notification decision is taken (`notified = 1`) and that are not among the
     * newest [keep] rows (same order as [feed]). Pending rows (`notified = 0`) are never deleted here, so no alert is
     * lost; the table holds at most [keep] rows plus the pending ones.
     */
    @Query(
        "DELETE FROM updates WHERE notified = 1 AND id NOT IN " +
            "(SELECT id FROM updates ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimNotified(keep: Int)

    /** At most 999 ids per call (SQLite variable limit on old Android versions); callers chunk. */
    @Query("SELECT id, notified FROM updates WHERE id IN (:ids)")
    suspend fun notifiedFlags(ids: List<Long>): List<IdNotified>

    @Query("UPDATE updates SET notified = 1 WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<Long>)

    @Query("SELECT * FROM updates ORDER BY id ASC")
    suspend fun allById(): List<UpdateEntity>

    /** Stored items whose notification decision was not taken yet (ruling 21), oldest first. */
    @Query("SELECT * FROM updates WHERE notified = 0 ORDER BY id ASC")
    suspend fun pendingNotifications(): List<UpdateEntity>

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun syncState(): SyncState?

    @Upsert
    suspend fun putSyncState(state: SyncState)

    @Query("DELETE FROM updates")
    suspend fun deleteAllUpdates()

    @Query("DELETE FROM sync_state")
    suspend fun deleteSyncState()
}

/** Chapters as a JSON array: chapter names may contain commas, quotes or anything else. */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun chaptersToJson(value: List<String>): String = json.encodeToString(listSerializer, value)

    @TypeConverter
    fun chaptersFromJson(value: String): List<String> =
        try {
            json.decodeFromString(listSerializer, value)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
}
