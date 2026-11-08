package com.mbols2510.mangamanager.ui.updates

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.ui.common.CoverImage
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Test tags of the Updates screen. */
object UpdatesTestTags {
    const val LIST = "updates_list"
}

/**
 * The Updates feed (spec 4.2): cached rows at once, search and filters over the cache, pull-to-refresh, and a tap
 * on a row opens its Kavita page in another app (http/https only, see [KavitaLink]). [notificationBanner] is shown
 * at the top (the app passes the notification-state banner, so a user who never opens Settings still learns that
 * alerts are off).
 */
@Composable
fun UpdatesScreen(
    viewModel: UpdatesViewModel,
    modifier: Modifier = Modifier,
    notificationBanner: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openFailed = stringResource(R.string.updates_open_failed)

    Box(modifier.fillMaxSize()) {
        UpdatesContent(
            state = state,
            notificationBanner = notificationBanner,
            onRefresh = viewModel::refresh,
            onQueryChange = viewModel::setQuery,
            onKindChange = viewModel::setKind,
            onCategoryChange = viewModel::setCategory,
            thumbnailUrl = viewModel::thumbnailUrl,
            onOpen = { url ->
                if (!KavitaLink.open(context, url)) scope.launch { snackbar.showSnackbar(openFailed) }
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesContent(
    state: UpdatesUiState,
    notificationBanner: @Composable () -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onKindChange: (KindFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    thumbnailUrl: (UpdateEntity) -> String?,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        notificationBanner()
        SearchField(state.query, onQueryChange)
        KindFilters(state.kind, onKindChange)
        if (state.categories.isNotEmpty()) CategoryFilters(state.categories, state.category, onCategoryChange)

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UpdatesTestTags.LIST),
            ) {
                // Every state lives inside the list, so pull-to-refresh also works on an empty screen.
                when {
                    !state.loaded -> Unit
                    state.cacheEmpty && state.error != null -> item(key = "offline") {
                        FullMessage(R.string.updates_offline_title, R.string.updates_offline_text)
                    }
                    state.cacheEmpty -> if (!state.refreshing) {
                        item(key = "empty") { FullMessage(R.string.updates_empty_title, R.string.updates_empty_text) }
                    }
                    else -> {
                        if (state.error != null) item(key = "banner") { OfflineBanner() }
                        if (state.items.isEmpty()) item(key = "no-match") { NoMatch() }
                    }
                }
                items(state.items, key = { it.id }, contentType = { "update" }) { item ->
                    UpdateRow(item = item, thumbnailUrl = thumbnailUrl(item), onOpen = onOpen)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.updates_search_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            { TextButton(onClick = { onQueryChange("") }) { Text(stringResource(R.string.updates_search_clear)) } }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

private val kindLabels = listOf(
    KindFilter.All to R.string.updates_kind_all,
    KindFilter.New to R.string.updates_kind_new,
    KindFilter.Updated to R.string.updates_kind_updated,
)

@Composable
private fun KindFilters(selected: KindFilter, onKindChange: (KindFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        kindLabels.forEach { (kind, label) ->
            FilterChip(
                selected = selected == kind,
                onClick = { onKindChange(kind) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun CategoryFilters(categories: List<String>, selected: String?, onCategoryChange: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onCategoryChange(null) },
            label = { Text(stringResource(R.string.updates_category_all)) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onCategoryChange(if (selected == category) null else category) },
                label = { Text(category, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun UpdateRow(item: UpdateEntity, thumbnailUrl: String?, onOpen: (String) -> Unit) {
    val link = remember(item.kavitaUrl) { KavitaLink.toIntentUriOrNull(item.kavitaUrl) }
    val openLabel = stringResource(R.string.updates_open_in_kavita)
    val clickable = if (link != null) {
        Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { onOpen(link) }
    } else {
        Modifier // no safe link: a plain row, nothing to open
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverImage(
            url = thumbnailUrl,
            sensitive = item.sensitive,
            title = item.title,
            modifier = Modifier.size(width = 56.dp, height = 80.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.chapters.isNotEmpty()) {
                Text(
                    text = item.chapters.joinToString(stringResource(R.string.updates_chapters_separator)),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val kind = stringResource(if (item.isNew) R.string.updates_badge_new else R.string.updates_badge_updated)
            val date = remember(item.createdAt) { formatDate(item.createdAt) }
            Text(
                text = listOf(kind, item.category, date).filter { it.isNotBlank() }
                    .joinToString(stringResource(R.string.updates_meta_separator)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.updates_offline_banner),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun NoMatch() {
    Text(
        text = stringResource(R.string.updates_no_match),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    )
}

/** The server's ISO timestamp as a local date, or "" when it cannot be read. */
private fun formatDate(createdAt: String): String {
    if (createdAt.isBlank()) return ""
    val local = runCatching { OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()) }
        .getOrNull()
        ?.toLocalDate()
        ?: runCatching { LocalDateTime.parse(createdAt).toLocalDate() }.getOrNull()
        ?: return ""
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local) // the current locale
}
