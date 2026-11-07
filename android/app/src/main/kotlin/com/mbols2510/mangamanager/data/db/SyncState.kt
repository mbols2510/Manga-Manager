package com.mbols2510.mangamanager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single row (id 0): where the change feed stands. [cursor] is the last update id stored (the next `since`),
 * [etag] the ETag of the last 200 (sent as `If-None-Match`), [lastSuccessAt] epoch millis of the last check
 * that wrote this row.
 */
@Entity(tableName = "sync_state")
data class SyncState(
    val cursor: Long,
    val etag: String?,
    val lastSuccessAt: Long?,
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
) {
    companion object {
        const val SINGLE_ROW_ID = 0
    }
}
