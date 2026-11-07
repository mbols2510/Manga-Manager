package com.mbols2510.mangamanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UrlPolicyTest {

    private fun ok(raw: String): String {
        val r = UrlPolicy.check(raw)
        if (r !is UrlCheck.Ok) fail("expected Ok for '$raw' but got $r")
        return (r as UrlCheck.Ok).normalized.toString()
    }

    private fun rejected(raw: String) {
        val r = UrlPolicy.check(raw)
        assertTrue("expected Rejected for '$raw' but got $r", r is UrlCheck.Rejected)
        assertTrue((r as UrlCheck.Rejected).reason.isNotBlank())
    }

    @Test
    fun allowed_urls_with_normalized_form() {
        val table = mapOf(
            "https://example.com" to "https://example.com/",
            "https://example.com/" to "https://example.com/",
            "HTTPS://Example.com/" to "https://example.com/",
            "https://host/manga" to "https://host/manga/",
            "https://host/manga/" to "https://host/manga/",
            "https://host:443/" to "https://host/",
            "http://192.168.1.10:8080" to "http://192.168.1.10:8080/",
            "http://10.0.0.5" to "http://10.0.0.5/",
            "http://172.16.0.1" to "http://172.16.0.1/",
            "http://172.31.255.255" to "http://172.31.255.255/",
            "http://100.64.0.1" to "http://100.64.0.1/",
            "http://100.127.255.255" to "http://100.127.255.255/",
            "http://127.0.0.1:5000" to "http://127.0.0.1:5000/",
            "http://169.254.10.10" to "http://169.254.10.10/",
            "http://[fd00::1]" to "http://[fd00::1]/",
            "http://[fc00::1]" to "http://[fc00::1]/",
            "http://[fe80::1]" to "http://[fe80::1]/",
            "http://[::1]" to "http://[::1]/",
            "http://localhost" to "http://localhost/",
            "http://LOCALHOST:8000/x" to "http://localhost:8000/x/",
            "http://nas.local" to "http://nas.local/",
            "http://NAS.Local:8080/m" to "http://nas.local:8080/m/",
        )
        for ((raw, expected) in table) assertEquals(raw, expected, ok(raw))
    }

    @Test
    fun ipv4_mapped_ipv6_uses_the_underlying_ipv4() {
        ok("http://[::ffff:192.168.1.1]")
        ok("http://[::ffff:10.0.0.1]:8080/m")
        rejected("http://[::ffff:8.8.8.8]")
        rejected("http://[::ffff:808:808]")
    }

    @Test
    fun rejected_urls() {
        listOf(
            "http://example.com",
            "http://8.8.8.8",
            "http://100.128.0.1",
            "http://100.63.255.255",
            "http://172.32.0.1",
            "http://172.15.255.255",
            "http://192.169.0.1",
            "http://11.0.0.1",
            "http://[2001:db8::1]",
            "http://[fe00::1]",
            "http://[fb00::1]",
            "http://[::ffff:8.8.8.8]",
            "http://[::]",
            "http://localhost.example.com",
            "http://notlocal",
            "http://evil.com/.local",
            "ftp://x",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "",
            "   ",
            "https://",
            "http://",
            "example.com",
            "http://user:pw@192.168.1.1",
            "https://user@example.com",
            "https://exa mple.com",
            "https://example.com/a b",
            "http://192.168.1.10 :8080",
        ).forEach { rejected(it) }
    }

    @Test
    fun surrounding_whitespace_is_trimmed() {
        assertEquals("https://example.com/", ok("  https://example.com \n"))
        assertEquals("http://10.0.0.5/", ok("\thttp://10.0.0.5\r\n"))
    }

    @Test
    fun query_and_fragment_are_stripped() {
        assertEquals("https://example.com/m/", ok("https://example.com/m?token=abc#frag"))
        assertEquals("https://example.com/", ok("https://example.com#x"))
    }

    @Test
    fun very_long_url_is_rejected() {
        rejected("https://example.com/" + "a".repeat(5000))
        rejected("https://" + "a".repeat(300) + ".com")
        assertTrue(UrlPolicy.check("https://example.com/" + "a".repeat(100)) is UrlCheck.Ok)
    }

    @Test
    fun idn_host_is_converted_to_punycode_for_https_and_rejected_for_http() {
        assertEquals("https://xn--bcher-kva.example/", ok("https://bücher.example"))
        rejected("http://bücher.example")
    }

    @Test
    fun odd_ipv4_notations_are_not_treated_as_private() {
        rejected("http://0x7f.0.0.1")
        rejected("http://2130706433")
        rejected("http://127.1")
    }

    @Test
    fun isPrivateHost_table() {
        val priv = listOf(
            "localhost", "LOCALHOST", "nas.local", "a.b.local", "nas.local.",
            "127.0.0.1", "127.255.255.254", "10.1.2.3", "172.16.0.0", "172.31.255.255",
            "192.168.0.1", "169.254.1.1", "100.64.0.0", "100.127.255.255",
            "::1", "[::1]", "fd00::1", "fc00::", "fdff:ffff::1", "fe80::1", "febf::1",
            "::ffff:192.168.1.1", "::ffff:10.0.0.1",
        )
        val pub = listOf(
            "example.com", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.0.1",
            "172.32.0.1", "192.167.0.1", "11.0.0.1", "169.253.0.1", "2001:db8::1",
            "fec0::1", "::ffff:8.8.8.8", "::", "", "local", "foo.localhost",
            "999.1.1.1", "1.2.3", "1.2.3.4.5", "fe80::1%eth0",
        )
        priv.forEach { assertTrue("private: $it", UrlPolicy.isPrivateHost(it)) }
        pub.forEach { assertFalse("public: $it", UrlPolicy.isPrivateHost(it)) }
    }

    @Test
    fun isUnencrypted_follows_scheme() {
        val http = (UrlPolicy.check("http://10.0.0.1") as UrlCheck.Ok).normalized
        val https = (UrlPolicy.check("https://example.com") as UrlCheck.Ok).normalized
        assertTrue(UrlPolicy.isUnencrypted(http))
        assertFalse(UrlPolicy.isUnencrypted(https))
    }
}
