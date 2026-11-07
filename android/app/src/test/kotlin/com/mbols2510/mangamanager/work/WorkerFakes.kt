package com.mbols2510.mangamanager.work

import com.mbols2510.mangamanager.data.api.ChangesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.DoublesStatusDto
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.KavitaScanRequest
import com.mbols2510.mangamanager.data.api.KavitaScanResultDto
import com.mbols2510.mangamanager.data.api.LatestDto
import com.mbols2510.mangamanager.data.api.MangaApi
import com.mbols2510.mangamanager.data.api.RemediateRequest
import com.mbols2510.mangamanager.data.api.RemediateResultDto
import com.mbols2510.mangamanager.data.api.RunDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.api.TrashEntryDto
import com.mbols2510.mangamanager.data.api.UpdateDto
import com.mbols2510.mangamanager.data.api.UpdatesPageDto
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.data.repo.FeedRefreshResult
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import com.mbols2510.mangamanager.notify.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.ResponseBody
import retrofit2.Response

/** Records what the worker asks to show. [available] false behaves like a denied permission. */
class FakeNotifier(var available: Boolean = true) : Notifier {
    /** Every notifyUpdates call, posted or not. */
    val updateCalls = mutableListOf<List<UpdateEntity>>()
    var repairCalls = 0

    override fun canNotify(): Boolean = available

    /** Simulates an unexpected platform failure while posting. */
    var failure: RuntimeException? = null

    override fun notifyUpdates(items: List<UpdateEntity>): Boolean {
        updateCalls += items
        failure?.let { throw it }
        return available && items.isNotEmpty()
    }

    /** Simulates an unexpected platform failure while posting the repair notice. */
    var repairFailure: RuntimeException? = null

    override fun notifyRepairNeeded(): Boolean {
        repairCalls++
        repairFailure?.let { throw it }
        return available
    }
}

/** A repository answering [next]; [pending] plays the stored-but-undecided rows. */
class FakeUpdatesRepository(var next: CheckResult = CheckResult.NoChange) : UpdatesRepository {
    var checks = 0
    val pending = mutableListOf<UpdateEntity>()
    val marked = mutableListOf<Long>()
    var onCheck: suspend () -> Unit = {}

    override val feed: Flow<List<UpdateEntity>> = emptyFlow()

    override suspend fun checkForChanges(): CheckResult {
        checks++
        onCheck()
        return next
    }

    override suspend fun baseline() = error("the worker never takes a baseline")

    override suspend fun refreshFeed(): FeedRefreshResult = error("the worker never refreshes the foreground feed")

    override suspend fun markNotified(ids: Collection<Long>) {
        marked += ids
        pending.removeAll { it.id in ids }
    }

    override suspend fun pendingNotifications(): List<UpdateEntity> = pending.toList()
}

/** Serves ids 1..[total] in cursor pages, like `GET api/v1/changes`. */
class PagedApi(var total: Long, private val sensitive: (Long) -> Boolean = { false }) : MangaApi {
    val sinces = mutableListOf<Long>()

    override suspend fun changes(since: Long, limit: Int, ifNoneMatch: String?): Response<ChangesDto> {
        sinces += since
        val ids = (since + 1..minOf(since + limit, total)).toList()
        val items = ids.map {
            UpdateDto(
                id = it,
                title = "Series $it",
                category = "Manga",
                chapters = listOf("Ch. $it"),
                createdAt = "2026-11-07T10:00:00+00:00",
                sensitive = sensitive(it),
            )
        }
        val next = ids.lastOrNull() ?: since
        return Response.success(ChangesDto(items, next, next < total))
    }

    override suspend fun latest(): LatestDto = LatestDto(total)
    override suspend fun updates(limit: Int, offset: Int, query: String?, category: String?, kind: String?): UpdatesPageDto = error("unused")
    override suspend fun status(): StatusDto = error("unused")
    override suspend fun run(): Response<RunDto> = error("unused")
    override suspend fun health(): HealthDto = error("unused")
    override suspend fun doubleSeries(): List<DoubleSeriesSummaryDto> = error("unused")
    override suspend fun doubleSeriesDetail(seriesId: Long): DoubleSeriesDto = error("unused")
    override suspend fun doubleCover(seriesId: Long): ResponseBody = error("unused")
    override suspend fun remediate(seriesId: Long, chapterId: Long, body: RemediateRequest): Response<RemediateResultDto> = error("unused")
    override suspend fun kavitaScan(seriesId: Long, body: KavitaScanRequest): Response<KavitaScanResultDto> = error("unused")
    override suspend fun trash(): List<TrashEntryDto> = error("unused")
    override suspend fun restoreTrash(trashId: String): Response<TrashEntryDto> = error("unused")
    override suspend fun doublesStatus(): DoublesStatusDto = error("unused")
}
