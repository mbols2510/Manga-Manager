package com.mbols2510.mangamanager.core

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    private fun code(json: String, pad: Boolean = false, prefix: String = "mm1."): String {
        val enc = if (pad) Base64.getUrlEncoder() else Base64.getUrlEncoder().withoutPadding()
        return prefix + enc.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private val good = """{"v":1,"url":"https://example.com/manga","token":"tok_abc-123"}"""

    @Test
    fun parses_valid_code() {
        assertEquals(Pairing("https://example.com/manga", "tok_abc-123"), PairingCode.parse(code(good)))
    }

    @Test
    fun accepts_padded_code() {
        assertEquals(Pairing("https://example.com/manga", "tok_abc-123"), PairingCode.parse(code(good, pad = true)))
    }

    @Test
    fun trims_whitespace_and_inner_newlines() {
        val c = code(good)
        val wrapped = "  \n" + c.substring(0, 10) + "\r\n" + c.substring(10, 20) + "\t " + c.substring(20) + "\n\n"
        assertEquals(Pairing("https://example.com/manga", "tok_abc-123"), PairingCode.parse(wrapped))
    }

    @Test
    fun handles_non_ascii_utf8_values() {
        val j = """{"v":1,"url":"https://bücher.example","token":"tökén"}"""
        assertEquals(Pairing("https://bücher.example", "tökén"), PairingCode.parse(code(j)))
    }

    @Test
    fun ignores_unknown_extra_fields() {
        val j = """{"v":1,"url":"https://e.com","token":"t","extra":[1,2]}"""
        assertEquals(Pairing("https://e.com", "t"), PairingCode.parse(code(j)))
    }

    @Test
    fun rejects_malformed_input_without_throwing() {
        val bad = listOf(
            "",
            "   ",
            "mm1.",
            "mm1",
            "MM1." + code(good).removePrefix("mm1."),
            code(good, prefix = "mm2."),
            code(good, prefix = ""),
            "mm1.!!!not base64!!!",
            "mm1.a",
            "mm1.abcde",
            code("not json"),
            code(""),
            code("[]"),
            code("null"),
            code("\"str\""),
            code("{}"),
            code("""{"v":2,"url":"https://e.com","token":"t"}"""),
            code("""{"v":0,"url":"https://e.com","token":"t"}"""),
            code("""{"url":"https://e.com","token":"t"}"""),
            code("""{"v":"1","url":"https://e.com","token":"t"}"""),
            code("""{"v":1.5,"url":"https://e.com","token":"t"}"""),
            code("""{"v":1,"url":"https://e.com"}"""),
            code("""{"v":1,"url":"https://e.com","token":""}"""),
            code("""{"v":1,"url":"https://e.com","token":"   "}"""),
            code("""{"v":1,"url":"https://e.com","token":null}"""),
            code("""{"v":1,"url":"https://e.com","token":5}"""),
            code("""{"v":1,"url":"","token":"t"}"""),
            code("""{"v":1,"url":"  ","token":"t"}"""),
            code("""{"v":1,"token":"t"}"""),
            code("""{"v":1,"url":["x"],"token":"t"}"""),
            code("""{"v":1,"url":"https://e.com","token":{"a":1}}"""),
            "mm1." + Base64.getUrlEncoder().encodeToString(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00)),
        )
        for (b in bad) assertNull("should be null: '$b'", PairingCode.parse(b))
    }

    @Test
    fun toString_and_interpolation_do_not_leak_the_token() {
        val p = Pairing("https://example.com", "SuperSecretToken123")
        assertFalse(p.toString().contains("SuperSecretToken123"))
        assertFalse("$p".contains("SuperSecretToken123"))
        assertTrue(p.toString().contains("https://example.com"))
        assertEquals(p, p.copy())
        assertEquals(p.hashCode(), p.copy().hashCode())
    }

    @Test
    fun input_size_is_capped() {
        assertNull(PairingCode.parse("mm1." + "A".repeat(5_000_000)))
        assertNull(PairingCode.parse("mm1." + "A".repeat(4093)))
        val big = code("""{"v":1,"url":"https://e.com","token":"${"x".repeat(2700)}"}""")
        assertEquals(true, big.length in 3500..4096)
        assertEquals("x".repeat(2700), PairingCode.parse(big)?.token)
        val tooBig = code("""{"v":1,"url":"https://e.com","token":"${"x".repeat(3100)}"}""")
        assertEquals(true, tooBig.length > 4096)
        assertNull(PairingCode.parse(tooBig))
    }
}
