package com.mbols2510.mangamanager.ui.jobs

import com.mbols2510.mangamanager.data.api.HealthCheckDto
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.RunDto
import com.mbols2510.mangamanager.data.api.SchedulerStatusDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.DefaultServerToolsRepository
import com.mbols2510.mangamanager.data.repo.ToolsApi
import com.mbols2510.mangamanager.data.repo.errorResponse
import com.mbols2510.mangamanager.data.repo.httpError
import com.mbols2510.mangamanager.data.repo.successResponse
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = AuthState()
    private val api = ToolsApi()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api.statusHandler = { StatusDto(updates = 3, scheduler = SchedulerStatusDto(running = false, intervalMinutes = 60)) }
        api.healthHandler = { HealthDto(version = "2.4.0", checks = listOf(HealthCheckDto("Database", "ok", "connected", 3))) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = JobsViewModel(DefaultServerToolsRepository({ api }, auth, io = dispatcher))

    // ------------------------------------------------------------------ plan tests

    @Test
    fun run_now_202_409_403_map_to_distinct_messages() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val seen = mutableListOf<RunMessage?>()

        api.runHandler = { successResponse(202, RunDto(true, "Run started")) }
        vm.runNow()
        advanceUntilIdle()
        seen += vm.state.value.runMessage

        api.runHandler = { errorResponse(409, """{"started":false,"message":"A run is already in progress"}""") }
        vm.runNow()
        advanceUntilIdle()
        seen += vm.state.value.runMessage

        // Flask's abort(403) answers with an HTML page, not JSON.
        api.runHandler = { errorResponse(403, "<!doctype html><title>403 Forbidden</title>") }
        vm.runNow()
        advanceUntilIdle()
        seen += vm.state.value.runMessage

        assertEquals(listOf(RunMessage.Started, RunMessage.AlreadyRunning, RunMessage.Disabled), seen)
        assertEquals("three distinct messages", 3, seen.toSet().size)
        assertEquals(3, api.count("run"))
        assertFalse(vm.state.value.runInFlight)
        assertFalse("a 403 is not a revoked token", auth.needsRepair.value)
    }

    @Test
    fun health_failure_shows_unreachable_not_crash() = runTest(dispatcher) {
        api.healthHandler = { throw IOException("connection reset") }
        val vm = vm()
        advanceUntilIdle()
        var s = vm.state.value
        assertTrue(s.unreachable)
        assertNull(s.health)
        assertNotNull("the status that did load is kept", s.status)
        assertFalse(s.refreshing)

        // The whole server unreachable: the status call fails first, health is not even tried.
        api.calls.clear()
        api.statusHandler = { throw ConnectException("refused") }
        vm.refresh()
        advanceUntilIdle()
        s = vm.state.value
        assertTrue(s.unreachable)
        assertEquals(listOf("status"), api.calls)
        assertFalse(s.refreshing)

        // Retry once the server is back.
        api.statusHandler = { StatusDto(updates = 4) }
        api.healthHandler = { HealthDto(checks = listOf(HealthCheckDto("Kavita", "fail", "HTTP 502", 10))) }
        vm.refresh()
        advanceUntilIdle()
        s = vm.state.value
        assertFalse(s.unreachable)
        assertEquals("fail", s.health!!.checks.single().state)
        assertEquals(4, s.status!!.updates)
    }

    // ------------------------------------------------------------------ own tests

    @Test
    fun jobs_screen_refreshes_once_on_open_not_on_a_timer() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, api.count("status"))
        assertEquals(1, api.count("health"))

        repeat(6) {
            advanceTimeBy(60 * 60 * 1_000L)
            runCurrent()
            vm.state.value
        }
        assertEquals("no timer-based refresh (spec 6)", 1, api.count("status"))
        assertEquals(1, api.count("health"))

        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, api.count("status"))
        assertEquals(2, api.count("health"))
    }

    @Test
    fun run_started_rereads_the_status_once_after_a_delay_and_never_again() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        api.calls.clear()
        api.statusHandler = { StatusDto(updates = 3, scheduler = SchedulerStatusDto(running = true)) }

        vm.runNow()
        runCurrent()
        assertEquals(listOf("run"), api.calls)
        advanceTimeBy(JobsViewModel.STATUS_RECHECK_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf("run", "status"), api.calls)
        assertTrue(vm.state.value.status!!.scheduler!!.running)

        advanceTimeBy(24 * 60 * 60 * 1_000L)
        runCurrent()
        assertEquals("one re-read, no polling", listOf("run", "status"), api.calls)

        // 409 and 403 do not re-read anything.
        api.calls.clear()
        api.runHandler = { errorResponse(409) }
        vm.runNow()
        advanceUntilIdle()
        assertEquals(listOf("run"), api.calls)
    }

    @Test
    fun run_now_timeout_says_maybe_started_and_other_failures_are_distinct() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        api.runHandler = { throw SocketTimeoutException("timeout") }
        vm.runNow()
        advanceUntilIdle()
        assertEquals(RunMessage.MaybeStarted, vm.state.value.runMessage)

        api.runHandler = { throw ConnectException("refused") }
        vm.runNow()
        advanceUntilIdle()
        assertEquals(RunMessage.Unreachable, vm.state.value.runMessage)

        api.runHandler = { errorResponse(500, """{"error":"boom"}""") }
        vm.runNow()
        advanceUntilIdle()
        assertEquals(RunMessage.Failed(500, "boom"), vm.state.value.runMessage)
    }

    @Test
    fun second_run_now_tap_while_in_flight_is_ignored() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        api.gate = CompletableDeferred()
        vm.runNow()
        runCurrent()
        assertTrue(vm.state.value.runInFlight)
        vm.runNow()
        vm.runNow()
        runCurrent()
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.count("run"))
        assertFalse(vm.state.value.runInFlight)
    }

    @Test
    fun unauthorized_on_any_call_sets_auth_state_and_stops_spinners() = runTest(dispatcher) {
        api.statusHandler = { throw httpError(401) }
        val vm = vm()
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.refreshing)
        assertFalse(vm.state.value.unreachable)
        assertEquals("health is not asked after a 401", 0, api.count("health"))

        auth.clear()
        api.statusHandler = { StatusDto() }
        api.healthHandler = { throw httpError(401) }
        vm.refresh()
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.refreshing)

        auth.clear()
        api.runHandler = { errorResponse(401) }
        vm.runNow()
        advanceUntilIdle()
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.runInFlight)
        assertNull(vm.state.value.runMessage)
    }

    @Test
    fun cancelled_call_does_not_leave_the_screen_busy() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        api.runHandler = { throw CancellationException("cancelled") }
        vm.runNow()
        advanceUntilIdle()
        assertFalse(vm.state.value.runInFlight)

        api.statusHandler = { throw CancellationException("cancelled") }
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)

        api.runHandler = { successResponse(202, RunDto(true)) }
        vm.runNow()
        advanceUntilIdle()
        assertEquals(RunMessage.Started, vm.state.value.runMessage)
    }

    @Test
    fun long_server_text_is_capped() {
        val long = "x".repeat(5_000)
        val capped = com.mbols2510.mangamanager.ui.common.boundedServerText(long)
        assertEquals(com.mbols2510.mangamanager.ui.common.MAX_SERVER_TEXT_CHARS, capped.length)
        assertTrue(capped.endsWith("…"))
        assertEquals("short", com.mbols2510.mangamanager.ui.common.boundedServerText("  short \n"))
    }

    private fun assertNoLoneSurrogate(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("high surrogate at $i is followed by a low one", i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
                i += 2
            } else {
                assertFalse("lone low surrogate at $i", Character.isLowSurrogate(c))
                i++
            }
        }
    }

    @Test
    fun capping_never_splits_an_emoji() {
        val cap = com.mbols2510.mangamanager.ui.common.MAX_SERVER_TEXT_CHARS
        // The emoji's two UTF-16 units straddle the cut (units cap-2 and cap-1).
        val boundary = "x".repeat(cap - 2) + "\uD83D\uDE00" + "yyy"
        val cut = com.mbols2510.mangamanager.ui.common.boundedServerText(boundary)
        assertNoLoneSurrogate(cut)
        assertTrue(cut.length <= cap)
        assertTrue(cut.endsWith("…"))

        val onlyEmoji = "\uD83D\uDE00".repeat(400)
        val cutEmoji = com.mbols2510.mangamanager.ui.common.boundedServerText(onlyEmoji)
        assertNoLoneSurrogate(cutEmoji)
        assertTrue(cutEmoji.length <= cap)
        assertTrue(cutEmoji.endsWith("…"))
        assertEquals("whole emoji only", 0, cutEmoji.dropLast(1).length % 2)
    }
}
