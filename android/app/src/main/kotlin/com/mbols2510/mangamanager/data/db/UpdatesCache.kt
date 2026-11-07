package com.mbols2510.mangamanager.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

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

    suspend fun markNotified(ids: Collection<Long>)
}

/** Room implementation. [afterUpsert] runs inside the transaction between the upsert and the cursor write (tests inject a failure there). */
class RoomUpdatesCache internal constructor(
    private val db: AppDatabase,
    private val afterUpsert: suspend () -> Unit,
) : UpdatesCache {
    constructor(db: AppDatabase) : this(db, {})

    private val dao = db.updateDao()

    override fun feed(): Flow<List<UpdateEntity>> = dao.feed()

    override suspend fun syncState(): SyncState? = dao.syncState()

    override suspend fun setSyncState(state: SyncState) = dao.putSyncState(state)

    // TDD stub (Task 7 RED): not atomic, and existing notified flags are lost.
    override suspend fun applyChanges(items: List<UpdateEntity>, state: SyncState): List<UpdateEntity> {
        dao.upsertAll(items)
        afterUpsert()
        dao.putSyncState(state)
        return items
    }

    override suspend fun markNotified(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.distinct().chunked(MAX_IDS_PER_QUERY).forEach { dao.markNotified(it) }
        }
    }

    private companion object {
        const val MAX_IDS_PER_QUERY = 500
    }
}
