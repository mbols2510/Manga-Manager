package com.mbols2510.mangamanager.data.repo

import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.core.UrlCheck
import com.mbols2510.mangamanager.core.UrlPolicy
import com.mbols2510.mangamanager.data.api.ChangesDto
import com.mbols2510.mangamanager.data.api.MangaApi
import com.mbols2510.mangamanager.data.api.MangaApiFactory
import com.mbols2510.mangamanager.data.api.UpdateDto
import com.mbols2510.mangamanager.data.db.SyncState
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.db.UpdatesCache
import com.mbols2510.mangamanager.data.db.toEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl
import retrofit2.HttpException
import retrofit2.Response

sealed interface CheckResult {
    /** Nothing new to show (304, an empty page, a 410 re-baseline, or only already-known ids). */
    data object NoChange : CheckResult

    /** Items whose ids were not stored before this check, oldest first. */
    data class NewItems(val items: List<UpdateEntity>) : CheckResult

    /** The server answered 401: [AuthState.needsRepair] is now true. */
    data object Unauthorized : CheckResult

    /** Network failure, timeout, unexpected status or unreadable answer. Nothing was advanced. */
    data object Offline : CheckResult
}

interface UpdatesRepository {
    /** The cached feed, newest first. */
    val feed: Flow<List<UpdateEntity>>

    /** Background/foreground check (spec 5.1, 5.3). Never throws except for coroutine cancellation. */
    suspend fun checkForChanges(): CheckResult

    /**
     * Stores `/api/v1/changes/latest` as the cursor (clears the ETag, stores no items). Used after pairing and
     * on 410. Throws on failure (IOException, retrofit2.HttpException); a 401 also sets [AuthState.needsRepair].
     */
    suspend fun baseline()

    /** Records that these ids were notified (or deliberately not), so they are never reported again. */
    suspend fun markNotified(ids: Collection<Long>)
}

/**
 * [api] returns the client for the stored pairing, or null when the device is not paired (treated as 401).
 * The foreground feed refresh (Task 10) reuses [checkForChanges]; there is no separate refresh call.
 */
class DefaultUpdatesRepository internal constructor(
    private val api: () -> MangaApi?,
    private val cache: UpdatesCache,
    private val authState: AuthState,
    private val clock: () -> Long,
    private val pageSize: Int,
    private val maxPages: Int,
) : UpdatesRepository {
    constructor(
        api: () -> MangaApi?,
        cache: UpdatesCache,
        authState: AuthState,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(api, cache, authState, clock, PAGE_SIZE, MAX_PAGES)

    override val feed: Flow<List<UpdateEntity>> = cache.feed()

    override suspend fun checkForChanges(): CheckResult {
        val client = api() ?: return unauthorized()
        return try {
            val state = cache.syncState()
            if (state == null) {
                // Fresh install: never start from since=0 (pruned servers answer 410), start at the newest id.
                baselineWith(client)
                CheckResult.NoChange
            } else {
                fetch(client, state)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 401) unauthorized() else CheckResult.Offline
        } catch (_: Exception) {
            // IOException (offline, timeout, TLS, URL policy), unreadable JSON, a failed database write:
            // nothing was committed, so the next check simply retries from the same cursor.
            CheckResult.Offline
        }
    }

    override suspend fun baseline() {
        val client = api() ?: run {
            authState.markUnauthorized()
            throw IllegalStateException("Device is not paired")
        }
        try {
            baselineWith(client)
        } catch (e: HttpException) {
            if (e.code() == 401) authState.markUnauthorized()
            throw e
        }
    }

    override suspend fun markNotified(ids: Collection<Long>) = cache.markNotified(ids)

    private suspend fun baselineWith(client: MangaApi) {
        val latest = client.latest()
        check(latest.cursor >= 0) { "Server returned a negative cursor" }
        cache.setSyncState(SyncState(cursor = latest.cursor, etag = null, lastSuccessAt = clock()))
    }

    private sealed interface Page {
        data class Ok(val body: ChangesDto, val etag: String?) : Page
        data class Stop(val result: CheckResult) : Page
    }

    private suspend fun page(client: MangaApi, since: Long, etag: String?): Page {
        val response: Response<ChangesDto> = client.changes(since, pageSize, etag)
        return when (response.code()) {
            304 -> Page.Stop(CheckResult.NoChange)
            401 -> Page.Stop(unauthorized())
            410 -> {
                // Cursor too old or the server was reset: new baseline, nothing older is reported.
                baselineWith(client)
                Page.Stop(CheckResult.NoChange)
            }
            200 -> {
                val body = response.body() ?: return Page.Stop(CheckResult.Offline)
                if (body.nextCursor < since) return Page.Stop(CheckResult.Offline)
                Page.Ok(body, response.headers()["ETag"])
            }
            else -> Page.Stop(CheckResult.Offline)
        }
    }

    private suspend fun fetch(client: MangaApi, state: SyncState): CheckResult {
        val collected = LinkedHashMap<Long, UpdateDto>()
        var cursor = state.cursor
        var etag: String? = null
        var pages = 0
        var send: String? = state.etag
        while (true) {
            val page = when (val p = page(client, cursor, send)) {
                is Page.Stop -> return p.result
                is Page.Ok -> p
            }
            pages++
            send = null // later pages never carry If-None-Match
            for (item in page.body.items) {
                collected.remove(item.id)
                collected[item.id] = item
            }
            cursor = page.body.nextCursor
            etag = page.etag
            val more = page.body.hasMore && page.body.items.isNotEmpty()
            if (!more) break
            if (pages >= maxPages) {
                etag = null // a capped walk did not reach the end; its ETag describes a page in the middle
                break
            }
        }

        if (collected.isEmpty()) {
            // Nothing new. Only remember a changed ETag (one small write) so the next check can get a 304.
            if (cursor != state.cursor || (etag != null && etag != state.etag)) {
                cache.setSyncState(SyncState(cursor = cursor, etag = etag ?: state.etag, lastSuccessAt = clock()))
            }
            return CheckResult.NoChange
        }

        val fresh = cache.applyChanges(
            items = collected.values.map { it.toEntity() },
            state = SyncState(cursor = cursor, etag = etag, lastSuccessAt = clock()),
        )
        return if (fresh.isEmpty()) CheckResult.NoChange else CheckResult.NewItems(fresh.sortedBy { it.id })
    }

    private fun unauthorized(): CheckResult {
        authState.markUnauthorized()
        return CheckResult.Unauthorized
    }

    companion object {
        const val PAGE_SIZE = 50

        /** Defensive cap: 200 pages of 50 is 10,000 updates in one check. */
        const val MAX_PAGES = 200
    }
}

/**
 * Production [MangaApi] supplier for the stored pairing: re-checks the stored URL with [UrlPolicy], reads the
 * token lazily from [tokenStore] on every request, routes every 401 to [authState], and reuses the client
 * while the URL is unchanged. Returns null when not paired or the stored URL is no longer acceptable.
 */
class StoredPairingApi(
    private val storedUrl: () -> String?,
    private val tokenStore: TokenStore,
    private val authState: AuthState,
) : () -> MangaApi? {
    private var cached: Pair<HttpUrl, MangaApi>? = null

    @Synchronized
    override fun invoke(): MangaApi? {
        if (tokenStore.load() == null) return null
        val url = (UrlPolicy.check(storedUrl() ?: return null) as? UrlCheck.Ok)?.normalized ?: return null
        cached?.let { (u, api) -> if (u == url) return api }
        return MangaApiFactory.create(url, tokenStore::load, authState::markUnauthorized).also { cached = url to it }
    }
}
