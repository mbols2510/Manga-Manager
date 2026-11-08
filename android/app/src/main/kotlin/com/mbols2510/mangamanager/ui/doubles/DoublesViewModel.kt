package com.mbols2510.mangamanager.ui.doubles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbols2510.mangamanager.data.api.DoubleChapterDto
import com.mbols2510.mangamanager.data.api.DoubleFileDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.KavitaScanChapter
import com.mbols2510.mangamanager.data.api.TrashEntryDto
import com.mbols2510.mangamanager.data.repo.ServerToolsRepository
import com.mbols2510.mangamanager.data.repo.ToolResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /** The call was sent but no clean answer came back (timeout, 5xx); the screen re-read the server state. */
    data object MaybeApplied : DoublesMessage

    /** A clean 4xx answer: nothing was changed. */
    data class ServerError(val code: Int, val text: String?) : DoublesMessage
}

/**
 * Remediated chapters of one series that Kavita has not been told about yet (`expectedPages` = the kept file's pages).
 * Kept until a scan answers with a clean 2xx.
 */
data class PendingScan(val libraryId: Long?, val chapters: List<KavitaScanChapter>)

/** State of the Kavita rescan of [seriesId]. Failures are warnings: nothing else is blocked by them. */
sealed interface KavitaNotice {
    val seriesId: Long

    data class Scanning(override val seriesId: Long) : KavitaNotice
    data class Done(override val seriesId: Long) : KavitaNotice

    /** No answer in time: the server may still be applying it. Retry is offered, never automatic. */
    data class MaybeApplied(override val seriesId: Long) : KavitaNotice
    data class Unreachable(override val seriesId: Long) : KavitaNotice
    data class Failed(override val seriesId: Long, val code: Int, val text: String?) : KavitaNotice
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
    /** Per series id: remediated chapters still to be sent to Kavita. */
    val pendingScans: Map<Long, PendingScan> = emptyMap(),
    /** Series whose Kavita rescan call is in flight. */
    val scanning: Set<Long> = emptySet(),
    val kavita: KavitaNotice? = null,
)

/**
 * Doubles (spec 4.2): series with duplicate chapters, per chapter keep one file and move the others to the server's
 * trash, and the trash with restore. Both changing actions run only after [confirm]. Loads once when the screen
 * opens (this ViewModel is created) and on [refresh]; nothing polls (spec 6).
 *
 * Like the web dashboard, Kavita is told once per series (`kavita-scan`) after its chapters were remediated: when the
 * user leaves the series, when no duplicates are left, or with [rescanKavita].
 */
