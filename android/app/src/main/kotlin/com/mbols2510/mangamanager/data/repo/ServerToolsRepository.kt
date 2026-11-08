package com.mbols2510.mangamanager.data.repo

import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.ErrorDto
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.KavitaScanChapter
import com.mbols2510.mangamanager.data.api.KavitaScanRequest
import com.mbols2510.mangamanager.data.api.KavitaScanResultDto
import com.mbols2510.mangamanager.data.api.MangaApi
import com.mbols2510.mangamanager.data.api.MangaApiFactory
import com.mbols2510.mangamanager.data.api.NotPairedException
import com.mbols2510.mangamanager.data.api.RemediateRequest
import com.mbols2510.mangamanager.data.api.RemediateResultDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.api.TrashEntryDto
import com.mbols2510.mangamanager.data.api.UrlPolicyViolation
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** Outcome of one call of [ServerToolsRepository]. */
sealed interface ToolResult<out T> {
    data class Success<T>(val value: T) : ToolResult<T>

    /** The paired server answered 401 (or nothing is paired): [AuthState.needsRepair] is now true. */
    data object Unauthorized : ToolResult<Nothing>

    /** The server could not be reached or its answer could not be read. A changing call was certainly not applied. */
    data object Offline : ToolResult<Nothing>

    /** The server answered with an error status. [message] is the server's `{"error": ...}` text, when it sent one. */
    data class ServerError(val code: Int, val message: String?) : ToolResult<Nothing>

    /**
     * A call that changes server state (remediate, restore, run) was sent but no readable answer came back (timeout,
     * dropped connection, unreadable body): the server may have applied it. Re-read the state to know.
     */
    data object MaybeApplied : ToolResult<Nothing>
}

/** `POST /api/run`: 202, 409 and 403 are three distinct, expected answers. */
enum class RunOutcome { Started, AlreadyRunning, Disabled }

/**
 * The Doubles and Jobs/Health calls (spec 4.2) over the shared, gated client. Every function runs on the injected IO
 * dispatcher and NEVER throws except [CancellationException]; an own-origin 401 sets [AuthState] exactly like the
 * update check does.
 */
interface ServerToolsRepository {
    suspend fun doubleSeries(): ToolResult<List<DoubleSeriesSummaryDto>>

    /** 404 (as [ToolResult.ServerError]) means the series has no duplicates any more. */
    suspend fun doubleSeriesDetail(seriesId: Long): ToolResult<DoubleSeriesDto>

    /** DESTRUCTIVE: every file of the chapter except [keepFileId] is moved to the server's trash. */
    suspend fun remediate(seriesId: Long, chapterId: Long, keepFileId: Long): ToolResult<RemediateResultDto>

    /**
     * Asks the server to rescan the series in Kavita and to re-mark remediated chapters as read once Kavita reports
     * the kept file's page count ([KavitaScanChapter.expectedPages]). The server waits for Kavita synchronously (up
     * to ~15 s per chapter), so a timeout is [ToolResult.MaybeApplied]. Never retried automatically.
     */
    suspend fun kavitaScan(
        seriesId: Long,
        libraryId: Long?,
        chapters: List<KavitaScanChapter>,
    ): ToolResult<KavitaScanResultDto>

    suspend fun trash(): ToolResult<List<TrashEntryDto>>

    /** Moves a trashed file back to where it was. */
    suspend fun restoreTrash(trashId: String): ToolResult<TrashEntryDto>

    suspend fun status(): ToolResult<StatusDto>

    suspend fun health(): ToolResult<HealthDto>

    suspend fun runNow(): ToolResult<RunOutcome>
}

/**
 * [api] is the stored-pairing supplier (null when not paired, which is treated as 401). [ensureReady] fills the
 * in-memory pairing caches when the start-up warm-up has not finished, so a cold cache is not reported as offline.
 */
