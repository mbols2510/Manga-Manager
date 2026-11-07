package com.mbols2510.mangamanager.ui.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbols2510.mangamanager.data.api.PairedCredentials
import com.mbols2510.mangamanager.data.api.sameOrigin
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.repo.FeedRefreshResult
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

enum class KindFilter { All, New, Updated }

enum class RefreshError { Offline }

/**
 * What the Updates screen shows. [items] is the cached feed (newest first) after the local filters; [loaded] is
 * false until the cache answered once; [cacheEmpty] says whether the cache holds anything at all (empty state vs
 * "no match"). Titles and other server text are kept as they are and rendered as plain text.
 */
data class UpdatesUiState(
    val items: List<UpdateEntity> = emptyList(),
    val query: String = "",
    val kind: KindFilter = KindFilter.All,
    val category: String? = null,
    val categories: List<String> = emptyList(),
    val refreshing: Boolean = false,
    val error: RefreshError? = null,
    val loaded: Boolean = false,
    val cacheEmpty: Boolean = true,
)

/**
 * The Updates feed (spec 4.2, 5.2): the Room cache is shown at once, ONE refresh runs when the screen opens (this
 * ViewModel is created) and another one only on pull-to-refresh. Nothing polls (spec 6). Search and filters work on
 * the cached rows only.
 *
 * [credentials] is the in-memory pairing snapshot (never blocks); [ensureReady] fills the pairing caches when the
 * start-up warm-up has not finished yet, so the first refresh does not report a cold cache as "offline".
 */
class UpdatesViewModel(
    private val repository: UpdatesRepository,
    private val credentials: () -> PairedCredentials?,
    private val ensureReady: suspend () -> Unit = {},
) : ViewModel() {
    private data class Filters(val query: String = "", val kind: KindFilter = KindFilter.All, val category: String? = null)

    private data class Refresh(val running: Boolean = false, val error: RefreshError? = null)

    private val filters = MutableStateFlow(Filters())
    private val refreshState = MutableStateFlow(Refresh())
    private var refreshJob: Job? = null

    val state: StateFlow<UpdatesUiState> = combine(
        repository.feed.map<List<UpdateEntity>, List<UpdateEntity>?> { it }.onStart { emit(null) },
        filters,
        refreshState,
    ) { cached, f, r ->
        val all = cached.orEmpty()
        UpdatesUiState(
            items = filterUpdates(all, f.query, f.kind, f.category),
            query = f.query,
            kind = f.kind,
            category = f.category,
            categories = categoriesOf(all),
            refreshing = r.running,
            error = r.error,
            loaded = cached != null,
            cacheEmpty = all.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState(refreshing = true))

    init {
        refresh()
    }

    /** Opening the screen and pull-to-refresh. A refresh that is already running is not started twice. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshState.update { it.copy(running = true) }
        refreshJob = viewModelScope.launch {
            try {
                val result = try {
                    ensureReady()
                    repository.refreshFeed()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    FeedRefreshResult.Offline // a failed warm-up: nothing to show beyond the cache
                }
                val error = when (result) {
                    FeedRefreshResult.Ok -> null
                    FeedRefreshResult.Offline -> RefreshError.Offline
                    // The navigation shell switches to pairing (AuthState); no error may outlive a re-pair.
                    FeedRefreshResult.Unauthorized -> null
                }
                refreshState.update { it.copy(error = error) }
                markSeenNotified()
            } finally {
                refreshState.update { it.copy(running = false) }
            }
        }
    }

    /**
     * Everything the feed shows is seen: ids still waiting for a notification decision (stored by a background
     * check, or new in this refresh) are marked notified, so the worker never alerts about them later (ruling 21).
     */
    private suspend fun markSeenNotified() {
        try {
            val unseen = repository.feed.first().filterNot { it.notified }.map { it.id }
            if (unseen.isNotEmpty()) repository.markNotified(unseen)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed write only means a possible duplicate alert later; the feed itself is unaffected.
        }
    }

    fun setQuery(query: String) = filters.update { it.copy(query = query) }

    fun setKind(kind: KindFilter) = filters.update { it.copy(kind = kind) }

    fun setCategory(category: String?) = filters.update { it.copy(category = category) }

    /** The cover URL against the paired base, read now; null (placeholder, no request) when not paired. */
    fun thumbnailUrl(item: UpdateEntity): String? = resolveThumbnail(credentials()?.base, item.thumbnail)
}

/** Local search and filters. The query is plain text (never a regex), case- and accent-insensitive. */
fun filterUpdates(items: List<UpdateEntity>, query: String, kind: KindFilter, category: String?): List<UpdateEntity> {
    val needle = foldForSearch(query.trim())
    return items.filter { item ->
        (category == null || item.category == category) &&
            when (kind) {
                KindFilter.All -> true
                KindFilter.New -> item.isNew
                KindFilter.Updated -> !item.isNew
            } &&
            (needle.isEmpty() || foldForSearch(item.title).contains(needle))
    }
}

/** Distinct non-blank categories present in [items], sorted case-insensitively. */
fun categoriesOf(items: List<UpdateEntity>): List<String> =
    items.map { it.category }.filter { it.isNotBlank() }.distinct()
        .sortedWith(compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it })

private val combiningMarks = Regex("\\p{Mn}+")

/** Lower case without accents ("Café" and "CAFE" both become "cafe"). Only server text goes through the regex. */
internal fun foldForSearch(text: String): String =
    combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

/**
 * [thumbnail] (relative, `thumb/<id>.png`) resolved against the paired [base]. Null when not paired, when there is
 * no thumbnail, or when the result would leave the paired origin (an absolute or scheme-relative URL from the
 * server): such a request would be refused by the shared client anyway, so none is made.
 */
fun resolveThumbnail(base: HttpUrl?, thumbnail: String?): String? {
    if (base == null || thumbnail.isNullOrBlank()) return null
    val url = base.resolve(thumbnail) ?: return null
    return if (sameOrigin(base, url)) url.toString() else null
}
