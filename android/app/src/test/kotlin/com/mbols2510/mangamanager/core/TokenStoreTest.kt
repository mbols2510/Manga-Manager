package com.mbols2510.mangamanager.core

import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreTest {

    private class MemoryByteStore : ByteStore {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun delete() { bytes = null }
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCipher { key }

    private fun newStore(backing: ByteStore = MemoryByteStore()) = CipherTokenStore(cipher, backing)

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private val token = "mm_tok_SuperSecret-0123456789abcdef"

    @Test
    fun save_then_load_returns_same_token() {
        val s = newStore()
        s.save(token)
        assertEquals(token, s.load())
    }

    @Test
    fun saved_token_is_not_plaintext_in_backing_bytes() {
        val backing = MemoryByteStore()
        newStore(backing).save(token)
        val stored = backing.bytes!!
        assertFalse(contains(stored, token.toByteArray(Charsets.UTF_8)))
        assertFalse(contains(stored, "SuperSecret".toByteArray(Charsets.UTF_8)))
        assertTrue(stored.size > token.length)
    }

    @Test
    fun two_saves_of_same_token_differ_because_of_random_iv() {
        val backing = MemoryByteStore()
        val s = newStore(backing)
        s.save(token)
        val first = backing.bytes!!.copyOf()
        s.save(token)
        val second = backing.bytes!!.copyOf()
        assertFalse(first.contentEquals(second))
        assertNotEquals(first.take(12), second.take(12))
        assertEquals(token, s.load())
    }

    @Test
    fun clear_removes_token() {
        val backing = MemoryByteStore()
        val s = newStore(backing)
        s.save(token)
        s.clear()
        assertNull(s.load())
        assertNull(backing.bytes)
    }

    @Test
    fun load_on_empty_storage_returns_null() {
        assertNull(newStore().load())
    }

    @Test
    fun load_on_corrupt_storage_returns_null() {
        val backing = MemoryByteStore()
        val s = newStore(backing)
        for (junk in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(12), ByteArray(40) { 7 })) {
            backing.bytes = junk
            assertNull(s.load())
        }
        s.save(token)
        val b = backing.bytes!!
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        assertNull(s.load())
    }

    @Test
    fun load_with_a_different_key_returns_null() {
        val backing = MemoryByteStore()
        newStore(backing).save(token)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(CipherTokenStore(AesGcmCipher { other }, backing).load())
    }

    @Test
    fun cipher_round_trips_and_prepends_12_byte_iv() {
        val plain = "hello".toByteArray()
        val blob = cipher.encrypt(plain)
        assertEquals(12 + plain.size + 16, blob.size)
        assertArrayEquals(plain, cipher.decrypt(blob))
    }
}
