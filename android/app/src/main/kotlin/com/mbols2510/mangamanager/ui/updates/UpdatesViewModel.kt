package com.mbols2510.mangamanager.ui.updates

import androidx.lifecycle.ViewModel
import com.mbols2510.mangamanager.data.api.PairedCredentials
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl

enum class KindFilter { All, New, Updated }

enum class RefreshError { Offline }

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

// TDD stub (Task 10): compiles, does nothing.
class UpdatesViewModel(
    @Suppress("unused") private val repository: UpdatesRepository,
    @Suppress("unused") private val credentials: () -> PairedCredentials?,
    @Suppress("unused") private val ensureReady: suspend () -> Unit = {},
) : ViewModel() {
    val state: StateFlow<UpdatesUiState> = MutableStateFlow(UpdatesUiState())

    fun refresh() = Unit

    fun setQuery(query: String) = Unit

    fun setKind(kind: KindFilter) = Unit

    fun setCategory(category: String?) = Unit

    fun thumbnailUrl(item: UpdateEntity): String? = null
}

fun filterUpdates(items: List<UpdateEntity>, query: String, kind: KindFilter, category: String?): List<UpdateEntity> =
    items

fun categoriesOf(items: List<UpdateEntity>): List<String> = emptyList()

fun resolveThumbnail(base: HttpUrl?, thumbnail: String?): String? = null
