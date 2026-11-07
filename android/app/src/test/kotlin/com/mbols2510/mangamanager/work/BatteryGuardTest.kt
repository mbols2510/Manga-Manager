package com.mbols2510.mangamanager.work

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 6 / Ruling 12b guard: the app's own code never runs foreground or expedited work, never schedules alarms,
 * never takes a wake lock and never registers its own receivers or jobs. WorkManager's own components do that work.
 * Plain JVM test over the Kotlin sources (comments are ignored, line numbers are kept).
 */
class BatteryGuardTest {

    @Test
    fun no_expedited_or_foreground_work_calls_in_source() {
        // Gradle runs unit tests with the module directory (android/app) as working directory.
        val root = File("src/main")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no Kotlin sources found under ${root.absolutePath}", sources.size > 10)
        val hits = sources.flatMap { BatteryGuard.scan(it.path, it.readText()) }
        assertEquals("forbidden battery-costly APIs in src/main:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun scanner_flags_each_forbidden_api_with_file_and_line_and_ignores_comments() {
        val fixture = """
            package x
            // setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) in a comment is fine
            /* setForeground(info)
               AlarmManager in a block comment is fine */
            /** KDoc: newWakeLock is fine here too */
            val a = OneTimeWorkRequestBuilder<W>().setExpedited(policy)
            suspend fun b() { setForeground(ForegroundInfo(1, n)) }
            fun c(ctx: Context) = ctx.startForegroundService(intent)
            val d = ctx.getSystemService(AlarmManager::class.java)
            fun e() = alarms.setExactAndAllowWhileIdle(0, 0L, pi)
            fun f() = alarms.setAlarmClock(info, pi)
            val g = power.newWakeLock(PARTIAL_WAKE_LOCK, "x")
            class H : BroadcastReceiver() { }
            val i = object : BroadcastReceiver() { }
            val j = ctx.getSystemService(JobScheduler::class.java)
            val k = setForegroundAsync(info)
            val fine = "ok" // setExpedited
        """.trimIndent()
        val hits = BatteryGuard.scan("Fixture.kt", fixture)
        val lines = hits.map { it.substringAfter("Fixture.kt:").substringBefore(':').toInt() }.distinct()
        assertEquals(hits.joinToString("\n"), (6..16).toList(), lines)
        assertTrue(hits.any { it.startsWith("Fixture.kt:7:") && it.contains("ForegroundInfo") })
        assertTrue(hits.any { it.startsWith("Fixture.kt:7:") && it.contains("setForeground") })
        assertEquals(emptyList<String>(), BatteryGuard.scan("Clean.kt", "val s = Scheduler(workManager)\n// AlarmManager\n"))
    }
}

/** Finds forbidden API names outside comments; each hit is `file:line: name`. */
object BatteryGuard {
    private val forbidden = listOf(
        "setExpedited" to Regex("""\bsetExpedited\b"""),
        "setForeground" to Regex("""\bsetForeground(Async)?\b"""),
        "ForegroundInfo" to Regex("""\bForegroundInfo\b"""),
        "startForegroundService" to Regex("""\bstartForegroundService\b"""),
        "AlarmManager" to Regex("""\bAlarmManager\b"""),
        "setExact" to Regex("""\bsetExact\w*"""),
        "setAlarmClock" to Regex("""\bsetAlarmClock\b"""),
        "newWakeLock" to Regex("""\bnewWakeLock\b"""),
        "BroadcastReceiver subclass" to Regex(""":\s*BroadcastReceiver\s*\("""),
        "JobScheduler" to Regex("""\bJobScheduler\b|\bJobService\b|\bJobInfo\b"""),
    )

    fun scan(file: String, text: String): List<String> =
        stripComments(text).lines().flatMapIndexed { index, line ->
            forbidden.filter { (_, regex) -> regex.containsMatchIn(line) }.map { (name, _) -> "$file:${index + 1}: $name" }
        }

    /** Blanks `//` and `/* */` comments (keeping newlines, so line numbers stay). String literals are kept. */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && next != null) {
                        out.append(next)
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> {
                    inString = true
                    out.append(c)
                }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                        if (text[i] == '\n') out.append('\n')
                        i++
                    }
                    i += 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
