package com.mbols2510.mangamanager.core

interface TokenStore {
    fun save(token: String)
    fun load(): String?
    fun clear()
}

internal interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

internal interface ByteStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

internal class CipherTokenStore(
    private val cipher: TokenCipher,
    private val store: ByteStore,
) : TokenStore {
    override fun save(token: String) = store.write(token.toByteArray(Charsets.UTF_8))
    override fun load(): String? = store.read()?.toString(Charsets.UTF_8)
    override fun clear() = store.delete()
}

internal class AesGcmCipher(private val key: () -> javax.crypto.SecretKey) : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}
