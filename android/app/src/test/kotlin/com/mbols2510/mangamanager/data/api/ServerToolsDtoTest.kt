package com.mbols2510.mangamanager.data.api

import com.mbols2510.mangamanager.data.repo.parseFixture
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures under src/test/resources/fixtures are written from the server code that builds each answer:
 * tools/double_chapters.py (`list_series`, `series_detail` = the stored `run_scan` result with `flatten_chapters` +
 * `enrich_chapter`, `remediate_chapter`, `trash_list`), web.py `status` (`History.stats` + `Scheduler.status`) and
 * core_routes.py `health_detail` (`Guard.checks` + `JobRunner.status`).
 */
class ServerToolsDtoTest {
    @Test
    fun doubles_dto_parses_the_real_server_json() {
        val list = parseFixture("double_series_list.json", ListSerializer(DoubleSeriesSummaryDto.serializer()))
        assertEquals(listOf(12L, 7L), list.map { it.id })
        assertEquals("Berserk", list[0].name)
        assertEquals(2, list[0].chapterCount)
        assertNull("a series without a name (rescanned before the full scan)", list[1].name)

        val detail = parseFixture("double_series_detail.json", DoubleSeriesDto.serializer())
        assertEquals(12L, detail.id)
        assertEquals(1L, detail.libraryId)
        val (c12, c13) = detail.chapters
        assertEquals("12", c12.number)
        assertEquals("3", c12.volumeName)
        assertFalse(c12.isLooseVolume)
        assertEquals(12.0, c12.sortOrder!!, 0.0)
        val official = c12.files[0]
        assertEquals(9001L, official.id)
        assertEquals("/manga/Berserk/Berserk Ch.12 (Official).cbz", official.filePath)
        assertEquals(52_428_800L, official.bytes)
        assertEquals(24, official.pages)
        assertTrue(official.isOfficial)
        assertTrue(official.isLargest)
        assertFalse(official.isNewest)
        assertEquals("2026-11-02T19:05:11.123456+00:00", c12.files[1].modifiedAt)
        assertTrue(c12.files[1].isNewest)
        assertEquals("a numeric Kavita chapter number becomes text", "13", c13.number)
        assertEquals("The Eclipse", c13.title)
        assertTrue(c13.isLooseVolume)
        assertNull("modifiedAt is null when the file is missing on disk", c13.files[0].modifiedAt)

        val trash = parseFixture("double_trash.json", ListSerializer(TrashEntryDto.serializer()))
        assertEquals("6f1c9a52-3d1e-4c8b-9b6e-2a7f0d4e5c11", trash[0].id)
        assertEquals(12L, trash[0].seriesId)
        assertEquals(500L, trash[0].chapterId)
        assertEquals(30_000_000L, trash[0].size)
        assertEquals("2026-12-05T10:15:00.512345+00:00", trash[0].expiresAt)
        assertNull("no expiry when automatic deletion is off", trash[1].expiresAt)
        assertNull(trash[1].seriesName)

        val result = parseFixture("remediate_result.json", RemediateResultDto.serializer())
        assertEquals(1L, result.libraryId)
        val moved = result.moved.single()
        assertEquals("/data/manga/Berserk/Berserk Ch.12.cbz", moved.originalPath)
        assertEquals(31_457_280L, moved.size)
        assertNull("remediate entries carry no expiresAt (only trash_list adds it)", moved.expiresAt)
    }

    @Test
    fun status_dto_parses_the_real_server_json() {
        val s = parseFixture("status.json", StatusDto.serializer())
        assertEquals(1234, s.updates)
        assertEquals(56, s.newSeries)
        assertEquals(420, s.titles)
        assertEquals(9876, s.chapters)
        assertEquals(listOf("Manga", "Manhwa"), s.categories)
        val run = s.lastRun!!
        assertEquals(77L, run.id)
        assertEquals("ok", run.status)
        assertEquals(3, run.moved)
        assertEquals(1, run.covers)
        assertEquals("2026-11-07T09:02:13+00:00", run.finishedAt)
        val scheduler = s.scheduler!!
        assertFalse(scheduler.running)
        assertEquals("2026-11-07T10:02:13+00:00", scheduler.nextRun)
        assertEquals(60, scheduler.intervalMinutes)
        assertEquals("Moved 3 chapter(s)", scheduler.lastResult)
        assertEquals("2.4.0", s.version)

        // No scheduler (manual-only server) and no run yet.
        val bare = MangaApiFactory.json.decodeFromString(
            StatusDto.serializer(),
            """{"updates":0,"new_series":0,"titles":0,"chapters":0,"categories":[],"last_run":null,"scheduler":null,"version":"2.4.0"}""",
        )
        assertNull(bare.lastRun)
        assertNull(bare.scheduler)
    }

    @Test
    fun health_dto_parses_the_real_server_json() {
        val h = parseFixture("health.json", HealthDto.serializer())
        assertEquals("2.4.0", h.version)
        assertEquals(listOf("Database", "NAS", "Kavita", "Suwayomi", "ntfy"), h.checks.map { it.name })
        assertEquals(listOf("ok", "ok", "fail", "ok", "unset"), h.checks.map { it.state })
        assertEquals("online (192.168.1.10:445)", h.checks[1].detail)
        assertEquals("ntfy URL not set", h.checks[4].detail)
        assertEquals(5003L, h.checks[2].ms)
        assertEquals("Offline: Kavita", h.paused)
        val scan = h.jobs.getValue("dc-scan")
        assertEquals("dc-scan", scan.name)
        assertFalse(scan.running)
        assertEquals("2026-11-07T09:45:20+00:00", scan.lastFinished)
        assertEquals("Skipped: Offline: Kavita", h.jobs.getValue("dc-trash").lastError)

        // No guard configured: empty checks, null pause reason; a non-text detail becomes "".
        val bare = MangaApiFactory.json.decodeFromString(
            HealthDto.serializer(),
            """{"version":"2.4.0","checks":[{"name":"X","state":"ok","detail":{"a":1},"ms":1}],"jobs":{},"paused":null}""",
        )
        assertNull(bare.paused)
        assertEquals("", bare.checks.single().detail)
    }
}
