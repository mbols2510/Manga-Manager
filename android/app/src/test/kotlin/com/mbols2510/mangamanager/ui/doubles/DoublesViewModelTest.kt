package com.mbols2510.mangamanager.ui.doubles

import com.mbols2510.mangamanager.data.api.DoubleChapterDto
import com.mbols2510.mangamanager.data.api.KavitaScanChapter
import com.mbols2510.mangamanager.data.api.KavitaScanRequest
import com.mbols2510.mangamanager.data.api.RemediateResultDto
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.DefaultServerToolsRepository
import com.mbols2510.mangamanager.data.repo.ToolsApi
import com.mbols2510.mangamanager.data.repo.chapter
import com.mbols2510.mangamanager.data.repo.errorResponse
import com.mbols2510.mangamanager.data.repo.file
import com.mbols2510.mangamanager.data.repo.httpError
import com.mbols2510.mangamanager.data.repo.series
import com.mbols2510.mangamanager.data.repo.successResponse
import com.mbols2510.mangamanager.data.repo.summaries
import com.mbols2510.mangamanager.data.repo.trashEntry
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real [DefaultServerToolsRepository] (status and exception mapping, AuthState) over a scripted [ToolsApi], all on
 * one test dispatcher (no real threads, no sleeps).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DoublesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = AuthState()
    private val api = ToolsApi()

    // Series 1 has chapter 10 (files 100 official, 101 newest) and chapter 11 (files 110, 111).
    private val full = series(
        1, "Berserk",
        chapter(10, file(100, "a.cbz", pages = 24, official = true), file(101, "b.cbz", pages = 22, newest = true)),
        chapter(11, file(110, "c.cbz"), file(111, "d.cbz")),
    )
    private val afterFirst = series(1, "Berserk", chapter(11, file(110, "c.cbz"), file(111, "d.cbz")))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api.seriesList = { summaries(full, series(2, "Vagabond", chapter(20, file(200, "e.cbz"), file(201, "f.cbz")))) }
        api.detail = { id -> if (id == 1L) full else throw httpError(404) }
        api.trashList = { listOf(trashEntry("t1")) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = DoublesViewModel(DefaultServerToolsRepository({ api }, auth, io = dispatcher))

    private fun kotlinx.coroutines.test.TestScope.openFirst(): DoublesViewModel {
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        api.calls.clear()
        return vm
    }

    // ------------------------------------------------------------------ plan tests

    @Test
    fun remediate_requires_explicit_confirmation_step() = runTest(dispatcher) {
        val vm = openFirst()
        assertEquals("the default keeps the official file", 100L, vm.state.value.selection[10])

        vm.remediate(1, 10, 100)
        advanceUntilIdle()
        assertEquals("nothing is sent before confirm()", 0, api.count("remediate"))
        val pending = vm.state.value.confirm as PendingConfirm.ConfirmRemediate
        assertEquals(100L, pending.keep.id)
        assertEquals(listOf(101L), pending.trash.map { it.id })
        assertEquals("Berserk", pending.seriesName)
        assertEquals(10L, pending.chapter.id)

        vm.dismissConfirm()
        advanceUntilIdle()
        assertNull(vm.state.value.confirm)
        vm.confirm() // no pending confirmation any more: nothing happens
        advanceUntilIdle()
        assertEquals("a dismissed confirmation never sends", 0, api.count("remediate"))

        vm.remediate(1, 10, 101)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("remediate:1:10:101"), api.calls.filter { it.startsWith("remediate") })
        assertNull(vm.state.value.confirm)
    }

    @Test
    fun remediate_failure_keeps_list_unchanged_and_shows_error() = runTest(dispatcher) {
        val vm = openFirst()
        val before = vm.state.value
        api.remediateHandler = { _, _, _ -> errorResponse(400, """{"error":"keepFileId does not match any file on this chapter"}""") }

        vm.remediate(1, 10, 100)
        vm.confirm()
        advanceUntilIdle()

        val after = vm.state.value
        assertEquals(before.detail, after.detail)
        assertEquals(before.series, after.series)
        assertEquals(before.trash, after.trash)
        assertEquals(DoublesMessage.ServerError(400, "keepFileId does not match any file on this chapter"), after.message)
        // Fix round 1 (ruling 24, 2c): a clean 400/404 re-reads the series detail (the chapter is probably gone). The
        // server's detail is unchanged here, so the screen still shows exactly what it showed before.
        assertEquals("a clean 4xx re-reads only the detail", listOf("remediate:1:10:100", "detail:1"), api.calls)
        assertFalse(after.busy)
        assertFalse(auth.needsRepair.value)
    }

    @Test
    fun trash_restore_refreshes_list() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.state.value.trash.map { it.id })
        api.calls.clear()
        api.trashList = { emptyList() }

        vm.restore("t1")
        vm.confirm()
        advanceUntilIdle()

        assertEquals(listOf("restore:t1", "series", "trash"), api.calls)
        assertTrue(vm.state.value.trash.isEmpty())
        assertEquals(DoublesMessage.Restored, vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    // ------------------------------------------------------------------ own tests

    @Test
    fun second_remediate_tap_while_in_flight_is_ignored() = runTest(dispatcher) {
        val vm = openFirst()
        api.gate = CompletableDeferred()

        vm.remediate(1, 10, 100)
        vm.confirm()
        advanceUntilIdle()
        assertTrue("busy while the call is in flight", vm.state.value.busy)

        vm.confirm()
        vm.remediate(1, 11, 110) // a second keep/trash tap
        vm.confirm()
        advanceUntilIdle()
        assertNull("no second confirmation while busy", vm.state.value.confirm)
        assertEquals(1, api.count("remediate"))

        api.detail = { afterFirst }
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.count("remediate"))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun remediate_timeout_is_reported_as_unknown_outcome_and_refetches_detail() = runTest(dispatcher) {
        val vm = openFirst()
        api.remediateHandler = { _, _, _ -> throw SocketTimeoutException("timeout") }
        api.detail = { afterFirst } // the server did apply it

        vm.remediate(1, 10, 100)
        vm.confirm()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(DoublesMessage.MaybeApplied, s.message)
        assertEquals("the detail is re-read after an unknown outcome", 1, api.count("detail:1"))
        assertEquals(listOf(11L), s.detail!!.chapters.map { it.id })
        assertEquals(1, api.count("series"))
        assertFalse(s.unreachable)
        assertFalse(s.busy)
    }

    @Test
    fun remediate_success_refreshes_detail_and_list() = runTest(dispatcher) {
        val vm = openFirst()
        api.remediateHandler = { _, _, _ -> successResponse(200, RemediateResultDto(listOf(trashEntry("t2")), 1)) }
        api.detail = { afterFirst }
        api.seriesList = { summaries(afterFirst) }
        api.trashList = { listOf(trashEntry("t2"), trashEntry("t1")) }

        vm.remediate(1, 10, 100)
        vm.confirm()
        advanceUntilIdle()

        assertEquals(listOf("remediate:1:10:100", "detail:1", "series", "trash"), api.calls)
        val s = vm.state.value
        assertEquals(DoublesMessage.Remediated(1), s.message)
        assertEquals(listOf(11L), s.detail!!.chapters.map { it.id })
        assertEquals(1, s.series.single().chapterCount)
        assertEquals(listOf("t2", "t1"), s.trash.map { it.id })

        // The last chapter handled: the server forgets the series (404), the screen goes back to the list.
        api.detail = { throw httpError(404) }
        vm.remediate(1, 11, 110)
        vm.confirm()
        advanceUntilIdle()
        assertNull(vm.state.value.openSeriesId)
        assertNull(vm.state.value.detail)
    }

    @Test
    fun trash_restore_requires_confirmation() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        api.calls.clear()

        vm.restore("t1")
        advanceUntilIdle()
        assertEquals(PendingConfirm.ConfirmRestore(trashEntry("t1")), vm.state.value.confirm)
        assertEquals("nothing is sent before confirm()", 0, api.count("restore"))

        vm.dismissConfirm()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(0, api.count("restore"))

        vm.restore("unknown-id") // not in the list: no confirmation, nothing sent
        assertNull(vm.state.value.confirm)
    }

    @Test
    fun unauthorized_on_any_call_sets_auth_state_and_stops_spinners() = runTest(dispatcher) {
        // On the first load.
        api.seriesList = { throw httpError(401) }
        val vm = vm()
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.unreachable)
        assertNull(vm.state.value.message)

        // On the detail and on a changing call.
        auth.clear()
        api.seriesList = { summaries(full) }
        vm.refresh()
        advanceUntilIdle()
        api.detail = { throw httpError(401) }
        vm.openSeries(1)
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.loadingDetail)

        auth.clear()
        api.detail = { full }
        vm.openSeries(1)
        advanceUntilIdle()
        api.remediateHandler = { _, _, _ -> errorResponse(401) }
        vm.remediate(1, 10, 100)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.message)

        // Restore, too.
        auth.clear()
        api.restoreHandler = { errorResponse(401) }
        vm.closeSeries()
        vm.restore("t1")
        vm.confirm()
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun cancelled_call_does_not_leave_the_screen_busy() = runTest(dispatcher) {
        val vm = openFirst()
        api.remediateHandler = { _, _, _ -> throw CancellationException("call cancelled") }
        vm.remediate(1, 10, 100)
        vm.confirm()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)

        api.seriesList = { throw CancellationException("load cancelled") }
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)

        // The screen still works afterwards.
        api.remediateHandler = { _, _, _ -> successResponse(200, RemediateResultDto(emptyList(), 1)) }
        api.seriesList = { summaries(full) }
        vm.remediate(1, 10, 100)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(2, api.count("remediate"))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun empty_states_for_no_duplicates_and_empty_trash() = runTest(dispatcher) {
        api.seriesList = { emptyList() }
        api.trashList = { emptyList() }
        val vm = vm()
        assertFalse("no empty state before the first answer", vm.state.value.loaded)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.loaded)
        assertTrue(s.series.isEmpty())
        assertTrue(s.trash.isEmpty())
        assertFalse(s.unreachable)
        assertFalse(s.loading)
    }

    @Test
    fun unreachable_server_on_open_shows_unreachable_and_retry_loads() = runTest(dispatcher) {
        api.seriesList = { throw IOException("no route") }
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.unreachable)
        assertFalse(vm.state.value.loaded)
        assertEquals("one failed request is enough", listOf("series"), api.calls)

        api.seriesList = { summaries(full) }
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.unreachable)
        assertEquals(listOf(1L), vm.state.value.series.map { it.id })
    }

    // ------------------------------------------------------------------ fix round 1: Kavita rescan

    /** A server whose series 1 loses each remediated chapter; 404 once none is left. */
    private fun liveSeries(vararg chapters: DoubleChapterDto): MutableList<DoubleChapterDto> {
        val remaining = chapters.toMutableList()
        api.detail = { id ->
            if (id == 1L && remaining.isNotEmpty()) series(1, "Berserk", *remaining.toTypedArray()) else throw httpError(404)
        }
        api.seriesList = { if (remaining.isEmpty()) emptyList() else summaries(series(1, "Berserk", *remaining.toTypedArray())) }
        api.remediateHandler = { _, chapterId, _ ->
            remaining.removeAll { it.id == chapterId }
            successResponse(200, RemediateResultDto(listOf(trashEntry("t$chapterId")), 1))
        }
        return remaining
    }

    private val ch10 = chapter(10, file(100, "a.cbz", pages = 24, official = true), file(101, "b.cbz", pages = 22))
    private val ch11 = chapter(11, file(110, "c.cbz", pages = 20), file(111, "d.cbz", pages = 19))
    private val ch12 = chapter(12, file(120, "e.cbz", pages = 30), file(121, "f.cbz", pages = 31))

    private fun DoublesViewModel.keep(chapterId: Long, fileId: Long) {
        remediate(1, chapterId, fileId)
        confirm()
    }

    private fun kavitaCalls() = api.calls.filter { it.startsWith("kavita") }

    @Test
    fun kavita_scan_sent_once_when_leaving_the_series_with_all_remediated_chapters() = runTest(dispatcher) {
        liveSeries(ch10, ch11, ch12)
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.keep(10, 100)
        advanceUntilIdle()
        vm.keep(11, 111)
        advanceUntilIdle()
        assertEquals("nothing is sent while the user stays in the series", emptyList<String>(), kavitaCalls())

        vm.closeSeries()
        advanceUntilIdle()
        assertEquals(listOf("kavita:1:lib=1:[10=24,11=19]"), kavitaCalls())
        assertTrue(vm.state.value.pendingScans.isEmpty())
        assertEquals(KavitaNotice.Done(1), vm.state.value.kavita)

        vm.openSeries(1)
        advanceUntilIdle()
        vm.closeSeries()
        advanceUntilIdle()
        assertEquals("nothing pending: no second scan", 1, kavitaCalls().size)
    }

    @Test
    fun kavita_scan_also_sent_when_no_duplicates_are_left() = runTest(dispatcher) {
        liveSeries(ch10)
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.keep(10, 101)
        advanceUntilIdle()
        assertNull("the series is gone (404): back to the list", vm.state.value.openSeriesId)
        assertEquals(listOf("kavita:1:lib=1:[10=22]"), kavitaCalls())
    }

    @Test
    fun kavita_scan_body_carries_library_id_chapter_ids_and_kept_pages() = runTest(dispatcher) {
        liveSeries(ch10, ch11, ch12)
        api.detail = { id -> if (id == 1L) series(1, "Berserk", ch10, ch11, ch12).copy(libraryId = 7) else throw httpError(404) }
        api.remediateHandler = { _, _, _ -> successResponse(200, RemediateResultDto(emptyList(), null)) }
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.keep(12, 121)
        advanceUntilIdle()
        vm.closeSeries()
        advanceUntilIdle()
        assertEquals(
            listOf(1L to KavitaScanRequest(libraryId = 7, chapters = listOf(KavitaScanChapter(12, 31)))),
            api.kavitaRequests,
        )
    }

    @Test
    fun kavita_scan_pending_list_survives_a_failed_scan_and_is_cleared_on_2xx() = runTest(dispatcher) {
        liveSeries(ch10, ch11)
        api.kavitaHandler = { _, _ -> errorResponse(502, """{"error":"Kavita answered 500"}""") }
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.keep(10, 100)
        advanceUntilIdle()
        vm.closeSeries()
        advanceUntilIdle()
        assertEquals(KavitaNotice.Failed(1, 502, "Kavita answered 500"), vm.state.value.kavita)
        assertEquals(listOf(KavitaScanChapter(10, 24)), vm.state.value.pendingScans.getValue(1).chapters)
        assertEquals("no automatic retry", 1, kavitaCalls().size)
        assertFalse("a failed scan blocks nothing", vm.state.value.busy)

        api.kavitaHandler = { _, _ -> successResponse(200, com.mbols2510.mangamanager.data.api.KavitaScanResultDto(true)) }
        vm.rescanKavita(1)
        advanceUntilIdle()
        assertEquals(listOf("kavita:1:lib=1:[10=24]", "kavita:1:lib=1:[10=24]"), kavitaCalls())
        assertTrue(vm.state.value.pendingScans.isEmpty())
        vm.rescanKavita(1)
        advanceUntilIdle()
        assertEquals(2, kavitaCalls().size)
    }

    @Test
    fun kavita_scan_timeout_is_reported_as_maybe_applied_with_retry() = runTest(dispatcher) {
        liveSeries(ch10, ch11)
        api.kavitaHandler = { _, _ -> throw SocketTimeoutException("timeout") }
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.keep(10, 100)
        advanceUntilIdle()
        vm.closeSeries()
        advanceUntilIdle()
        assertEquals(KavitaNotice.MaybeApplied(1), vm.state.value.kavita)
        assertTrue("kept for the retry", vm.state.value.pendingScans.containsKey(1))
        assertEquals(1, kavitaCalls().size)

        api.kavitaHandler = { _, _ -> successResponse(200, com.mbols2510.mangamanager.data.api.KavitaScanResultDto(true)) }
        vm.rescanKavita(1) // the banner's Retry
        advanceUntilIdle()
        assertEquals(2, kavitaCalls().size)
        assertEquals(KavitaNotice.Done(1), vm.state.value.kavita)
    }

    @Test
    fun kavita_scan_not_sent_when_nothing_was_remediated() = runTest(dispatcher) {
        liveSeries(ch10, ch11)
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.closeSeries()
        advanceUntilIdle()

        // A clean 4xx and a not-sent call change nothing, so nothing is pending either.
        vm.openSeries(1)
        advanceUntilIdle()
        api.remediateHandler = { _, _, _ -> errorResponse(400) }
        vm.keep(10, 100)
        advanceUntilIdle()
        api.remediateHandler = { _, _, _ -> throw java.net.ConnectException("refused") }
        vm.keep(10, 100)
        advanceUntilIdle()
        vm.rescanKavita()
        vm.closeSeries()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), kavitaCalls())
        assertTrue(vm.state.value.pendingScans.isEmpty())
        assertNull(vm.state.value.kavita)
    }

    @Test
    fun closing_the_series_does_not_cancel_the_scan() = runTest(dispatcher) {
        liveSeries(ch10, ch11)
        api.kavitaGate = CompletableDeferred()
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.keep(10, 100)
        advanceUntilIdle()
        vm.rescanKavita() // the button, while the series is open
        runCurrent()
        assertEquals(setOf(1L), vm.state.value.scanning)
        assertFalse("the scan never blocks the screen", vm.state.value.busy)

        vm.closeSeries() // leaving starts no second scan while one is in flight
        vm.openSeries(2)
        advanceUntilIdle()
        assertEquals(1, kavitaCalls().size)

        api.kavitaGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(KavitaNotice.Done(1), vm.state.value.kavita)
        assertTrue(vm.state.value.pendingScans.isEmpty())
        assertTrue(vm.state.value.scanning.isEmpty())
        assertEquals(1, kavitaCalls().size)
    }

    @Test
    fun kavita_scan_401_sets_auth_state() = runTest(dispatcher) {
        liveSeries(ch10, ch11)
        api.kavitaHandler = { _, _ -> errorResponse(401) }
        val vm = vm()
        advanceUntilIdle()
        vm.openSeries(1)
        advanceUntilIdle()
        vm.keep(10, 100)
        advanceUntilIdle()
        vm.closeSeries()
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertNull(vm.state.value.kavita)
        assertTrue(vm.state.value.scanning.isEmpty())
        assertTrue("still pending after the re-pair", vm.state.value.pendingScans.containsKey(1))
    }

    // ------------------------------------------------------------------ fix round 1: hardening

    @Test
    fun stale_confirm_cannot_be_confirmed_after_the_detail_changed() = runTest(dispatcher) {
        val vm = openFirst()
        vm.remediate(1, 10, 100)
        assertTrue(vm.state.value.confirm is PendingConfirm.ConfirmRemediate)

        // The detail is re-read and chapter 10 is gone: the staged confirmation is dropped.
        api.detail = { afterFirst }
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.confirm)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(0, api.count("remediate"))

        // Staged, then the files of the chapter change behind it: confirm() refuses the stale dialog.
        val changed = series(1, "Berserk", chapter(11, file(110, "c.cbz"), file(112, "new.cbz")))
        vm.remediate(1, 11, 110)
        val staged = vm.state.value.confirm as PendingConfirm.ConfirmRemediate
        assertFalse(stillMatches(staged, changed))
        assertTrue(stillMatches(staged, afterFirst))

        // Opening or closing a series clears it too.
        vm.closeSeries()
        assertNull(vm.state.value.confirm)
        vm.openSeries(1)
        advanceUntilIdle()
        vm.remediate(1, 11, 110)
        vm.openSeries(1)
        assertNull(vm.state.value.confirm)
        advanceUntilIdle()
        assertEquals(0, api.count("remediate"))
    }

    @Test
    fun remediate_404_refetches_detail_and_5xx_is_an_unknown_outcome() = runTest(dispatcher) {
        val vm = openFirst()
        api.remediateHandler = { _, _, _ -> errorResponse(404, """{"error":"Chapter not found in current scan results"}""") }
        api.detail = { afterFirst }
        vm.keep(10, 100)
        advanceUntilIdle()
        assertEquals(DoublesMessage.ServerError(404, "Chapter not found in current scan results"), vm.state.value.message)
        assertEquals(listOf(11L), vm.state.value.detail!!.chapters.map { it.id })
        assertTrue("a clean 4xx records nothing for Kavita", vm.state.value.pendingScans.isEmpty())

        api.calls.clear()
        api.remediateHandler = { _, _, _ -> errorResponse(500, """{"error":"boom"}""") }
        vm.keep(11, 110)
        advanceUntilIdle()
        assertEquals(DoublesMessage.MaybeApplied, vm.state.value.message)
        assertEquals(listOf("remediate:1:11:110", "detail:1", "series", "trash"), api.calls)
        assertEquals(listOf(KavitaScanChapter(11, 20)), vm.state.value.pendingScans.getValue(1).chapters)
    }

    @Test
    fun folder_and_name_tells_same_named_files_apart() {
        assertEquals("Official/Ch. 12.cbz", folderAndName("/manga/Berserk/Official/Ch. 12.cbz"))
        assertEquals("Scanlation/Ch. 12.cbz", folderAndName("/manga/Berserk/Scanlation/Ch. 12.cbz"))
        assertEquals("a.cbz", folderAndName("a.cbz"))
        assertEquals("dir/b.cbz", folderAndName("C:\\lib\\dir\\b.cbz"))
        assertEquals("", folderAndName(null))
    }
}
