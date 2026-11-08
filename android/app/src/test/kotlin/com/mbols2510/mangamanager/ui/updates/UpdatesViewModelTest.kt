package com.mbols2510.mangamanager.ui.updates

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.data.api.PairedCredentialsHolder
import com.mbols2510.mangamanager.data.db.AppDatabase
import com.mbols2510.mangamanager.data.db.RoomUpdatesCache
import com.mbols2510.mangamanager.data.db.SyncState
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.data.repo.DefaultUpdatesRepository
import com.mbols2510.mangamanager.data.repo.FeedRefreshResult
import com.mbols2510.mangamanager.work.PagedApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdatesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The ViewModel plus a collector standing in for the open screen: `state` is shared only while subscribed
     * (`WhileSubscribed`, final-review fix E), so reading `state.value` needs a subscriber, as on the device.
     */
    private fun TestScope.vm(repo: UpdatesRepository, credentials: PairedCredentialsHolder = PairedCredentialsHolder()) =
        UpdatesViewModel(repo, credentials::get).also { vm -> backgroundScope.launch { vm.state.collect {} } }

    private fun ids(vm: UpdatesViewModel) = vm.state.value.items.map { it.id }

    // ------------------------------------------------------------------ plan tests

    @Test
    fun refresh_called_once_on_open_not_on_a_timer() = runTest(dispatcher) {
        val repo = FakeFeedRepository(listOf(feedItem(1)))
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals("one refresh when the screen opens", 1, repo.refreshes)
        assertFalse(vm.state.value.refreshing)

        // Hours pass with the screen open, the state is read again and again: nothing polls.
        repeat(6) {
            advanceTimeBy(60 * 60 * 1_000L)
            runCurrent()
            vm.state.value
        }
        assertEquals("no timer-based refresh (spec 6)", 1, repo.refreshes)

        // A pull-to-refresh is the only other trigger.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, repo.refreshes)
    }

    @Test
    fun filters_apply_to_list() = runTest(dispatcher) {
        val repo = FakeFeedRepository(
            listOf(
                feedItem(3, title = "One Piece", category = "Manga", isNew = true),
                feedItem(2, title = "Berserk", category = "Seinen"),
                feedItem(1, title = "One Punch-Man", category = "Manga"),
            ),
        )
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals(listOf(3L, 2L, 1L), ids(vm))

        vm.setQuery("one")
        advanceUntilIdle()
        assertEquals(listOf(3L, 1L), ids(vm))
        assertEquals("one", vm.state.value.query)

        vm.setKind(KindFilter.New)
        advanceUntilIdle()
        assertEquals(listOf(3L), ids(vm))
        vm.setKind(KindFilter.Updated)
        advanceUntilIdle()
        assertEquals(listOf(1L), ids(vm))
        assertEquals(KindFilter.Updated, vm.state.value.kind)

        vm.setQuery("")
        vm.setKind(KindFilter.All)
        vm.setCategory("Seinen")
        advanceUntilIdle()
        assertEquals(listOf(2L), ids(vm))
        assertEquals("Seinen", vm.state.value.category)

        vm.setCategory(null)
        advanceUntilIdle()
        assertEquals(listOf(3L, 2L, 1L), ids(vm))
        assertEquals("filters are local: no extra network call", 1, repo.refreshes)
    }

    // ------------------------------------------------------------------ controller tests

    @Test
    fun cached_items_are_in_state_while_the_refresh_is_pending() = runTest(dispatcher) {
        val repo = FakeFeedRepository(listOf(feedItem(1, title = "Cached")))
        repo.gate = CompletableDeferred()
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals(listOf(1L), ids(vm))
        assertTrue(vm.state.value.refreshing)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun a_refresh_while_one_runs_does_not_start_a_second_request() = runTest(dispatcher) {
        val repo = FakeFeedRepository()
        repo.gate = CompletableDeferred()
        val vm = vm(repo)
        advanceUntilIdle()
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, repo.refreshes)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, repo.refreshes)
    }

    @Test
    fun search_is_case_insensitive_and_matches_titles_literally() = runTest(dispatcher) {
        val repo = FakeFeedRepository(
            listOf(
                feedItem(4, title = "Re:Zero (Light Novel)"),
                feedItem(3, title = "A.*B"),
                feedItem(2, title = "Café Terrace"),
                feedItem(1, title = "Zebra [Official]"),
            ),
        )
        val vm = vm(repo)
        advanceUntilIdle()
        fun search(q: String): List<Long> {
            vm.setQuery(q)
            dispatcher.scheduler.advanceUntilIdle()
            return ids(vm)
        }
        assertEquals(listOf(4L), search("RE:ZERO"))
        assertEquals(listOf(4L), search("("))
        assertEquals("regex characters are plain text", listOf(3L), search(".*"))
        assertEquals(listOf(1L), search("["))
        assertEquals(listOf(1L), search("[official]"))
        assertEquals("accents are optional", listOf(2L), search("cafe"))
        assertEquals(listOf(2L), search("CAFÉ"))
        assertEquals("surrounding spaces are ignored", listOf(4L), search("  zero "))
        assertEquals(emptyList<Long>(), search("\\"))
        assertEquals(listOf(4L, 3L, 2L, 1L), search("   "))
        // The pure function agrees.
        assertEquals(listOf(3L), filterUpdates(repo.feedState.value, ".*", KindFilter.All, null).map { it.id })
    }

    @Test
    fun category_filter_lists_only_present_categories() = runTest(dispatcher) {
        val repo = FakeFeedRepository(
            listOf(
                feedItem(4, category = "Seinen"),
                feedItem(3, category = "manhwa"),
                feedItem(2, category = "Manga"),
                feedItem(1, category = ""),
                feedItem(5, category = "Manga"),
            ),
        )
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals(listOf("Manga", "manhwa", "Seinen"), vm.state.value.categories)

        repo.feedState.value = listOf(feedItem(2, category = "Manga"))
        advanceUntilIdle()
        assertEquals(listOf("Manga"), vm.state.value.categories)
    }

    @Test
    fun refresh_error_is_shown_and_cleared_on_success() = runTest(dispatcher) {
        val repo = FakeFeedRepository(listOf(feedItem(1)))
        repo.next = FeedRefreshResult.Offline
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals(RefreshError.Offline, vm.state.value.error)

        repo.next = FeedRefreshResult.Ok
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun offline_refresh_keeps_cached_items() = runTest(dispatcher) {
        val repo = FakeFeedRepository(listOf(feedItem(2), feedItem(1)))
        repo.next = FeedRefreshResult.Offline
        val vm = vm(repo)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(listOf(2L, 1L), state.items.map { it.id })
        assertEquals(RefreshError.Offline, state.error)
        assertFalse(state.refreshing)
        assertFalse(state.cacheEmpty)
    }

    @Test
    fun unauthorized_refresh_stops_refreshing_without_a_stale_error() = runTest(dispatcher) {
        val auth = AuthState()
        val repo = FakeFeedRepository(listOf(feedItem(1)))
        repo.next = FeedRefreshResult.Offline
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals(RefreshError.Offline, vm.state.value.error)

        repo.next = FeedRefreshResult.Unauthorized
        repo.onRefresh = { auth.markUnauthorized() } // what the repository does on a 401
        vm.refresh()
        advanceUntilIdle()
        assertNull("the shell shows the pairing screen; no error survives a re-pair", vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
        assertTrue(auth.needsRepair.value)
    }

    @Test
    fun items_seen_after_a_refresh_are_marked_notified() = runTest(dispatcher) {
        val repo = FakeFeedRepository(listOf(feedItem(3, notified = false), feedItem(2), feedItem(1, notified = false)))
        vm(repo)
        advanceUntilIdle()
        assertEquals(listOf(1L, 3L), repo.marked.sorted())
    }

    @Test
    fun feed_is_collected_only_while_the_screen_subscribes() = runTest(dispatcher) {
        val repo = CountingFeedRepository(FakeFeedRepository(listOf(feedItem(1))))
        val vm = UpdatesViewModel(repo, { null })
        advanceUntilIdle() // the refresh on open reads the feed once (markSeenNotified) and lets go
        assertEquals("no subscriber, no collection of the Room feed", 0, repo.active)

        val screen = backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(1, repo.active)
        assertEquals(listOf(1L), vm.state.value.items.map { it.id })

        screen.cancel()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("kept for 5 s (a rotation does not re-query)", 1, repo.active)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("upstream stopped after the timeout", 0, repo.active)

        val starts = repo.starts
        repo.inner.feedState.value = listOf(feedItem(2), feedItem(1)) // a worker write while nobody looks
        advanceUntilIdle()
        assertEquals("no collection restarted by a background write", starts, repo.starts)
        assertEquals(0, repo.active)
    }

    @Test
    fun thumbnail_url_resolves_against_paired_base_and_never_when_unpaired() = runTest(dispatcher) {
        val base = "https://nas.example/manga/".toHttpUrl()
        assertEquals("https://nas.example/manga/thumb/7.png", resolveThumbnail(base, "thumb/7.png"))
        assertNull("not paired", resolveThumbnail(null, "thumb/7.png"))
        assertNull(resolveThumbnail(base, null))
        assertNull(resolveThumbnail(base, ""))
        assertNull("another origin", resolveThumbnail(base, "https://evil.example/thumb/7.png"))
        assertNull("scheme-relative to another host", resolveThumbnail(base, "//evil.example/thumb/7.png"))
        assertNull("another port", resolveThumbnail(base, "https://nas.example:8443/thumb/7.png"))
        assertNull("another scheme", resolveThumbnail(base, "http://nas.example/manga/thumb/7.png"))
        assertNull(resolveThumbnail(base, "javascript:alert(1)"))
        assertNull(resolveThumbnail(base, "file:///etc/passwd"))

        val holder = PairedCredentialsHolder()
        val repo = FakeFeedRepository()
        val vm = vm(repo, holder)
        advanceUntilIdle()
        val item = feedItem(7)
        assertNull("cold cache or unpaired: placeholder, no request", vm.thumbnailUrl(item))
        holder.set(base, "token")
        assertEquals("https://nas.example/manga/thumb/7.png", vm.thumbnailUrl(item))
        assertNull(vm.thumbnailUrl(item.copy(thumbnail = null)))
        holder.set("http://192.168.1.5:5000/".toHttpUrl(), "token2") // re-paired elsewhere: read at request time
        assertEquals("http://192.168.1.5:5000/thumb/7.png", vm.thumbnailUrl(item))
        holder.clear()
        assertNull(vm.thumbnailUrl(item))
    }

    @Test
    fun displayed_items_are_marked_notified_and_not_renotified_by_the_worker() = runBlocking {
        Dispatchers.resetMain()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val api = FeedApi(PagedApi(total = 2), browse = emptyList())
            val repo = DefaultUpdatesRepository({ api }, RoomUpdatesCache(db), AuthState())
            RoomUpdatesCache(db).setSyncState(SyncState(0, null, 1L))
            // A background check stored 1 and 2, but its notification decision never ran (process killed).
            assertTrue(repo.checkForChanges() is CheckResult.NewItems)
            assertEquals(listOf(1L, 2L), repo.pendingNotifications().map { it.id })

            val vm = UpdatesViewModel(repo, { null })
            val screen = CoroutineScope(Dispatchers.Default).launch { vm.state.collect {} } // the open screen
            withTimeout(5_000) {
                while (api.browseCalls == 0 || vm.state.value.refreshing || repo.pendingNotifications().isNotEmpty()) delay(10)
            }
            assertEquals(listOf(1L, 2L), vm.state.value.items.map { it.id }.sorted())
            screen.cancel()

            // The worker's next run: nothing left to decide, nothing new, so no notification for what the user saw.
            assertTrue(repo.pendingNotifications().isEmpty())
            assertEquals(CheckResult.NoChange, repo.checkForChanges())
            assertTrue(repo.pendingNotifications().isEmpty())
        } finally {
            db.close()
        }
    }
}
