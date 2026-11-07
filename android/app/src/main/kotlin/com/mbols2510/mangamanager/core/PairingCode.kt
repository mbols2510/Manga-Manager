package com.mbols2510.mangamanager.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class Pairing(val url: String, val token: String)

/**
 * Parses the one-time code shown by the server: `mm1.` + base64url(JSON `{"v":1,"url":..,"token":..}`).
 * Pure and total: returns null for anything malformed and never throws. The URL is NOT policy-checked
 * here; the pairing screen does that.
 */
object PairingCode {
    private const val PREFIX = "mm1."

    fun parse(raw: String): Pairing? = try {
        parseOrThrow(raw)
    } catch (_: Exception) {
        null
    }

    private fun parseOrThrow(raw: String): Pairing? {
        // QR scanners and pasted text wrap lines, so drop every whitespace character.
        val compact = raw.filterNot { it.isWhitespace() }
        if (!compact.startsWith(PREFIX)) return null
        var body = compact.substring(PREFIX.length).trimEnd('=')
        if (body.isEmpty()) return null
        while (body.length % 4 != 0) body += "="
        val bytes = Base64.getUrlDecoder().decode(body)
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val obj = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val v = obj["v"] as? JsonPrimitive ?: return null
        if (v.isString || v.content != "1") return null
        val url = stringField(obj, "url") ?: return null
        val token = stringField(obj, "token") ?: return null
        return Pairing(url, token)
    }

    private fun stringField(obj: JsonObject, name: String): String? {
        val p = obj[name] as? JsonPrimitive ?: return null
        if (!p.isString || p.content.isBlank()) return null
        return p.content
    }
}
