package com.mbols2510.mangamanager.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Counts pairings: [bump] runs FIRST in an unpair and on every pairing success. Background work captures
 * [current] when it starts and makes every state-changing write through [ifCurrent], so a run that started for an
 * earlier pairing writes nothing (no repair flag, no "last check", no notification) after an unpair.
 *
 * [ifCurrent] and [bump] share one lock: a write that saw the current generation finishes before a [bump] returns,
 * so everything an unpair clears after its [bump] can no longer be written by an older run.
 */
class PairingGeneration {
    private val value = AtomicLong(0)
    private val lock = Mutex()

    fun current(): Long = value.get()

    suspend fun bump(): Long = lock.withLock { value.incrementAndGet() }

    /** Runs [block] only while the generation is still [expected]; null when it changed (a stale run). */
    suspend fun <T> ifCurrent(expected: Long, block: suspend () -> T): T? = lock.withLock {
        if (value.get() == expected) block() else null
    }
}
