package com.mbols2510.mangamanager.work

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * System time in the device's CURRENT default time zone, read on every call, so quiet hours follow the user
 * when they travel or the zone changes ([Clock.systemDefaultZone] would keep the zone of the moment it was made).
 */
object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()
}
