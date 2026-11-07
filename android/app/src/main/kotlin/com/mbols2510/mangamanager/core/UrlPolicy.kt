package com.mbols2510.mangamanager.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface UrlCheck {
    data class Ok(val normalized: HttpUrl) : UrlCheck
    data class Rejected(val reason: String) : UrlCheck
}

/**
 * Transport policy (spec 7.3): HTTPS to any host, plain HTTP only to private hosts.
 *
 * Privacy is decided from the host string alone. There is never a DNS lookup: IP literals are
 * parsed here by hand and every other name is public unless it is `localhost` or `*.local`.
 */
object UrlPolicy {
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_HOST_LENGTH = 253

    fun check(raw: String): UrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlCheck.Rejected("Server URL is empty")
        if (trimmed.length > MAX_URL_LENGTH) return UrlCheck.Rejected("Server URL is too long")
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlCheck.Rejected("Server URL must not contain spaces")
        }
        val authority = trimmed.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return UrlCheck.Rejected("Server URL must not contain credentials")

        // HttpUrl only knows http and https, so any other scheme is null here.
        val url = trimmed.toHttpUrlOrNull()
            ?: return UrlCheck.Rejected("Not a valid http(s) URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return UrlCheck.Rejected("Server URL must not contain credentials")
        }
        if (url.host.length > MAX_HOST_LENGTH) return UrlCheck.Rejected("Server host name is too long")
        if (isUnencrypted(url) && !isPrivateHost(url.host)) {
            return UrlCheck.Rejected("Plain HTTP is only allowed for local or private network addresses; use HTTPS")
        }

        // Query and fragment are meaningless for a base URL: drop them. Keep the sub-path with a trailing slash.
        val builder = url.newBuilder().query(null).fragment(null)
        if (!url.encodedPath.endsWith("/")) builder.encodedPath(url.encodedPath + "/")
        return UrlCheck.Ok(builder.build())
    }

    fun isUnencrypted(url: HttpUrl): Boolean = url.scheme == "http"

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local")) return true
        parseIpv4(h)?.let { return isPrivateV4(it) }
        parseIpv6(h)?.let { return isPrivateV6(it) }
        return false
    }

    private fun isPrivateV4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xff
        val c = b[1].toInt() and 0xff
        return a == 127 ||
            a == 10 ||
            (a == 172 && c in 16..31) ||
            (a == 192 && c == 168) ||
            (a == 169 && c == 254) ||
            (a == 100 && c in 64..127)
    }

    private fun isPrivateV6(b: ByteArray): Boolean {
        // IPv4-mapped (::ffff:a.b.c.d) is judged by the underlying IPv4 address.
        if ((0..9).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff) {
            return isPrivateV4(b.copyOfRange(12, 16))
        }
        val b0 = b[0].toInt() and 0xff
        val b1 = b[1].toInt() and 0xff
        val loopback = (0..14).all { b[it].toInt() == 0 } && b[15].toInt() == 1
        return loopback ||
            (b0 and 0xfe) == 0xfc || // fc00::/7 unique local
            (b0 == 0xfe && (b1 and 0xc0) == 0x80) // fe80::/10 link local
    }

    /** Strict dotted-quad parser; anything else (hex, octal, short forms) is not an IPv4 literal. */
    private fun parseIpv4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val n = p.toInt()
            if (n > 255) return null
            out[i] = n.toByte()
        }
        return out
    }

    /** Hand-written IPv6 literal parser (no zone ids). Never touches the network. */
    private fun parseIpv6(s: String): ByteArray? {
        if (!s.contains(':') || !s.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
        val halves = s.split("::")
        if (halves.size > 2) return null
        val compressed = halves.size == 2
        val head = groups(halves[0], allowV4Tail = !compressed) ?: return null
        val tail = if (compressed) groups(halves[1], allowV4Tail = true) ?: return null else emptyList()
        val total = head.size + tail.size
        if (compressed) { if (total > 7) return null } else if (total != 8) return null
        val all = head + List(8 - total) { 0 } + tail
        val out = ByteArray(16)
        for ((i, g) in all.withIndex()) {
            out[2 * i] = (g shr 8).toByte()
            out[2 * i + 1] = (g and 0xff).toByte()
        }
        return out
    }

    private fun groups(part: String, allowV4Tail: Boolean): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val items = part.split(':')
        val out = ArrayList<Int>()
        for ((i, item) in items.withIndex()) {
            if (item.contains('.')) {
                if (!allowV4Tail || i != items.lastIndex) return null
                val v4 = parseIpv4(item) ?: return null
                out += ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
                out += ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
            } else {
                if (item.isEmpty() || item.length > 4) return null
                out += item.toInt(16)
            }
        }
        return out
    }
}
