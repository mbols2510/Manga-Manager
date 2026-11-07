package com.mbols2510.mangamanager.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Wire models of the server API (docs/api-v1.md). Every DTO tolerates unknown fields (the shared Json has
 * ignoreUnknownKeys) and every optional field is nullable or has a default, so an additive server change
 * never breaks parsing. Field names follow the server code that produces them.
 */

// ---------------------------------------------------------------- change feed (v1_changes_routes.py)

/** One row of `/api/v1/changes` (and of `/api/updates`, which has no `sensitive` flag and extra columns). */
@Serializable
data class UpdateDto(
    val id: Long,
    val title: String = "",
    val category: String? = null,
    @SerialName("is_new") val isNew: Boolean = false,
    val chapters: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    val thumbnail: String? = null,
    @SerialName("kavita_url") val kavitaUrl: String? = null,
    val sensitive: Boolean = false,
)

@Serializable
data class ChangesDto(
    val items: List<UpdateDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class LatestDto(val cursor: Long)

// ---------------------------------------------------------------- dashboard (web.py)

/** `GET /api/updates`: `{items, total, offset, limit}`. */
@Serializable
data class UpdatesPageDto(
    val items: List<UpdateDto> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

/** `GET /api/status`: `History.stats()` plus `scheduler` and `version`. */
@Serializable
data class StatusDto(
    val updates: Int = 0,
    @SerialName("new_series") val newSeries: Int = 0,
    val titles: Int = 0,
    val chapters: Int = 0,
    val categories: List<String> = emptyList(),
    @SerialName("last_run") val lastRun: RunRowDto? = null,
    val scheduler: SchedulerStatusDto? = null,
    val version: String? = null,
)

/** A row of the server's `runs` table. */
@Serializable
data class RunRowDto(
    val id: Long? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val status: String? = null,
    val moved: Int? = null,
    val covers: Int? = null,
    val message: String? = null,
)

/** `Scheduler.status()`. */
@Serializable
data class SchedulerStatusDto(
    val running: Boolean = false,
    @SerialName("next_run") val nextRun: String? = null,
    @SerialName("interval_minutes") val intervalMinutes: Int? = null,
    @SerialName("last_result") val lastResult: String? = null,
)

/** `POST /api/run`: `202 {started: true, ...}`, `409 {started: false, ...}`, `403` when manual runs are off. */
@Serializable
data class RunDto(
    val started: Boolean = false,
    val message: String? = null,
)

/** `GET /api/health` (core_routes.health_detail). */
@Serializable
data class HealthDto(
    val version: String? = null,
    val checks: List<HealthCheckDto> = emptyList(),
    val jobs: Map<String, JobStatusDto> = emptyMap(),
    val paused: String? = null,
)

/** One entry of `Guard.checks()`. `detail` is whatever the check returned, so it is kept as raw JSON. */
@Serializable
data class HealthCheckDto(
    val name: String = "",
    val state: String = "",
    val detail: JsonElement? = null,
    val ms: Long = 0,
)

/** `Job.status()` in jobs.py. */
@Serializable
data class JobStatusDto(
    val name: String? = null,
    val running: Boolean = false,
    @SerialName("last_started") val lastStarted: String? = null,
    @SerialName("last_finished") val lastFinished: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("next_run") val nextRun: String? = null,
)

// ---------------------------------------------------------------- double chapters (tools/double_chapters.py)

/** `GET /api/double-chapters/series`: a JSON array of these. */
@Serializable
data class DoubleSeriesSummaryDto(
    val id: Long,
    val name: String? = null,
    val libraryId: Long? = null,
    val chapterCount: Int = 0,
)

/** `GET /api/double-chapters/series/<id>`: the stored scan result of one series. */
@Serializable
data class DoubleSeriesDto(
    val id: Long,
    val name: String? = null,
    val libraryId: Long? = null,
    val chapters: List<DoubleChapterDto> = emptyList(),
)

/** One chapter from `flatten_chapters` + `enrich_chapter`. Kavita's number/range/title are kept as text. */
@Serializable
data class DoubleChapterDto(
    val id: Long,
    @Serializable(with = PrimitiveAsStringSerializer::class) val number: String = "",
    @Serializable(with = PrimitiveAsStringSerializer::class) val range: String = "",
    @Serializable(with = PrimitiveAsStringSerializer::class) val title: String = "",
    val isSpecial: Boolean = false,
    val sortOrder: Double? = null,
    val volumeId: Long? = null,
    @Serializable(with = PrimitiveAsStringSerializer::class) val volumeName: String = "",
    val isLooseVolume: Boolean = false,
    val files: List<DoubleFileDto> = emptyList(),
)

/** A file of a duplicated chapter. `format` is Kavita's raw value (shape not confirmed), kept as JSON. */
@Serializable
data class DoubleFileDto(
    val id: Long,
    val filePath: String? = null,
    val bytes: Long = 0,
    val pages: Int = 0,
    val format: JsonElement? = null,
    val localPath: String? = null,
    val modifiedAt: String? = null,
    val isOfficial: Boolean = false,
    val isNewest: Boolean = false,
    val isLargest: Boolean = false,
)

/** Body of `POST .../chapters/<id>/remediate`: every file of the chapter except this one goes to the trash. */
@Serializable
data class RemediateRequest(val keepFileId: Long)

@Serializable
data class RemediateResultDto(
    val moved: List<TrashEntryDto> = emptyList(),
    val libraryId: Long? = null,
)

/** A trash entry (`GET /api/double-chapters/trash` items, restore result, remediate `moved`). */
@Serializable
data class TrashEntryDto(
    val id: String,
    val originalPath: String? = null,
    val trashPath: String? = null,
    val seriesId: Long? = null,
    val seriesName: String? = null,
    val libraryId: Long? = null,
    val chapterId: Long? = null,
    val size: Long? = null,
    val deletedAt: String? = null,
    val expiresAt: String? = null,
)

/** Body of `POST .../series/<id>/kavita-scan`. */
@Serializable
data class KavitaScanRequest(
    val libraryId: Long? = null,
    val chapters: List<KavitaScanChapter> = emptyList(),
)

@Serializable
data class KavitaScanChapter(val chapterId: Long, val expectedPages: Int? = null)

@Serializable
data class KavitaScanResultDto(val scanned: Boolean = false)

/** `GET /api/double-chapters/status` (`DoubleChapters.status()`). */
@Serializable
data class DoublesStatusDto(
    val lastScan: String? = null,
    val scanning: Boolean = false,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val lastError: String? = null,
    val seriesWithDuplicates: Int = 0,
    val scanIntervalMinutes: Int? = null,
    val trashRetentionDays: Int? = null,
    val ntfyConfigured: Boolean = false,
    val digestEnabled: Boolean = false,
    val digestWeekday: String? = null,
    val digestHour: Int? = null,
    val lastDigestWeek: String? = null,
    val kavitaConfigured: Boolean = false,
)

/** Error body the server uses for 4xx answers: `{"error": "..."}`. */
@Serializable
data class ErrorDto(val error: String? = null)

/** Accepts any JSON primitive (string or number) as text; anything else becomes "". Kavita mixes both. */
internal object PrimitiveAsStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PrimitiveAsString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return (json.decodeJsonElement() as? JsonPrimitive)?.contentOrNull ?: ""
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
