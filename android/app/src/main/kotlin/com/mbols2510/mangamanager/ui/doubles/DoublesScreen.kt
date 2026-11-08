package com.mbols2510.mangamanager.ui.doubles

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.api.DoubleChapterDto
import com.mbols2510.mangamanager.data.api.DoubleFileDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.TrashEntryDto
import com.mbols2510.mangamanager.ui.common.Banner
import com.mbols2510.mangamanager.ui.common.UnreachableBanner
import com.mbols2510.mangamanager.ui.common.formatTimestamp
import com.mbols2510.mangamanager.ui.common.serverErrorText

/** Test tags of the Doubles screen. */
object DoublesTestTags {
    const val CONFIRM_DIALOG = "doubles_confirm_dialog"
    const val CONFIRM = "doubles_confirm"
    const val CANCEL = "doubles_cancel"
    const val SERIES_LIST = "doubles_series_list"
    const val TRASH_LIST = "doubles_trash_list"
    const val MESSAGE = "doubles_message"
    const val KAVITA_NOTICE = "doubles_kavita_notice"
    const val RESCAN = "doubles_rescan_kavita"
}

/**
 * Doubles (spec 4.2). No covers are loaded here (series names only), so nothing sensitive is shown beyond the names
 * the server lists; every server string is rendered as plain text.
 */
@Composable
fun DoublesScreen(viewModel: DoublesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        if (state.openSeriesId != null) {
            BackHandler(onBack = viewModel::closeSeries)
            SeriesDetail(
                state = state,
                onBack = viewModel::closeSeries,
                onSelect = viewModel::select,
                onKeep = viewModel::remediate,
                onRetry = viewModel::refresh,
                onDismissMessage = viewModel::dismissMessage,
            )
        } else {
            Overview(
                state = state,
                onRefresh = viewModel::refresh,
                onOpen = viewModel::openSeries,
                onRestore = viewModel::restore,
                onDismissMessage = viewModel::dismissMessage,
            )
        }
    }
    state.confirm?.let { pending ->
        ConfirmDialog(pending = pending, onConfirm = viewModel::confirm, onDismiss = viewModel::dismissConfirm)
    }
}

@OptIn(ExperimentalMaterial3Api::class) // PrimaryTabRow
@Composable
private fun Overview(
    state: DoublesUiState,
    onRefresh: () -> Unit,
    onOpen: (Long) -> Unit,
    onRestore: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Header(title = R.string.doubles_title, busy = state.loading || state.busy, onRefresh = onRefresh)
    PrimaryTabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.doubles_tab_series)) })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.doubles_tab_trash)) })
    }
    MessageBanner(state.message, onDismissMessage)
    if (state.unreachable && state.loaded) UnreachableBanner(onRefresh)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(if (tab == 0) DoublesTestTags.SERIES_LIST else DoublesTestTags.TRASH_LIST),
    ) {
        when {
            !state.loaded && state.unreachable -> item(key = "unreachable") { Unreachable(onRefresh) }
            !state.loaded -> Unit
            tab == 0 && state.series.isEmpty() -> item(key = "empty-series") {
                FullMessage(R.string.doubles_empty_title, R.string.doubles_empty_text)
            }
            tab == 1 && state.trash.isEmpty() -> item(key = "empty-trash") {
                FullMessage(R.string.doubles_trash_empty_title, R.string.doubles_trash_empty_text)
            }
            tab == 0 -> items(state.series, key = { "s${it.id}" }, contentType = { "series" }) { s ->
                SeriesRow(s, onOpen)
                HorizontalDivider()
            }
            else -> items(state.trash, key = { "t${it.id}" }, contentType = { "trash" }) { entry ->
                TrashRow(entry, enabled = !state.busy, onRestore = onRestore)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Header(@StringRes title: Int, busy: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.action_refresh)) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
}

/** The series name as the server sent it, or "Series <id>" when it has none. */
@Composable
private fun displayName(name: String?, id: Long): String =
    name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.doubles_series_unnamed, id)

