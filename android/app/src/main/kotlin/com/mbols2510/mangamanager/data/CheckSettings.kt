package com.mbols2510.mangamanager.data

import java.time.LocalTime

/**
 * When the background check runs (spec 6). [intervalMinutes] null means checks are off; other values are clamped
 * to 15..360 by the scheduler. [quietHours] null means no quiet hours. With [quietNightStretch] the worker checks
 * at most every 2 hours while inside [quietHours].
 */
data class CheckSettings(
    val intervalMinutes: Int?,
    val wifiOnly: Boolean,
    val quietNightStretch: Boolean,
    val quietHours: LocalTimeRange?,
) {
    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 360

        val DEFAULT = CheckSettings(
            intervalMinutes = DEFAULT_INTERVAL_MINUTES,
            wifiOnly = false,
            quietNightStretch = false,
            quietHours = null,
        )
    }
}

/**
 * A daily time window from [start] (inclusive) to [end] (exclusive). It may wrap midnight (22:00-07:00).
 * A window whose start equals its end is empty.
 */
data class LocalTimeRange(val start: LocalTime, val end: LocalTime) {
    operator fun contains(time: LocalTime): Boolean = when {
        start == end -> false
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }
}
