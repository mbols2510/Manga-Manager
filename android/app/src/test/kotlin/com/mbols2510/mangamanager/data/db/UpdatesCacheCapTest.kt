package com.mbols2510.mangamanager.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The local cache keeps only the newest [MAX_CACHED_UPDATES] decided rows; pending rows are never dropped. */
@RunWith(RobolectricTestRunner::class)
class UpdatesCacheCapTest {
    private lateinit var db: AppDatabase
    private lateinit var cache: RoomUpdatesCache

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = RoomUpdatesCache(db)
    }

    @After
    fun tearDown() = db.close()

    /** createdAt grows with the id, so "newest" is the highest id. */
    private fun row(id: Long, notified: Boolean = true) = UpdateEntity(
        id = id, title = "T$id", category = "Manga", isNew = false, chapters = listOf("c$id"),
        createdAt = "2026-%02d-%02dT%02d:%02d:00+00:00".format(1 + (id / 10_000) % 12, 1 + (id / 1_000) % 28, (id / 60) % 24, id % 60),
        thumbnail = null, kavitaUrl = null, sensitive = false, notified = notified,
    )

    private fun ids() = runBlocking { db.updateDao().allById().map { it.id } }

    @Test
    fun browsed_rows_beyond_the_cap_keep_only_the_newest() = runBlocking {
        val n = MAX_CACHED_UPDATES
        cache.storeBrowsed((1L..(n + 120L)).map { row(it) })
        assertEquals(((121L)..(n + 120L)).toList(), ids())
    }

    @Test
    fun applied_changes_beyond_the_cap_keep_only_the_newest_decided_rows() = runBlocking {
        val n = MAX_CACHED_UPDATES.toLong()
        cache.storeBrowsed((1L..n).map { row(it) }) // full cache of already-seen rows
        val new = cache.applyChanges((n + 1..n + 50).map { row(it, notified = false) }, SyncState(n + 50, null, 1L))
        assertEquals(50, new.size)
        val stored = ids()
        assertTrue("the 50 pending rows are all kept", stored.containsAll((n + 1..n + 50).toList()))
        // The newest N (by createdAt, id) include the 50 pending ones, so 450 of the decided rows remain.
        assertEquals((51L..n + 50).toList(), stored)
    }

    @Test
    fun pending_rows_survive_the_cap_even_when_they_are_the_oldest() = runBlocking {
        val n = MAX_CACHED_UPDATES.toLong()
        // Old items whose notification decision was never taken (e.g. the notifier kept failing).
        cache.applyChanges((1L..10L).map { row(it, notified = false) }, SyncState(10, null, 1L))
        cache.storeBrowsed((11L..n + 100).map { row(it) })
        val stored = ids()
        assertTrue("pending rows are never trimmed", stored.containsAll((1L..10L).toList()))
        assertEquals((1L..10L).toList(), cache.pendingNotifications().map { it.id })
        assertEquals("cap plus the pending rows", n.toInt() + 10, stored.size)
        assertEquals(((101L)..(n + 100)).toList(), stored.filter { it > 10 })

        // Once decided they fall under the cap.
        cache.markNotified((1L..10L).toList())
        assertEquals(((101L)..(n + 100)).toList(), ids())
    }

    @Test
    fun feed_returns_at_most_the_cap_newest_first() = runBlocking {
        val n = MAX_CACHED_UPDATES.toLong()
        cache.applyChanges((1L..n + 30).map { row(it, notified = false) }, SyncState(n + 30, null, 1L))
        assertEquals("pending rows are all stored", (n + 30).toInt(), ids().size)
        val feed = cache.feed().first()
        assertEquals(MAX_CACHED_UPDATES, feed.size)
        assertEquals((n + 30 downTo 31L).toList(), feed.map { it.id })
    }
}
