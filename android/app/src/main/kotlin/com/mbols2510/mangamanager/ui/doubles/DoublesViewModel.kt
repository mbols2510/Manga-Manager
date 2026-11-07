package com.mbols2510.mangamanager.ui.doubles

import androidx.lifecycle.ViewModel
import com.mbols2510.mangamanager.data.api.DoubleChapterDto
import com.mbols2510.mangamanager.data.api.DoubleFileDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.TrashEntryDto
import com.mbols2510.mangamanager.data.repo.ServerToolsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A destructive action waiting for the user's explicit confirmation (shown as a dialog). */
sealed interface PendingConfirm {
    /** Keep [keep], move every file in [trash] to the server's trash. */
    data class ConfirmRemediate(
        val seriesId: Long,
        val seriesName: String,
        val chapter: DoubleChapterDto,
        val keep: DoubleFileDto,
        val trash: List<DoubleFileDto>,
    ) : PendingConfirm

    /** Move [entry]'s file back to its original place. */
    data class ConfirmRestore(val entry: TrashEntryDto) : PendingConfirm
}

/** What the last action or load reported. Server text ([ServerError.text]) is shown as plain text. */
sealed interface DoublesMessage {
    data class Remediated(val moved: Int) : DoublesMessage
    data object Restored : DoublesMessage

    /** The call was sent but no answer came back; the screen re-read the server state. */
    data object MaybeApplied : DoublesMessage

    /** A clean error answer: nothing was changed. */
    data class ServerError(val code: Int, val text: String?) : DoublesMessage
}

data class DoublesUiState(
    val series: List<DoubleSeriesSummaryDto> = emptyList(),
    val trash: List<TrashEntryDto> = emptyList(),
    /** False until the first load answered (the empty states are only shown after it). */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** The open series (its duplicate chapters), or null on the series list. */
    val openSeriesId: Long? = null,
    val detail: DoubleSeriesDto? = null,
    val loadingDetail: Boolean = false,
    /** The file to keep per chapter id, for the open series. */
    val selection: Map<Long, Long> = emptyMap(),
    val confirm: PendingConfirm? = null,
    /** A remediate or restore call is in flight: further taps are ignored. */
    val busy: Boolean = false,
    val unreachable: Boolean = false,
    val message: DoublesMessage? = null,
)

/**
 * Doubles (spec 4.2): series with duplicate chapters, per chapter keep one file and move the others to the server's
 * trash, and the trash with restore. Both changing actions run only after [confirm]. Loads once when the screen
 * opens (this ViewModel is created) and on [refresh]; nothing polls (spec 6).
 */
class DoublesViewModel(private val repository: ServerToolsRepository) : ViewModel() {
    // STUB (red commit)
    private val mutableState = MutableStateFlow(DoublesUiState())
    val state: StateFlow<DoublesUiState> = mutableState.asStateFlow()

    fun refresh() = Unit
    fun openSeries(seriesId: Long) = Unit
    fun closeSeries() = Unit
    fun select(chapterId: Long, fileId: Long) = Unit
    fun remediate(seriesId: Long, chapterId: Long, keepFileId: Long) = Unit
    fun restore(trashId: String) = Unit
    fun dismissConfirm() = Unit
    fun dismissMessage() = Unit
    fun confirm() = Unit
}

/** The web dashboard's default: the official release, else the newest file, else the first one. */
fun defaultKeep(chapter: DoubleChapterDto): DoubleFileDto? =
    chapter.files.firstOrNull { it.isOfficial } ?: chapter.files.firstOrNull { it.isNewest } ?: chapter.files.firstOrNull()

/** The file name of a server path (the part after the last slash or backslash). */
fun fileName(path: String?): String = path.orEmpty().substringAfterLast('/').substringAfterLast('\\')
