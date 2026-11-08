package com.mbols2510.mangamanager.ui.pairing

import androidx.lifecycle.viewModelScope
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.api.LatestDto
import com.mbols2510.mangamanager.data.api.PairedCredentialsHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tokens = FakeTokenStore()
    private val settings = FakeSettingsStore()
    private val auth = AuthState()
    private val server = FakeServer(validToken = TOKEN)
    private var baselineFailure: Exception? = null
    private var storedAtBaseline: Pair<String?, String?>? = null
    private var vmRef: PairingViewModel? = null
    private var stateAtBaseline: PairingUiState? = null
    private val repo = FakeUpdatesRepository {
        storedAtBaseline = tokens.token to settings.serverUrl()
        stateAtBaseline = vmRef?.state?.value
        baselineFailure?.let { throw it }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PairingViewModel(tokens, settings, auth, repo, PairedCredentialsHolder(), server, dispatcher).also { vmRef = it }

    /** Records every state the ViewModel emits. */
    private fun TestScope.recorded(vm: PairingViewModel): MutableList<PairingUiState> {
        val states = mutableListOf<PairingUiState>()
        backgroundScope.launch(dispatcher) { vm.state.collect { states += it } }
        return states
    }

    private fun assertNothingStored() {
        assertNull("token must not be stored", tokens.token)
        assertEquals("token must never be saved", 0, tokens.saves)
        assertNull("URL must not be stored", runBlocking { settings.serverUrl() })
        assertEquals("no baseline", 0, repo.baselines)
    }

    @Test
    fun valid_code_validates_stores_token_and_sets_baseline() = runTest(dispatcher) {
        auth.markUnauthorized() // re-pair after a revoked token
        val vm = viewModel()

        vm.submit(pairingCode("https://manga.example.com", TOKEN))

        assertEquals(PairingUiState.Success, vm.state.value)
        assertEquals("Validating shown while working", PairingUiState.Validating, stateAtBaseline)
        assertEquals(TOKEN, tokens.token)
        assertEquals("https://manga.example.com/", settings.serverUrl())
        assertEquals(1, repo.baselines)
        assertEquals("baseline runs after token and URL are stored", TOKEN to "https://manga.example.com/", storedAtBaseline)
        assertFalse("a successful pairing clears needsRepair", auth.needsRepair.value)
        assertEquals("probe without a token, then validation with the candidate",
            listOf(null, TOKEN), server.calls.map { it.second })
        assertEquals("https://manga.example.com/".toHttpUrl(), server.calls.last().first)
    }

    @Test
    fun successful_pairing_clears_the_persisted_repair_flag_then_schedules_checks() = runTest(dispatcher) {
        auth.markUnauthorized()
        settings.setNeedsRepair(true)
        var repairFlagWhenScheduled: Boolean? = null
        val vm = PairingViewModel(
            tokens, settings, auth, repo, PairedCredentialsHolder(), server, dispatcher,
            afterPaired = { repairFlagWhenScheduled = settings.needsRepair() },
        )

        vm.submit(pairingCode("https://manga.example.com", TOKEN))

        assertEquals(PairingUiState.Success, vm.state.value)
        assertFalse(settings.needsRepair())
        assertEquals("scheduled once, after the flag was cleared", false, repairFlagWhenScheduled)
    }

    @Test
    fun failed_pairing_keeps_the_persisted_repair_flag_and_schedules_nothing() = runTest(dispatcher) {
        settings.setNeedsRepair(true)
        baselineFailure = IOException("offline")
        var scheduled = 0
        val vm = PairingViewModel(
            tokens, settings, auth, repo, PairedCredentialsHolder(), server, dispatcher,
            afterPaired = { scheduled++ },
        )

        vm.submit(pairingCode("https://manga.example.com", TOKEN))

        assertEquals(PairingUiState.Error(R.string.pairing_error_store), vm.state.value)
        assertTrue(settings.needsRepair())
        assertEquals(0, scheduled)
    }

    @Test
    fun invalid_code_shows_error_and_stores_nothing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.submit("this is not a pairing code")

        assertEquals(PairingUiState.Error(R.string.pairing_error_invalid_code), vm.state.value)
        assertNothingStored()
        assertTrue("no network call for an unparseable code", server.calls.isEmpty())
    }

    @Test
    fun rejected_url_shows_reason() = runTest(dispatcher) {
        val vm = viewModel()
        vm.submit(pairingCode("http://manga.example.com", TOKEN))

        val state = vm.state.value as PairingUiState.Error
        assertEquals(R.string.pairing_error_rejected_url, state.message)
        assertTrue("the UrlPolicy reason is shown: ${state.detail}", state.detail!!.contains("Plain HTTP"))
        assertNothingStored()
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun server_401_during_validation_shows_error_and_clears_token() = runTest(dispatcher) {
        server.validToken = "some-other-token"
        val vm = viewModel()
        vm.submit(pairingCode("https://manga.example.com", TOKEN))

        assertEquals(PairingUiState.Error(R.string.pairing_error_rejected_code), vm.state.value)
        assertNothingStored()
        assertEquals(2, server.calls.size)
    }

    @Test
    fun auth_disabled_server_refused_by_default() = runTest(dispatcher) {
        server.authDisabled = true
        val vm = viewModel()
        vm.submit(pairingCode("http://192.168.1.5:5000", TOKEN))

        assertEquals(PairingUiState.ConfirmAuthDisabled("192.168.1.5"), vm.state.value)
        assertNothingStored()
        assertEquals("only the probe ran", listOf<String?>(null), server.calls.map { it.second })

        vm.dismissAuthDisabled()
        assertEquals(PairingUiState.Error(R.string.pairing_error_auth_disabled), vm.state.value)
        vm.confirmAuthDisabled() // too late: the candidate was dropped
        assertEquals(PairingUiState.Error(R.string.pairing_error_auth_disabled), vm.state.value)
        assertNothingStored()
        assertEquals(1, server.calls.size)
    }

    @Test
    fun auth_disabled_override_only_offered_on_private_host() = runTest(dispatcher) {
        server.authDisabled = true
        val vm = viewModel()

        vm.submit(pairingCode("https://manga.example.com", TOKEN))
        assertEquals("public host: refused, no override",
            PairingUiState.Error(R.string.pairing_error_auth_disabled), vm.state.value)

        vm.submit(pairingCode("http://192.168.1.5:5000", TOKEN))
        assertEquals(PairingUiState.ConfirmAuthDisabled("192.168.1.5"), vm.state.value)
        vm.confirmAuthDisabled()

        assertEquals(PairingUiState.Success, vm.state.value)
        assertEquals(TOKEN, tokens.token)
        assertEquals("http://192.168.1.5:5000/", settings.serverUrl())
        assertEquals(1, repo.baselines)
        assertEquals("the candidate token is still validated after the override",
            TOKEN, server.calls.last().second)
    }

    @Test
    fun public_host_auth_disabled_cannot_be_overridden() = runTest(dispatcher) {
        server.authDisabled = true
        val vm = viewModel()
        vm.submit(pairingCode("https://manga.example.com", TOKEN))
        assertEquals(PairingUiState.Error(R.string.pairing_error_auth_disabled), vm.state.value)

        vm.confirmAuthDisabled()

        assertEquals(PairingUiState.Error(R.string.pairing_error_auth_disabled), vm.state.value)
        assertNothingStored()
        assertEquals("no validation call", 1, server.calls.size)
    }

    @Test
    fun baseline_failure_rolls_back_token_and_url() = runTest(dispatcher) {
        auth.markUnauthorized() // a failed re-pair must leave the device flagged for repair
        baselineFailure = IOException("offline")
        val vm = viewModel()
        vm.submit(pairingCode("https://manga.example.com", TOKEN))

        assertEquals(PairingUiState.Error(R.string.pairing_error_store), vm.state.value)
        assertEquals(1, repo.baselines)
        assertEquals("token and URL were stored before the baseline", TOKEN to "https://manga.example.com/", storedAtBaseline)
        assertNull("token rolled back", tokens.token)
        assertNull("URL rolled back", settings.serverUrl())
        assertTrue("needsRepair is cleared only after a successful baseline", auth.needsRepair.value)
    }

    @Test
    fun cancelled_submit_mid_baseline_rolls_back_token_and_url() = runTest(dispatcher) {
        auth.markUnauthorized()
        val baselineStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val suspendedRepo = FakeUpdatesRepository {
            baselineStarted.complete(Unit)
            gate.await()
        }
        val vm = PairingViewModel(tokens, settings, auth, suspendedRepo, PairedCredentialsHolder(), server, dispatcher)

        vm.submit(pairingCode("https://manga.example.com", TOKEN))

        assertTrue("baseline is suspended", baselineStarted.isCompleted)
        assertEquals("token and URL are already stored at this point", TOKEN, tokens.token)
        assertEquals("https://manga.example.com/", settings.serverUrl())
        assertEquals(PairingUiState.Validating, vm.state.value)

        vm.viewModelScope.cancel() // what clearing the ViewModel does

        assertNull("token rolled back after cancellation", tokens.token)
        assertNull("URL rolled back after cancellation", settings.serverUrl())
        assertTrue("needsRepair untouched: pairing never completed", auth.needsRepair.value)
        assertEquals(1, suspendedRepo.baselines)
    }

    @Test
    fun holder_is_published_only_after_both_writes_and_cleared_first_on_rollback() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        lateinit var holder: PairedCredentialsHolder
        fun held() = if (holder.get() == null) "null" else "set"
        holder = object : PairedCredentialsHolder() {
            override fun set(base: HttpUrl, token: String) {
                log += "holder.set"
                super.set(base, token)
            }

            override fun clear() {
                log += "holder.clear"
                super.clear()
            }
        }
        val store = object : TokenStore {
            override fun save(token: String) { log += "token.save[holder=${held()}]" }
            override fun load(): String? = null
            override fun clear() { log += "token.clear[holder=${held()}]" }
        }
        val urlState = MutableStateFlow<String?>(null)
        // The other settings (check settings, repair flag) are plain in-memory values; only URL writes are logged.
        val urls = object : SettingsStore by FakeSettingsStore() {
            override val serverUrl: Flow<String?> = urlState
            override suspend fun serverUrl(): String? = urlState.value
            override suspend fun setServerUrl(url: String?) {
                log += (if (url == null) "url.clear" else "url.set") + "[holder=${held()}]"
            }
            override suspend fun clearAll() = Unit
        }
        var failBaseline = false
        val repository = FakeUpdatesRepository {
            log += "baseline[holder=${held()}]"
            if (failBaseline) throw IOException("offline")
        }
        val code = pairingCode("https://manga.example.com", TOKEN)

        PairingViewModel(store, urls, auth, repository, holder, server, dispatcher).submit(code)
        assertEquals(
            // url.clear before token.save (final-review fix F): a crash after the token write never pairs the
            // new token with the old server's URL.
            listOf(
                "holder.clear", "url.clear[holder=null]", "token.save[holder=null]", "url.set[holder=null]",
                "holder.set", "baseline[holder=set]",
            ),
            log,
        )
        assertEquals("https://manga.example.com/", holder.get()!!.base.toString())
        assertFalse("the redacted value never prints the token", holder.get().toString().contains(TOKEN))

        log.clear()
        failBaseline = true
        PairingViewModel(store, urls, auth, repository, holder, server, dispatcher).submit(code)
        assertEquals(
            listOf(
                "holder.clear", "url.clear[holder=null]", "token.save[holder=null]", "url.set[holder=null]",
                "holder.set", "baseline[holder=set]", "holder.clear", "token.clear[holder=null]",
                "url.clear[holder=null]",
            ),
            log,
        )
        assertNull(holder.get())
    }

    @Test
    fun pairing_writes_clear_the_url_then_save_the_token_then_write_the_url_then_publish() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val holder = object : PairedCredentialsHolder() {
            override fun set(base: HttpUrl, token: String) {
                events += "publish"
                super.set(base, token)
            }
        }
        val urlState = MutableStateFlow<String?>("https://old.example.com/")
        val urls = object : SettingsStore by FakeSettingsStore() {
            override val serverUrl: Flow<String?> = urlState
            override suspend fun serverUrl(): String? = urlState.value
            override suspend fun setServerUrl(url: String?) {
                events += if (url == null) "clearUrl" else "urlSet"
                urlState.value = url
            }
        }
        var urlWhenTokenSaved: String? = "unset"
        val store = object : TokenStore {
            var token: String? = "old-token"
            override fun save(token: String) {
                events += "tokenSave"
                urlWhenTokenSaved = urlState.value
                this.token = token
            }
            override fun load(): String? = token
            override fun clear() { token = null }
        }

        val vm = PairingViewModel(store, urls, auth, repo, holder, server, dispatcher)
        vm.submit(pairingCode("https://manga.example.com", TOKEN))

        assertEquals(PairingUiState.Success, vm.state.value)
        assertEquals(listOf("clearUrl", "tokenSave", "urlSet", "publish"), events)
        assertNull("no URL is persisted while the new token is written", urlWhenTokenSaved)
        assertEquals("https://manga.example.com/", urlState.value)
    }

    @Test
    fun second_submit_while_validating_is_ignored() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String?>()
        val gated = LatestCall { _, token ->
            calls += token
            gate.await()
            if (token == null) throw httpError(401)
            LatestDto(cursor = 1)
        }
        val vm = PairingViewModel(tokens, settings, auth, repo, PairedCredentialsHolder(), gated, dispatcher)
        val code = pairingCode("https://manga.example.com", TOKEN)

        vm.submit(code)
        assertEquals(PairingUiState.Validating, vm.state.value)
        vm.submit(code)
        vm.submit("garbage")

        assertEquals("state stays Validating", PairingUiState.Validating, vm.state.value)
        assertEquals("only one probe is in flight", listOf<String?>(null), calls)

        gate.complete(Unit)

        assertEquals(PairingUiState.Success, vm.state.value)
        assertEquals("one probe and one validation in total", listOf<String?>(null, TOKEN), calls)
        assertEquals(1, tokens.saves)
        assertEquals(1, repo.baselines)
    }

    @Test
    fun connection_error_stores_nothing() = runTest(dispatcher) {
        val vm = viewModel()
        server.probeFailure = IOException("unreachable")
        vm.submit(pairingCode("https://manga.example.com", TOKEN))
        assertEquals(PairingUiState.Error(R.string.pairing_error_connection), vm.state.value)
        assertNothingStored()

        server.probeFailure = null
        server.validationFailure = IOException("timeout")
        vm.submit(pairingCode("https://manga.example.com", TOKEN))
        assertEquals(PairingUiState.Error(R.string.pairing_error_connection), vm.state.value)
        assertNothingStored()

        server.validationFailure = httpError(500)
        vm.submit(pairingCode("https://manga.example.com", TOKEN))
        assertEquals(PairingUiState.Error(R.string.pairing_error_server, "500"), vm.state.value)
        assertNothingStored()

        server.validationFailure = null
        server.probeFailure = httpError(502)
        vm.submit(pairingCode("https://manga.example.com", TOKEN))
        assertEquals(PairingUiState.Error(R.string.pairing_error_connection), vm.state.value)
        assertNothingStored()
    }

    @Test
    fun pasted_code_never_appears_in_ui_state_or_error_messages() = runTest(dispatcher) {
        val vm = viewModel()
        val states = recorded(vm)
        val codes = mutableListOf<String>()
        fun submit(code: String) {
            codes += code
            vm.submit(code)
            states += vm.state.value
        }

        submit("mm1.$TOKEN") // malformed, contains the token verbatim
        submit(pairingCode("http://manga.example.com", TOKEN)) // rejected URL
        server.validToken = "other"
        submit(pairingCode("https://manga.example.com", TOKEN)) // 401
        server.validToken = TOKEN
        server.validationFailure = IOException("boom $TOKEN")
        submit(pairingCode("https://manga.example.com", TOKEN)) // connection error whose message has the token
        server.validationFailure = null
        server.authDisabled = true
        submit(pairingCode("http://192.168.1.5:5000", TOKEN)) // confirmation dialog
        vm.dismissAuthDisabled()
        server.authDisabled = false
        baselineFailure = IllegalStateException(TOKEN)
        submit(pairingCode("https://manga.example.com", TOKEN)) // rollback
        baselineFailure = null
        submit(pairingCode("https://manga.example.com", TOKEN)) // success

        assertEquals(PairingUiState.Success, vm.state.value)
        for (state in states) {
            val text = state.toString() + ((state as? PairingUiState.Error)?.detail ?: "")
            assertFalse("token leaked into $state", text.contains(TOKEN))
            for (code in codes) assertFalse("pasted code leaked into $state", text.contains(code))
        }
    }

    /** The production [LatestCall] really sends no Authorization header on the probe and the Bearer token after. */
    @Test
    fun factory_latest_call_probes_without_authorization() = runBlocking {
        val web = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            web.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"login required"}"""))
            web.enqueue(MockResponse().setBody("""{"cursor":9}"""))
            val base = web.url("/")
            val probe = runCatching { FactoryLatestCall.latest(base, null) }.exceptionOrNull() as HttpException
            assertEquals(401, probe.code())
            assertEquals(9L, FactoryLatestCall.latest(base, TOKEN).cursor)

            val first = web.takeRequest()
            assertEquals("/api/v1/changes/latest", first.path)
            assertNull(first.getHeader("Authorization"))
            assertEquals("Bearer $TOKEN", web.takeRequest().getHeader("Authorization"))
        } finally {
            web.shutdown()
        }
    }

    private companion object {
        const val TOKEN = "tok_Secret-Value_0123456789abcdef"
    }
}
