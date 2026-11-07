package com.mbols2510.mangamanager.ui.pairing

import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.api.LatestDto
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.HttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** `mm1.` + base64url(JSON), exactly as the server builds it. */
fun pairingCode(url: String, token: String): String =
    "mm1." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"v":1,"url":"$url","token":"$token"}""".toByteArray(Charsets.UTF_8))

fun httpError(code: Int): HttpException =
    HttpException(Response.error<Any>(code, """{"error":"x"}""".toResponseBody()))

class FakeTokenStore : TokenStore {
    var token: String? = null
    var saves = 0

    override fun save(token: String) {
        saves++
        this.token = token
    }

    override fun load(): String? = token

    override fun clear() {
        token = null
    }
}

class FakeUpdatesRepository(private val onBaseline: suspend () -> Unit = {}) : UpdatesRepository {
    var baselines = 0

    override val feed: Flow<List<UpdateEntity>> = emptyFlow()

    override suspend fun checkForChanges(): CheckResult = CheckResult.NoChange

    override suspend fun baseline() {
        baselines++
        onBaseline()
    }

    override suspend fun markNotified(ids: Collection<Long>) = Unit
}

/** A fake server for `changes/latest`: 401 without a valid token unless auth is disabled. */
class FakeServer(
    var validToken: String = "valid-token",
    var authDisabled: Boolean = false,
) : LatestCall {
    val calls = mutableListOf<Pair<HttpUrl, String?>>()

    /** Thrown on the unauthenticated probe / on the call with a token. */
    var probeFailure: Exception? = null
    var validationFailure: Exception? = null

    override suspend fun latest(baseUrl: HttpUrl, token: String?): LatestDto {
        calls += baseUrl to token
        if (token == null) {
            probeFailure?.let { throw it }
            if (!authDisabled) throw httpError(401)
        } else {
            validationFailure?.let { throw it }
            if (!authDisabled && token != validToken) throw httpError(401)
        }
        return LatestDto(cursor = 42)
    }
}
