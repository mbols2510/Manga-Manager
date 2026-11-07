package com.mbols2510.mangamanager.data.api

import okhttp3.HttpUrl

/**
 * The paired server and its token as ONE immutable value: whatever reads it gets a base URL and the token that
 * belongs to it, never one from an older pairing and one from a newer one. [toString] prints neither the token
 * nor anything of the URL beyond scheme, host and port.
 */
class PairedCredentials(val base: HttpUrl, val token: String) {
    override fun toString(): String = "PairedCredentials(${base.scheme}://${base.host}:${base.port}, token=<redacted>)"
}

/**
 * The only source the shared client's request path reads: one `@Volatile` field replaced in a single assignment.
 * Pairing publishes it after both persisted writes succeeded; rollback and unpair clear it first. [get] never
 * blocks. Open only so tests can record the order of events.
 */
open class PairedCredentialsHolder {
    @Volatile
    private var value: PairedCredentials? = null
    private var touched = false // guarded by this

    open fun get(): PairedCredentials? = value

    @Synchronized
    open fun set(base: HttpUrl, token: String) {
        value = PairedCredentials(base, token)
        touched = true
    }

    @Synchronized
    open fun clear() {
        value = null
        touched = true
    }

    /** Startup warm-up: never overrides an explicit [set] or [clear] (a pairing or unpairing that started meanwhile wins). */
    @Synchronized
    fun publishIfUntouched(base: HttpUrl, token: String) {
        if (!touched) value = PairedCredentials(base, token)
    }

    override fun toString(): String = "PairedCredentialsHolder"
}