class DefaultServerToolsRepository(
    private val api: () -> MangaApi?,
    private val authState: AuthState,
    private val ensureReady: suspend () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ServerToolsRepository {

    override suspend fun doubleSeries() = read { it.doubleSeries() }

    override suspend fun doubleSeriesDetail(seriesId: Long) = read { it.doubleSeriesDetail(seriesId) }

    override suspend fun remediate(seriesId: Long, chapterId: Long, keepFileId: Long) =
        write { it.remediate(seriesId, chapterId, RemediateRequest(keepFileId)) }

    override suspend fun kavitaScan(seriesId: Long, libraryId: Long?, chapters: List<KavitaScanChapter>) =
        write { it.kavitaScan(seriesId, KavitaScanRequest(libraryId, chapters)) }

    override suspend fun trash() = read { it.trash() }

    override suspend fun restoreTrash(trashId: String) = write { it.restoreTrash(trashId) }

    override suspend fun status() = read { it.status() }

    override suspend fun health() = read { it.health() }

    override suspend fun runNow(): ToolResult<RunOutcome> = call(changesState = true) { client ->
        val response = client.run()
        when (response.code()) {
            202 -> ToolResult.Success(RunOutcome.Started)
            409 -> ToolResult.Success(RunOutcome.AlreadyRunning)
            403 -> ToolResult.Success(RunOutcome.Disabled)
            else -> if (response.isSuccessful) ToolResult.Success(RunOutcome.Started) else failure(response)
        }
    }

    /** A GET returning the DTO itself: non-2xx arrives as [HttpException]. */
    private suspend fun <T> read(block: suspend (MangaApi) -> T): ToolResult<T> =
        call(changesState = false) { ToolResult.Success(block(it)) }

    /** A POST returning [Response]: the status is mapped here. */
    private suspend fun <T> write(block: suspend (MangaApi) -> Response<T>): ToolResult<T> =
        call(changesState = true) { client ->
            val response = block(client)
            val body = response.body()
            when {
                response.isSuccessful && body != null -> ToolResult.Success(body)
                // 2xx without a body: applied, but nothing to show.
                response.isSuccessful -> ToolResult.MaybeApplied
                else -> failure(response)
            }
        }

    private suspend fun <T> call(
        changesState: Boolean,
        block: suspend (MangaApi) -> ToolResult<T>,
    ): ToolResult<T> = withContext(io) {
        // Nothing has been sent while the pairing caches load: a failure here is plainly "offline".
        val client = try {
            ensureReady()
            api()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext ToolResult.Offline
        }
        if (client == null) {
            authState.markUnauthorized()
            return@withContext ToolResult.Unauthorized
        }
        try {
            block(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val raw = e.response()
            if (raw != null) failure(raw) else ToolResult.ServerError(e.code(), null)
        } catch (e: Exception) {
            if (changesState && !certainlyNotSent(e)) ToolResult.MaybeApplied else ToolResult.Offline
        }
    }

    private fun failure(response: Response<*>): ToolResult<Nothing> {
        if (response.code() == 401) {
            if (response.raw().isOwn401()) {
                authState.markUnauthorized()
                return ToolResult.Unauthorized
            }
            return ToolResult.Offline // a 401 from another host after a redirect says nothing about our token
        }
        return ToolResult.ServerError(response.code(), errorMessage(response.errorBody()))
    }

    companion object {
        /** Longest server error text shown to the user. */
        const val MAX_ERROR_CHARS = 300

        /**
         * Failures that happen before the request reaches the server: the gate refused it, no route, no DNS, no TLS
         * session. Anything else (timeouts, a dropped connection, an unreadable answer) may come after the server
         * received the request.
         */
        internal fun certainlyNotSent(e: Exception): Boolean = e is NotPairedException ||
            e is UrlPolicyViolation ||
            e is ConnectException ||
            e is NoRouteToHostException ||
            e is UnknownHostException ||
            e is SSLException

        /** The `error` field of a JSON error body, trimmed and capped; null for HTML or anything else. */
        internal fun errorMessage(body: ResponseBody?): String? = try {
            body?.use { b ->
                val text = b.string()
                MangaApiFactory.json.decodeFromString(ErrorDto.serializer(), text).error
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(MAX_ERROR_CHARS)
            }
        } catch (_: Exception) {
            null
        }
    }
}
