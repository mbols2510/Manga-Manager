package com.mbols2510.mangamanager.data.repo

import com.mbols2510.mangamanager.data.api.sameOrigin
import retrofit2.HttpException

/**
 * A 401 counts only when it came from the origin the call was sent to. After a redirect to another host the 401 is
 * that host's and says nothing about our token (the token is never sent there).
 */
internal fun okhttp3.Response.isOwn401(): Boolean {
    if (code != 401) return false
    var first: okhttp3.Response = this
    while (true) first = first.priorResponse ?: break
    return sameOrigin(request.url, first.request.url)
}

internal fun HttpException.isOwn401(): Boolean = response()?.raw()?.isOwn401() == true
