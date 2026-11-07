package com.mbols2510.mangamanager.data.repo

import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.ErrorDto
import com.mbols2510.mangamanager.data.api.HealthDto
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
    // STUB (red commit): every call reports Offline.
    override suspend fun doubleSeries(): ToolResult<List<DoubleSeriesSummaryDto>> = ToolResult.Offline
    override suspend fun doubleSeriesDetail(seriesId: Long): ToolResult<DoubleSeriesDto> = ToolResult.Offline
    override suspend fun remediate(seriesId: Long, chapterId: Long, keepFileId: Long): ToolResult<RemediateResultDto> =
        ToolResult.Offline
    override suspend fun trash(): ToolResult<List<TrashEntryDto>> = ToolResult.Offline
    override suspend fun restoreTrash(trashId: String): ToolResult<TrashEntryDto> = ToolResult.Offline
    override suspend fun status(): ToolResult<StatusDto> = ToolResult.Offline
    override suspend fun health(): ToolResult<HealthDto> = ToolResult.Offline
    override suspend fun runNow(): ToolResult<RunOutcome> = ToolResult.Offline

    companion object {
        const val MAX_ERROR_CHARS = 300
    }
}
