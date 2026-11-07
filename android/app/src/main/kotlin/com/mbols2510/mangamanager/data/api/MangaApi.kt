package com.mbols2510.mangamanager.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The device-token API surface (docs/api-v1.md, "Endpoints a device may call"). Paths are relative (no
 * leading slash) so a base URL with a sub-path (`https://host/manga/`) keeps working behind a reverse proxy.
 *
 * Calls that return a bare DTO throw `retrofit2.HttpException` on a non-2xx answer; calls that return
 * [Response] leave status handling to the caller (304/410 on `changes`, 202/409/403 on `run`).
 */
interface MangaApi {
    @GET("api/v1/changes")
    suspend fun changes(
        @Query("since") since: Long,
        @Query("limit") limit: Int = 50,
        @Header("If-None-Match") ifNoneMatch: String?,
    ): Response<ChangesDto>

    @GET("api/v1/changes/latest")
    suspend fun latest(): LatestDto

    /** Paged browse of the dashboard feed. `kind` is `new` or `updated`; null means both. */
    @GET("api/updates")
    suspend fun updates(
        @Query("limit") limit: Int = 60,
        @Query("offset") offset: Int = 0,
        @Query("q") query: String? = null,
        @Query("category") category: String? = null,
        @Query("kind") kind: String? = null,
    ): UpdatesPageDto

    @GET("api/status")
    suspend fun status(): StatusDto

    @POST("api/run")
    suspend fun run(): Response<RunDto>

    @GET("api/health")
    suspend fun health(): HealthDto

    // ------------------------------------------------------------ double chapters

    @GET("api/double-chapters/series")
    suspend fun doubleSeries(): List<DoubleSeriesSummaryDto>

    @GET("api/double-chapters/series/{seriesId}")
    suspend fun doubleSeriesDetail(@Path("seriesId") seriesId: Long): DoubleSeriesDto

    /** Raw cover image bytes (content type from Kavita). Prefer loading covers through Coil with the same client. */
    @Streaming
    @GET("api/double-chapters/cover/{seriesId}")
    suspend fun doubleCover(@Path("seriesId") seriesId: Long): ResponseBody

    @POST("api/double-chapters/series/{seriesId}/chapters/{chapterId}/remediate")
    suspend fun remediate(
        @Path("seriesId") seriesId: Long,
        @Path("chapterId") chapterId: Long,
        @Body body: RemediateRequest,
    ): Response<RemediateResultDto>

    @POST("api/double-chapters/series/{seriesId}/kavita-scan")
    suspend fun kavitaScan(
        @Path("seriesId") seriesId: Long,
        @Body body: KavitaScanRequest,
    ): Response<KavitaScanResultDto>

    @GET("api/double-chapters/trash")
    suspend fun trash(): List<TrashEntryDto>

    @POST("api/double-chapters/trash/{trashId}/restore")
    suspend fun restoreTrash(@Path("trashId") trashId: String): Response<TrashEntryDto>

    @GET("api/double-chapters/status")
    suspend fun doublesStatus(): DoublesStatusDto
}
