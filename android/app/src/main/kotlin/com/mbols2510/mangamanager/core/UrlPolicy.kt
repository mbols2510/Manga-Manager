package com.mbols2510.mangamanager.core

import okhttp3.HttpUrl

sealed interface UrlCheck {
    data class Ok(val normalized: HttpUrl) : UrlCheck
    data class Rejected(val reason: String) : UrlCheck
}

object UrlPolicy {
    fun check(raw: String): UrlCheck = UrlCheck.Rejected("not implemented")
    fun isPrivateHost(host: String): Boolean = false
    fun isUnencrypted(url: HttpUrl): Boolean = false
}
