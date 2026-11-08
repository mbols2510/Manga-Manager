package com.mbols2510.mangamanager.ui.updates

import com.mbols2510.mangamanager.data.api.MangaApi
import com.mbols2510.mangamanager.data.api.UpdateDto
import com.mbols2510.mangamanager.data.api.UpdatesPageDto
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.data.repo.FeedRefreshResult
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

fun feedItem(
    id: Long,
    title: String = "Title $id",
    category: String = "Manga",
    isNew: Boolean = false,
    sensitive: Boolean = false,
    kavitaUrl: String? = null,
    thumbnail: String? = "thumb/$id.png",
    notified: Boolean = true,
) = UpdateEntity(
    id = id,
    title = title,
    category = category,
    isNew = isNew,
    chapters = listOf("Ch. $id"),
    createdAt = "2026-11-0${1 + (id % 9)}T10:00:00+00:00",
    thumbnail = thumbnail,
    kavitaUrl = kavitaUrl,
    sensitive = sensitive,
    notified = notified,
)

/** A cached feed in memory; [refreshFeed] answers [next], after [gate] when one is set. */
class FakeFeedRepository(initial: List<UpdateEntity> = emptyList()) : UpdatesRepository {
    val feedState = MutableStateFlow(initial)
    override val feed = feedState

    @Volatile var refreshes = 0
    @Volatile var next: FeedRefreshResult = FeedRefreshResult.Ok
    var gate: CompletableDeferred<Unit>? = null
    var onRefresh: () -> Unit = {}
    val marked = mutableListOf<Long>()

    override suspend fun refreshFeed(): FeedRefreshResult {
        refreshes++
        gate?.await()
        onRefresh()
        return next
    }

    override suspend fun checkForChanges(): CheckResult = error("the screen refreshes through refreshFeed only")

    override suspend fun baseline() = error("the screen never takes a baseline")

    override suspend fun markNotified(ids: Collection<Long>) {
        marked += ids
        feedState.value = feedState.value.map { if (it.id in ids) it.copy(notified = true) else it }
    }

    override suspend fun pendingNotifications(): List<UpdateEntity> = feedState.value.filterNot { it.notified }

    override suspend fun clearLocalData() {
        feedState.value = emptyList()
    }
}

/** [paged] for the change feed, plus a fixed browse page. */
class FeedApi(private val paged: MangaApi, var browse: List<UpdateDto> = emptyList()) : MangaApi by paged {
    var browseCalls = 0

    override suspend fun updates(limit: Int, offset: Int, query: String?, category: String?, kind: String?): UpdatesPageDto {
        browseCalls++
        return UpdatesPageDto(browse, browse.size, offset, limit)
    }
}

/** Counts active collectors of [inner]'s feed, to prove the screen state is not shared without subscribers. */
class CountingFeedRepository(val inner: FakeFeedRepository) : UpdatesRepository by inner {
    @Volatile var active = 0
    @Volatile var starts = 0
    override val feed: Flow<List<UpdateEntity>> = inner.feedState
        .onStart { active++; starts++ }
        .onCompletion { active-- }
}
