package com.mbols2510.mangamanager.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * How many updates the phone keeps. The server prunes after `web.keep_days`; without a cap the phone would keep
 * everything, and every Room write re-runs the feed query over all of it. Rows still waiting for a notification
 * decision (`notified == false`) are kept on top of this (see [UpdateDao.trimNotified]).
 */
const val MAX_CACHED_UPDATES = 500

/** What the repository needs from local storage. The seam lets tests count writes. */
interface UpdatesCache {
    fun feed(): Flow<List<UpdateEntity>>
    suspend fun syncState(): SyncState?
    suspend fun setSyncState(state: SyncState)

    /**
     * In ONE transaction: upsert [items] (keeping the local `notified` flag of ids already stored) and write
     * [state]. Returns the items whose ids were not stored before, in the order given.
     */
    suspend fun applyChanges(items: List<UpdateEntity>, state: SyncState): List<UpdateEntity>

    /** Sets `notified` for these ids: the notification decision for them is taken. */
    suspend fun markNotified(ids: Collection<Long>)

    /**
     * Upserts rows of the browse endpoint with `notified = true` (they are shown in the app, so they never notify).
     * Never touches the sync state. Applies the [MAX_CACHED_UPDATES] cap in the same transaction, like
     * [applyChanges] and [markNotified].
     */
    suspend fun storeBrowsed(items: List<UpdateEntity>)

    /** Stored items whose notification decision is not taken yet (`notified == false`), oldest first. */
    suspend fun pendingNotifications(): List<UpdateEntity>

    /** Unpairing: removes every cached update and the sync state (cursor, ETag) in ONE transaction. */
    suspend fun clearAll()
}

/** Room implementation. [afterUpsert] runs inside the transaction between the upsert and the cursor write (tests inject a failure there). */
class RoomUpdatesCache internal constructor(
    private val db: AppDatabase,
    private val afterUpsert: suspend () -> Unit,
) : UpdatesCache {
    constructor(db: AppDatabase) : this(db, {})

    private val dao = db.updateDao()

    override fun feed(): Flow<List<UpdateEntity>> = dao.feed(MAX_CACHED_UPDATES)

    override suspend fun syncState(): SyncState? = dao.syncState()

    override suspend fun setSyncState(state: SyncState) = dao.putSyncState(state)

    override suspend fun applyChanges(items: List<UpdateEntity>, state: SyncState): List<UpdateEntity> =
        db.withTransaction {
            val known = HashMap<Long, Boolean>()
            items.map { it.id }.distinct().chunked(MAX_IDS_PER_QUERY).forEach { chunk ->
                dao.notifiedFlags(chunk).forEach { known[it.id] = it.notified }
            }
            dao.upsertAll(items.map { it.copy(notified = known[it.id] ?: false) })
            afterUpsert()
            dao.putSyncState(state)
            dao.trimNotified(MAX_CACHED_UPDATES) // new items are pending (notified = false), so never trimmed here
            items.filter { it.id !in known }
        }

    override suspend fun markNotified(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.distinct().chunked(MAX_IDS_PER_QUERY).forEach { dao.markNotified(it) }
            dao.trimNotified(MAX_CACHED_UPDATES) // rows that just stopped being pending fall under the cap
        }
    }

    override suspend fun storeBrowsed(items: List<UpdateEntity>) {
        if (items.isEmpty()) return
        db.withTransaction {
            dao.upsertAll(items.map { it.copy(notified = true) })
            dao.trimNotified(MAX_CACHED_UPDATES)
        }
    }

    override suspend fun pendingNotifications(): List<UpdateEntity> = dao.pendingNotifications()

    override suspend fun clearAll() {
        db.withTransaction {
            dao.deleteAllUpdates()
            dao.deleteSyncState()
        }
    }

    private companion object {
        const val MAX_IDS_PER_QUERY = 500
    }
}