class DoublesViewModel(private val repository: ServerToolsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(DoublesUiState(loading = true))
    val state: StateFlow<DoublesUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var actionJob: Job? = null
    private val scanJobs = mutableMapOf<Long, Job>()

    /** The series whose remediate call is in flight, if any. */
    private var remediating: Long? = null

    init {
        refresh()
    }

    /** Reloads the series list and the trash (and the open series). A load that is running is not started twice. */
    fun refresh() {
        mutableState.update { it.copy(confirm = null) }
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                reloadLists()
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
        mutableState.value.openSeriesId?.let { loadDetail(it) }
    }

    fun openSeries(seriesId: Long) {
        val previous = mutableState.value.openSeriesId
        mutableState.update {
            it.copy(openSeriesId = seriesId, detail = null, selection = emptyMap(), message = null, confirm = null)
        }
        if (previous != null && previous != seriesId) startScan(previous)
        loadDetail(seriesId)
    }

    /** Leaves the open series; remediated chapters are sent to Kavita now (in the background). */
    fun closeSeries() {
        detailJob?.cancel()
        val previous = mutableState.value.openSeriesId
        mutableState.update {
            it.copy(openSeriesId = null, detail = null, selection = emptyMap(), loadingDetail = false, confirm = null)
        }
        if (previous != null) startScan(previous)
    }

    /** Chooses the file to keep for one chapter of the open series. */
    fun select(chapterId: Long, fileId: Long) {
        mutableState.update { it.copy(selection = it.selection + (chapterId to fileId)) }
    }

    /**
     * Step 1 of the destructive keep/trash action: shows the confirmation naming the file kept and the files moved to
     * the trash. Nothing is sent until [confirm]. Ignored while another action is in flight or when the chapter is
     * not (or no longer) in the open series.
     */
    fun remediate(seriesId: Long, chapterId: Long, keepFileId: Long) {
        val s = mutableState.value
        if (s.busy || actionJob?.isActive == true) return
        val detail = s.detail?.takeIf { it.id == seriesId } ?: return
        val chapter = detail.chapters.firstOrNull { it.id == chapterId } ?: return
        val keep = chapter.files.firstOrNull { it.id == keepFileId } ?: return
        val trash = chapter.files.filter { it.id != keepFileId }
        if (trash.isEmpty()) return
        mutableState.update {
            it.copy(confirm = PendingConfirm.ConfirmRemediate(seriesId, detail.name.orEmpty(), chapter, keep, trash))
        }
    }

    /** Step 1 of restoring a trashed file: shows the confirmation. Nothing is sent until [confirm]. */
    fun restore(trashId: String) {
        val s = mutableState.value
        if (s.busy || actionJob?.isActive == true) return
        val entry = s.trash.firstOrNull { it.id == trashId } ?: return
        mutableState.update { it.copy(confirm = PendingConfirm.ConfirmRestore(entry)) }
    }

    fun dismissConfirm() {
        mutableState.update { it.copy(confirm = null) }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun dismissKavitaNotice() {
        mutableState.update { it.copy(kavita = null) }
    }

    /** Step 2: runs the confirmed action. A second tap while a call is in flight is ignored. */
    fun confirm() {
        if (actionJob?.isActive == true) return
        val s = mutableState.value
        val pending = s.confirm ?: return
        if (pending is PendingConfirm.ConfirmRemediate && !stillMatches(pending, s.detail)) {
            // The series changed since the dialog was shown: never act on a stale confirmation.
            mutableState.update { it.copy(confirm = null) }
            return
        }
        mutableState.update { it.copy(confirm = null, busy = true, message = null) }
        actionJob = viewModelScope.launch {
            try {
                when (pending) {
                    is PendingConfirm.ConfirmRemediate -> runRemediate(pending)
                    is PendingConfirm.ConfirmRestore -> runRestore(pending.entry)
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** "Rescan in Kavita": sends the pending chapters of [seriesId] (the open series when null). */
    fun rescanKavita(seriesId: Long? = null) {
        val id = seriesId ?: mutableState.value.openSeriesId ?: return
        startScan(id)
    }

    private suspend fun runRemediate(p: PendingConfirm.ConfirmRemediate) {
        remediating = p.seriesId
        val r = try {
            repository.remediate(p.seriesId, p.chapter.id, p.keep.id)
        } finally {
            remediating = null
        }
        when (r) {
            is ToolResult.Success -> {
                recordPendingScan(p, r.value.libraryId)
                mutableState.update { it.copy(message = DoublesMessage.Remediated(r.value.moved.size), unreachable = false) }
                refetchAfterChange(p.seriesId)
            }
            ToolResult.MaybeApplied -> unknownOutcome(p)
            ToolResult.Offline -> {
                // Not sent, so nothing changed; re-reading shows whether the server is back.
                mutableState.update { it.copy(unreachable = true) }
                refetchAfterChange(p.seriesId)
            }
            // The server can move files and then fail while saving: a 5xx is an unknown outcome, not a failure.
            is ToolResult.ServerError -> if (r.code >= 500) {
                unknownOutcome(p)
            } else {
                // A clean 4xx: nothing changed, but the chapter is probably gone (400/404), so re-read the series.
                mutableState.update { it.copy(message = DoublesMessage.ServerError(r.code, r.message)) }
                fetchDetail(p.seriesId)
            }
            ToolResult.Unauthorized -> Unit // the navigation shell switches to pairing
        }
    }

    private suspend fun unknownOutcome(p: PendingConfirm.ConfirmRemediate) {
        recordPendingScan(p, null)
        mutableState.update { it.copy(message = DoublesMessage.MaybeApplied) }
        refetchAfterChange(p.seriesId)
    }

    /**
     * Records the chapter for the Kavita scan. When the user already left the series (the remediate landed late), the
     * scan is started now: no other trigger is left for it (a scan in flight picks it up when it succeeds).
     */
    private fun recordPendingScan(p: PendingConfirm.ConfirmRemediate, libraryId: Long?) {
        recordPending(p, libraryId)
        if (mutableState.value.openSeriesId != p.seriesId) startScan(p.seriesId)
    }

    private fun recordPending(p: PendingConfirm.ConfirmRemediate, libraryId: Long?) {
        mutableState.update { s ->
            val old = s.pendingScans[p.seriesId]
            val library = libraryId ?: s.detail?.takeIf { it.id == p.seriesId }?.libraryId ?: old?.libraryId
            val chapters = old?.chapters.orEmpty().filter { it.chapterId != p.chapter.id } +
                KavitaScanChapter(p.chapter.id, p.keep.pages)
            s.copy(pendingScans = s.pendingScans + (p.seriesId to PendingScan(library, chapters)))
        }
    }

    /**
     * One `kavita-scan` for [seriesId] with its pending chapters, in [viewModelScope] so leaving the series does not
     * cancel it. Not started when nothing is pending or a scan of that series is already in flight; never retried
     * automatically (the server may need ~15 s per chapter). The pending list is cleared only on a clean 2xx.
     */
    private fun startScan(seriesId: Long) {
        val pending = mutableState.value.pendingScans[seriesId] ?: return
        if (pending.chapters.isEmpty() || scanJobs[seriesId]?.isActive == true) return
        mutableState.update { it.copy(scanning = it.scanning + seriesId, kavita = KavitaNotice.Scanning(seriesId)) }
        scanJobs[seriesId] = viewModelScope.launch {
            var followUp = false
            try {
                val notice = when (val r = repository.kavitaScan(seriesId, pending.libraryId, pending.chapters)) {
                    is ToolResult.Success -> {
                        val sent = pending.chapters.map { it.chapterId }.toSet()
                        mutableState.update { s ->
                            val left = s.pendingScans[seriesId]?.chapters.orEmpty().filter { it.chapterId !in sent }
                            val scans = if (left.isEmpty()) {
                                s.pendingScans - seriesId
                            } else {
                                s.pendingScans + (seriesId to pending.copy(chapters = left))
                            }
                            s.copy(pendingScans = scans)
                        }
                        // Chapters recorded while this scan was in flight. Only after a clean success, so a failing
                        // server is never asked twice; not while a remediate of the open series is still running
                        // (leaving the series or its 404 will send them then).
                        val s = mutableState.value
                        followUp = s.pendingScans[seriesId]?.chapters.orEmpty().isNotEmpty() &&
                            (s.openSeriesId != seriesId || remediating != seriesId)
                        KavitaNotice.Done(seriesId)
                    }
                    ToolResult.MaybeApplied -> KavitaNotice.MaybeApplied(seriesId)
                    ToolResult.Offline -> KavitaNotice.Unreachable(seriesId)
                    is ToolResult.ServerError -> KavitaNotice.Failed(seriesId, r.code, r.message)
                    ToolResult.Unauthorized -> null
                }
                mutableState.update { it.copy(kavita = notice) }
            } finally {
                mutableState.update { s ->
                    s.copy(
                        scanning = s.scanning - seriesId,
                        kavita = s.kavita.takeUnless { it is KavitaNotice.Scanning && it.seriesId == seriesId },
                    )
                }
            }
            if (followUp) {
                scanJobs.remove(seriesId) // this job is ending: let the single follow-up start
                startScan(seriesId)
            }
        }
    }

    private suspend fun runRestore(entry: TrashEntryDto) {
        when (val r = repository.restoreTrash(entry.id)) {
            is ToolResult.Success -> {
                mutableState.update { it.copy(message = DoublesMessage.Restored, unreachable = false) }
                refetchAfterChange(entry.seriesId)
            }
            ToolResult.MaybeApplied -> {
                mutableState.update { it.copy(message = DoublesMessage.MaybeApplied) }
                refetchAfterChange(entry.seriesId)
            }
            ToolResult.Offline -> mutableState.update { it.copy(unreachable = true) }
            is ToolResult.ServerError -> mutableState.update { it.copy(message = DoublesMessage.ServerError(r.code, r.message)) }
            ToolResult.Unauthorized -> Unit
        }
    }

    /** After a change (or an unknown outcome): the open series first, then the lists, so the screen shows the truth. */
    private suspend fun refetchAfterChange(seriesId: Long?) {
        val open = mutableState.value.openSeriesId
        if (open != null && (seriesId == null || open == seriesId)) {
            if (!fetchDetail(open)) return
        }
        reloadLists()
    }

    private fun loadDetail(seriesId: Long) {
        detailJob?.cancel()
        mutableState.update { it.copy(loadingDetail = true) }
        detailJob = viewModelScope.launch {
            try {
                fetchDetail(seriesId)
            } finally {
                mutableState.update { it.copy(loadingDetail = false) }
            }
        }
    }

    /** Re-reads one series. Returns false when the device must re-pair (stop there). */
    private suspend fun fetchDetail(seriesId: Long): Boolean {
        when (val r = repository.doubleSeriesDetail(seriesId)) {
            is ToolResult.Success -> mutableState.update { s ->
                if (s.openSeriesId != seriesId) {
                    s
                } else {
                    val selection = r.value.chapters.associate { c ->
                        val chosen = s.selection[c.id]?.takeIf { id -> c.files.any { it.id == id } }
                        c.id to (chosen ?: defaultKeep(c)?.id ?: -1L)
                    }.filterValues { it >= 0 }
                    val confirm = s.confirm.takeUnless {
                        it is PendingConfirm.ConfirmRemediate && it.seriesId == seriesId && !stillMatches(it, r.value)
                    }
                    s.copy(detail = r.value, selection = selection, unreachable = false, confirm = confirm)
                }
            }
            // 404: no duplicates left in this series (all handled): back to the list, and tell Kavita.
            is ToolResult.ServerError -> if (r.code == 404) {
                var closed = false
                mutableState.update { s ->
                    if (s.openSeriesId == seriesId) {
                        closed = true
                        s.copy(openSeriesId = null, detail = null, selection = emptyMap(), confirm = null)
                    } else {
                        s
                    }
                }
                if (closed) startScan(seriesId)
            } else {
                mutableState.update { it.copy(message = it.message ?: DoublesMessage.ServerError(r.code, r.message)) }
            }
            ToolResult.Offline, ToolResult.MaybeApplied -> mutableState.update { it.copy(unreachable = true) }
            ToolResult.Unauthorized -> return false
        }
        return true
    }

    private suspend fun reloadLists() {
        when (val r = repository.doubleSeries()) {
            is ToolResult.Success -> mutableState.update { it.copy(series = r.value, loaded = true, unreachable = false) }
            ToolResult.Offline, ToolResult.MaybeApplied -> {
                mutableState.update { it.copy(unreachable = true) }
                return // one failed request is enough: the trash would fail too
            }
            is ToolResult.ServerError -> mutableState.update {
                it.copy(message = it.message ?: DoublesMessage.ServerError(r.code, r.message))
            }
            ToolResult.Unauthorized -> return
        }
        when (val r = repository.trash()) {
            is ToolResult.Success -> mutableState.update { it.copy(trash = r.value, loaded = true) }
            ToolResult.Offline, ToolResult.MaybeApplied -> mutableState.update { it.copy(unreachable = true) }
            is ToolResult.ServerError -> mutableState.update {
                it.copy(message = it.message ?: DoublesMessage.ServerError(r.code, r.message))
            }
            ToolResult.Unauthorized -> Unit
        }
    }
}

/** True when [detail] still has the confirmed chapter with exactly the files the dialog named. */
internal fun stillMatches(p: PendingConfirm.ConfirmRemediate, detail: DoubleSeriesDto?): Boolean {
    if (detail == null || detail.id != p.seriesId) return false
    val chapter = detail.chapters.firstOrNull { it.id == p.chapter.id } ?: return false
    val ids = chapter.files.map { it.id }.toSet()
    return ids == (p.trash.map { it.id } + p.keep.id).toSet()
}

/** The web dashboard's default: the official release, else the newest file, else the first one. */
fun defaultKeep(chapter: DoubleChapterDto): DoubleFileDto? =
    chapter.files.firstOrNull { it.isOfficial } ?: chapter.files.firstOrNull { it.isNewest } ?: chapter.files.firstOrNull()

/** The file name of a server path (the part after the last slash or backslash). */
fun fileName(path: String?): String = path.orEmpty().substringAfterLast('/').substringAfterLast('\\')

/**
 * The parent folder and the file name ("Official/Ch. 12.cbz"): duplicates often share a file name in different folders,
 * so the name alone cannot tell them apart. Just the name when the path has no folder.
 */
fun folderAndName(path: String?): String {
    val parts = path.orEmpty().split('/', '\\').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> ""
        parts.size == 1 -> parts[0]
        else -> parts[parts.size - 2] + "/" + parts.last()
    }
}
