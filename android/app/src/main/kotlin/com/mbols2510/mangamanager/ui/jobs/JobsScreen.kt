package com.mbols2510.mangamanager.ui.jobs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.api.HealthCheckDto
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.JobStatusDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.ui.common.Banner
import com.mbols2510.mangamanager.ui.common.SERVER_TEXT_LINES
import com.mbols2510.mangamanager.ui.common.UnreachableBanner
import com.mbols2510.mangamanager.ui.common.boundedServerText
import com.mbols2510.mangamanager.ui.common.formatTimestamp
import com.mbols2510.mangamanager.ui.common.serverErrorText

/** Test tags of the Jobs/Health screen. */
object JobsTestTags {
    const val RUN_NOW = "jobs_run_now"
    const val RUN_MESSAGE = "jobs_run_message"
    const val UNREACHABLE = "jobs_unreachable"
}

/** Jobs/Health (spec 4.2): run now, the scheduler status and the server's connection checks. */
@Composable
fun JobsScreen(viewModel: JobsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    JobsContent(
        state = state,
        onRefresh = viewModel::refresh,
        onRunNow = viewModel::runNow,
        onDismissRunMessage = viewModel::dismissRunMessage,
        modifier = modifier,
    )
}

@Composable
private fun JobsContent(
    state: JobsUiState,
    onRefresh: () -> Unit,
    onRunNow: () -> Unit,
    onDismissRunMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.jobs_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(onClick = onRefresh, enabled = !state.refreshing) { Text(stringResource(R.string.action_refresh)) }
        }
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
        state.runMessage?.let { message ->
            val isError = message !is RunMessage.Started
            Banner(text = runMessageText(message), isError = isError, onDismiss = onDismissRunMessage, tag = JobsTestTags.RUN_MESSAGE)
        }
        if (state.unreachable) {
            Column(Modifier.testTag(JobsTestTags.UNREACHABLE)) { UnreachableBanner(onRefresh) }
        }
        state.loadError?.let { (code, text) ->
            Banner(text = serverErrorText(code, text), isError = true, onDismiss = null, tag = "jobs_load_error")
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onRunNow,
                enabled = !state.runInFlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(JobsTestTags.RUN_NOW),
            ) {
                if (state.runInFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.jobs_run_now))
                }
            }
            state.status?.let { StatusCards(it) }
            state.health?.let { HealthCards(it) }
        }
    }
}

@Composable
private fun runMessageText(message: RunMessage): String = when (message) {
    RunMessage.Started -> stringResource(R.string.jobs_run_started)
    RunMessage.AlreadyRunning -> stringResource(R.string.jobs_run_already_running)
    RunMessage.Disabled -> stringResource(R.string.jobs_run_disabled)
    RunMessage.MaybeStarted -> stringResource(R.string.jobs_run_maybe_started)
    RunMessage.Unreachable -> stringResource(R.string.jobs_run_unreachable)
    is RunMessage.Failed -> serverErrorText(message.code, message.text)
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Line(text: String, secondary: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun time(iso: String?): String = formatTimestamp(iso) ?: stringResource(R.string.value_never)

@Composable
private fun StatusCards(status: StatusDto) {
    Section(R.string.jobs_section_scheduler) {
        val scheduler = status.scheduler
        if (scheduler == null) {
            Line(stringResource(R.string.jobs_scheduler_off))
        } else {
            Line(stringResource(if (scheduler.running) R.string.jobs_scheduler_running else R.string.jobs_scheduler_idle))
            Line(stringResource(R.string.jobs_next_run, time(scheduler.nextRun)))
            scheduler.intervalMinutes?.let { Line(pluralStringResource(R.plurals.jobs_interval, it, it), secondary = true) }
            scheduler.lastResult?.takeIf { it.isNotBlank() }?.let {
                Line(stringResource(R.string.jobs_last_result, boundedServerText(it)), secondary = true, maxLines = SERVER_TEXT_LINES)
            }
        }
    }
    Section(R.string.jobs_section_last_run) {
        val run = status.lastRun
        if (run == null) {
            Line(stringResource(R.string.jobs_no_runs))
        } else {
            run.status?.let { Line(stringResource(R.string.jobs_run_status, it)) }
            Line(stringResource(R.string.jobs_run_started_at, time(run.startedAt)), secondary = true)
            Line(stringResource(R.string.jobs_run_finished_at, time(run.finishedAt)), secondary = true)
            val counts = listOfNotNull(
                run.moved?.let { pluralStringResource(R.plurals.jobs_run_moved, it, it) },
                run.covers?.let { pluralStringResource(R.plurals.jobs_run_covers, it, it) },
            )
            if (counts.isNotEmpty()) Line(counts.joinToString(stringResource(R.string.list_separator)), secondary = true)
            run.message?.takeIf { it.isNotBlank() }?.let { Line(boundedServerText(it), secondary = true, maxLines = SERVER_TEXT_LINES) }
        }
    }
    Section(R.string.jobs_section_library) {
        Line(
            listOf(
                pluralStringResource(R.plurals.jobs_count_updates, status.updates, status.updates),
                pluralStringResource(R.plurals.jobs_count_new_series, status.newSeries, status.newSeries),
            ).joinToString(stringResource(R.string.list_separator)),
        )
        Line(
            listOf(
                pluralStringResource(R.plurals.jobs_count_titles, status.titles, status.titles),
                pluralStringResource(R.plurals.jobs_count_chapters, status.chapters, status.chapters),
            ).joinToString(stringResource(R.string.list_separator)),
        )
        status.version?.let { Line(stringResource(R.string.jobs_server_version, it), secondary = true) }
    }
}

@Composable
private fun HealthCards(health: HealthDto) {
    Section(R.string.jobs_section_health) {
        health.paused?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.jobs_paused, boundedServerText(it)), maxLines = SERVER_TEXT_LINES, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (health.checks.isEmpty()) Line(stringResource(R.string.jobs_no_checks))
        health.checks.forEach { CheckRow(it) }
    }
    if (health.jobs.isNotEmpty()) {
        Section(R.string.jobs_section_jobs) {
            health.jobs.entries.sortedBy { it.key }.forEach { (key, job) -> JobRow(job.name ?: key, job) }
        }
    }
}

@Composable
private fun CheckRow(check: HealthCheckDto) {
    val (label, failed) = when (check.state) {
        "ok" -> stringResource(R.string.jobs_check_ok) to false
        "fail" -> stringResource(R.string.jobs_check_fail) to true
        "unset" -> stringResource(R.string.jobs_check_unset) to false
        else -> check.state.ifBlank { stringResource(R.string.value_unknown) } to false
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(check.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        val facts = listOfNotNull(
            check.detail.takeIf { it.isNotBlank() }?.let(::boundedServerText),
            if (check.state == "unset") null else stringResource(R.string.jobs_check_ms, check.ms),
        )
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString(stringResource(R.string.list_separator)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = SERVER_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun JobRow(name: String, job: JobStatusDto) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (job.running) Text(stringResource(R.string.jobs_job_running), style = MaterialTheme.typography.labelLarge)
        }
        Line(stringResource(R.string.jobs_job_last_finished, time(job.lastFinished)), secondary = true)
        job.nextRun?.let { Line(stringResource(R.string.jobs_next_run, time(it)), secondary = true) }
        job.lastError?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.jobs_job_last_error, boundedServerText(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = SERVER_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
