package com.mbols2510.mangamanager.data.repo

import com.mbols2510.mangamanager.data.api.DoubleChapterDto
import com.mbols2510.mangamanager.data.api.DoubleFileDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.KavitaScanRequest
import com.mbols2510.mangamanager.data.api.KavitaScanResultDto
import com.mbols2510.mangamanager.data.api.MangaApi
import com.mbols2510.mangamanager.data.api.MangaApiFactory
import com.mbols2510.mangamanager.data.api.RemediateRequest
import com.mbols2510.mangamanager.data.api.RemediateResultDto
import com.mbols2510.mangamanager.data.api.RunDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.api.TrashEntryDto
import com.mbols2510.mangamanager.work.PagedApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** A server fixture from src/test/resources/fixtures, written from the server code that produces it. */
fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.classLoader!!.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

fun <T> parseFixture(name: String, serializer: KSerializer<T>): T =
    MangaApiFactory.json.decodeFromString(serializer, fixture(name))

private val jsonType = "application/json".toMediaType()

/** A Retrofit error [Response] whose raw request is on [url] (an own-origin answer unless a redirect is modelled). */
fun <T> errorResponse(code: Int, body: String = """{"error":"nope"}""", url: String = "http://127.0.0.1:8080/x"): Response<T> =
    Response.error(
        body.toResponseBody(jsonType),
        okhttp3.Response.Builder()
            .code(code)
            .message("HTTP $code")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url(url).build())
            .build(),
    )

fun <T> successResponse(code: Int, value: T): Response<T> = Response.success(
    value,
    okhttp3.Response.Builder()
        .code(code)
        .message("HTTP $code")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("http://127.0.0.1:8080/x").build())
        .build(),
)

fun httpError(code: Int, body: String = """{"error":"nope"}"""): HttpException = HttpException(errorResponse<Any>(code, body))

fun file(id: Long, name: String, pages: Int = 20, bytes: Long = 1_000, official: Boolean = false, newest: Boolean = false) =
    DoubleFileDto(
        id = id,
        filePath = "/manga/S/$name",
        bytes = bytes,
        pages = pages,
        isOfficial = official,
        isNewest = newest,
    )

fun chapter(id: Long, vararg files: DoubleFileDto, number: String = "$id") =
    DoubleChapterDto(id = id, number = number, range = number, files = files.toList(), sortOrder = id.toDouble())

fun series(id: Long, name: String, vararg chapters: DoubleChapterDto) =
    DoubleSeriesDto(id = id, name = name, libraryId = 1, chapters = chapters.toList())

fun trashEntry(id: String, seriesId: Long = 1, name: String = "$id.cbz") =
    TrashEntryDto(id = id, originalPath = "/data/manga/S/$name", trashPath = "/trash/$name", seriesId = seriesId, seriesName = "S", size = 10)

/**
 * A scriptable server for the Doubles and Jobs calls. Every handler can throw (IOException, HttpException,
 * CancellationException) to model the transport; [gate] holds the changing calls until it completes.
 */
class ToolsApi : MangaApi by PagedApi(0) {
    var seriesList: () -> List<DoubleSeriesSummaryDto> = { emptyList() }
    var detail: (Long) -> DoubleSeriesDto = { throw httpError(404) }
    var remediateHandler: (Long, Long, Long) -> Response<RemediateResultDto> = { _, _, _ ->
        successResponse(200, RemediateResultDto(emptyList(), 1))
    }
    var trashList: () -> List<TrashEntryDto> = { emptyList() }
    var restoreHandler: (String) -> Response<TrashEntryDto> = { id -> successResponse(200, trashEntry(id)) }
    var statusHandler: () -> StatusDto = { StatusDto() }
    var healthHandler: () -> HealthDto = { HealthDto() }
    var runHandler: () -> Response<RunDto> = { successResponse(202, RunDto(true, "Run started")) }
    var gate: CompletableDeferred<Unit>? = null
    var kavitaHandler: (Long, KavitaScanRequest) -> Response<KavitaScanResultDto> = { _, _ ->
        successResponse(200, KavitaScanResultDto(scanned = true))
    }
    var kavitaGate: CompletableDeferred<Unit>? = null
    val kavitaRequests = mutableListOf<Pair<Long, KavitaScanRequest>>()

    val calls = mutableListOf<String>()
    fun count(prefix: String) = calls.count { it.startsWith(prefix) }

    override suspend fun doubleSeries(): List<DoubleSeriesSummaryDto> {
        calls += "series"
        return seriesList()
    }

    override suspend fun doubleSeriesDetail(seriesId: Long): DoubleSeriesDto {
        calls += "detail:$seriesId"
        return detail(seriesId)
    }

    override suspend fun remediate(seriesId: Long, chapterId: Long, body: RemediateRequest): Response<RemediateResultDto> {
        calls += "remediate:$seriesId:$chapterId:${body.keepFileId}"
        gate?.await()
        return remediateHandler(seriesId, chapterId, body.keepFileId)
    }

    override suspend fun kavitaScan(seriesId: Long, body: KavitaScanRequest): Response<KavitaScanResultDto> {
        calls += "kavita:$seriesId:lib=${body.libraryId}:" + body.chapters.joinToString(",", "[", "]") { "${it.chapterId}=${it.expectedPages}" }
        kavitaRequests += seriesId to body
        kavitaGate?.await()
        return kavitaHandler(seriesId, body)
    }

    override suspend fun trash(): List<TrashEntryDto> {
        calls += "trash"
        return trashList()
    }

    override suspend fun restoreTrash(trashId: String): Response<TrashEntryDto> {
        calls += "restore:$trashId"
        gate?.await()
        return restoreHandler(trashId)
    }

    override suspend fun status(): StatusDto {
        calls += "status"
        return statusHandler()
    }

    override suspend fun health(): HealthDto {
        calls += "health"
        return healthHandler()
    }

    override suspend fun run(): Response<RunDto> {
        calls += "run"
        gate?.await()
        return runHandler()
    }
}

/** Summary rows for [series], as `GET /api/double-chapters/series` builds them. */
fun summaries(vararg s: DoubleSeriesDto) = s.map { DoubleSeriesSummaryDto(it.id, it.name, it.libraryId, it.chapters.size) }