@Composable
private fun SeriesRow(series: DoubleSeriesSummaryDto, onOpen: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.doubles_open_series), role = Role.Button) { onOpen(series.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = displayName(series.name, series.id),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.doubles_chapter_count, series.chapterCount, series.chapterCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrashRow(entry: TrashEntryDto, enabled: Boolean, onRestore: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = fileName(entry.originalPath ?: entry.trashPath),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.seriesName?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val deleted = formatTimestamp(entry.deletedAt)
            val meta = listOfNotNull(
                entry.size?.let { Formatter.formatShortFileSize(context, it) },
                deleted?.let { stringResource(R.string.doubles_trash_deleted, it) },
            ).joinToString(stringResource(R.string.list_separator))
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val expires = formatTimestamp(entry.expiresAt)
            Text(
                text = if (expires != null) {
                    stringResource(R.string.doubles_trash_expires, expires)
                } else {
                    stringResource(R.string.doubles_trash_never_expires)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { onRestore(entry.id) }, enabled = enabled) {
            Text(stringResource(R.string.doubles_restore))
        }
    }
}

@Composable
private fun SeriesDetail(
    state: DoublesUiState,
    onBack: () -> Unit,
    onSelect: (Long, Long) -> Unit,
    onKeep: (Long, Long, Long) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val detail = state.detail
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        Text(
            text = displayName(detail?.name ?: state.series.firstOrNull { it.id == state.openSeriesId }?.name, state.openSeriesId ?: 0L),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
    if (state.loadingDetail || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
    MessageBanner(state.message, onDismissMessage)
    if (state.unreachable) UnreachableBanner(onRetry)
    if (detail == null) return
    val chapters = remember(detail) { detail.chapters.sortedBy { it.sortOrder ?: 0.0 } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(chapters, key = { "c${it.id}" }, contentType = { "chapter" }) { chapter ->
            ChapterCard(
                series = detail,
                chapter = chapter,
                selected = state.selection[chapter.id],
                busy = state.busy,
                onSelect = onSelect,
                onKeep = onKeep,
            )
        }
    }
}

@Composable
private fun chapterLabel(chapter: DoubleChapterDto): String =
    chapter.title.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.doubles_chapter_label, chapter.range.ifBlank { chapter.number }.ifBlank { "?" })

@Composable
private fun ChapterCard(
    series: DoubleSeriesDto,
    chapter: DoubleChapterDto,
    selected: Long?,
    busy: Boolean,
    onSelect: (Long, Long) -> Unit,
    onKeep: (Long, Long, Long) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(chapterLabel(chapter), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val sub = listOfNotNull(
                chapter.volumeName.takeIf { it.isNotBlank() && !chapter.isLooseVolume }
                    ?.let { stringResource(R.string.doubles_volume_label, it) },
                pluralStringResource(R.plurals.doubles_file_count, chapter.files.size, chapter.files.size),
            ).joinToString(stringResource(R.string.list_separator))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                chapter.files.forEach { file ->
                    FileOption(file = file, selected = file.id == selected, onClick = { onSelect(chapter.id, file.id) })
                }
            }
            Button(
                onClick = { if (selected != null) onKeep(series.id, chapter.id, selected) },
                enabled = !busy && selected != null && chapter.files.size > 1,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(if (busy) R.string.doubles_working else R.string.doubles_keep_selected))
            }
        }
    }
}

@Composable
private fun fileFacts(file: DoubleFileDto): String {
    val context = LocalContext.current
    return listOf(
        formatTimestamp(file.modifiedAt) ?: stringResource(R.string.doubles_date_unknown),
        Formatter.formatShortFileSize(context, file.bytes),
        pluralStringResource(R.plurals.doubles_pages, file.pages, file.pages),
    ).joinToString(stringResource(R.string.list_separator))
}

@Composable
private fun FileOption(file: DoubleFileDto, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(fileName(file.filePath), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(fileFacts(file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val chips = listOfNotNull(
                if (file.isOfficial) stringResource(R.string.doubles_chip_official) else null,
                if (file.isNewest) stringResource(R.string.doubles_chip_newest) else null,
                if (file.isLargest) stringResource(R.string.doubles_chip_largest) else null,
            )
            if (chips.isNotEmpty()) {
                Text(
                    text = chips.joinToString(stringResource(R.string.list_separator)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(pending: PendingConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val destructive = pending is PendingConfirm.ConfirmRemediate
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DoublesTestTags.CONFIRM_DIALOG),
        title = {
            Text(
                stringResource(
                    if (destructive) R.string.doubles_confirm_remediate_title else R.string.doubles_confirm_restore_title,
                ),
            )
        },
        text = {
            when (pending) {
                is PendingConfirm.ConfirmRemediate -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(displayName(pending.seriesName, pending.seriesId), style = MaterialTheme.typography.titleSmall)
                    Text(chapterLabel(pending.chapter), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.doubles_confirm_remediate_keep, fileName(pending.keep.filePath)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(fileFacts(pending.keep), style = MaterialTheme.typography.bodySmall)
                    Text(
                        pluralStringResource(R.plurals.doubles_confirm_remediate_trash, pending.trash.size, pending.trash.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    pending.trash.forEach { f ->
                        Text(fileName(f.filePath), style = MaterialTheme.typography.bodyMedium)
                        Text(fileFacts(f), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.doubles_confirm_remediate_note), style = MaterialTheme.typography.bodySmall)
                }
                is PendingConfirm.ConfirmRestore -> Text(
                    stringResource(
                        R.string.doubles_confirm_restore_text,
                        fileName(pending.entry.originalPath ?: pending.entry.trashPath),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(DoublesTestTags.CONFIRM),
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(
                    stringResource(
                        if (destructive) R.string.doubles_confirm_remediate_action else R.string.doubles_confirm_restore_action,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(DoublesTestTags.CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun MessageBanner(message: DoublesMessage?, onDismiss: () -> Unit) {
    if (message == null) return
    val isError = message is DoublesMessage.ServerError || message is DoublesMessage.MaybeApplied
    val text = when (message) {
        is DoublesMessage.Remediated -> pluralStringResource(R.plurals.doubles_remediated, message.moved, message.moved)
        DoublesMessage.Restored -> stringResource(R.string.doubles_restored)
        DoublesMessage.MaybeApplied -> stringResource(R.string.doubles_maybe_applied)
        is DoublesMessage.ServerError -> serverErrorText(message.code, message.text)
    }
    Banner(text = text, isError = isError, onDismiss = onDismiss, tag = DoublesTestTags.MESSAGE)
}

@Composable
private fun LazyItemScope.Unreachable(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillParentMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.unreachable_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.unreachable_text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun LazyItemScope.FullMessage(@StringRes title: Int, @StringRes text: Int) {
    Column(
        modifier = Modifier
            .fillParentMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
