package com.mbols2510.mangamanager.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenStore {
    fun save(token: String)
    fun load(): String?
    fun clear()
}

/**
 * Keeps the token in memory so request paths never touch the Keystore. [warmUp] does the one decrypt (call it off
 * the main thread, at startup); [save] and [clear] write through to [delegate] and update the cache. [load] and
 * [cachedToken] only read memory and are null while cold. The token is never logged and not in any toString().
 */
class CachingTokenStore(private val delegate: TokenStore) : TokenStore {
    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var token: String? = null

    /** True once the token state is known (decrypted by [warmUp] or written through this store). */
    val isLoaded: Boolean get() = loaded

    val cachedToken: String? get() = token

    /** Blocking Keystore read; no lock is held while it runs. A newer [save] or [clear] wins over a late read. */
    fun warmUp() {
        val read = delegate.load()
        synchronized(lock) {
            if (!loaded) {
                token = read
                loaded = true
            }
        }
    }

    override fun save(token: String) {
        delegate.save(token)
        put(token)
    }

    override fun load(): String? = token

    /** The cached token is dropped even when the Keystore call throws (the exception still propagates). */
    override fun clear() {
        try {
            delegate.clear()
        } finally {
            put(null)
        }
    }

    private fun put(value: String?) = synchronized(lock) {
        token = value
        loaded = true
    }

    override fun toString(): String = "CachingTokenStore"
}

/** Seam so the encryption logic runs on the JVM. Blob layout: 12-byte IV, then ciphertext and GCM tag. */
internal interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** Raw persistence of one opaque blob. */
internal interface ByteStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

/** Encrypts the token before it reaches [ByteStore]. [load] never throws: any failure means "no token". */
internal class CipherTokenStore(
    private val cipher: TokenCipher,
    private val store: ByteStore,
) : TokenStore {
    override fun save(token: String) {
        store.write(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
    }

    override fun load(): String? = try {
        store.read()?.let { String(cipher.decrypt(it), Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }

    override fun clear() = store.delete()
}

/** AES-256-GCM. The key comes from [key] (Keystore in production, a plain key in tests). The IV is chosen by the cipher. */
internal class AesGcmCipher(private val key: () -> SecretKey) : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        check(iv.size == IV_BYTES) { "unexpected IV size" }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= IV_BYTES + TAG_BYTES) { "blob too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, blob, 0, IV_BYTES))
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

// Android-only glue below. It needs the Keystore and SharedPreferences, so it is not unit-tested on the JVM.

private class KeystoreKey(private val alias: String) : () -> SecretKey {
    override fun invoke(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

private class SharedPrefsByteStore(context: Context, name: String) : ByteStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(): ByteArray? = prefs.getString(KEY, null)?.let { Base64.getDecoder().decode(it) }

    override fun write(bytes: ByteArray) {
        prefs.edit().putString(KEY, Base64.getEncoder().encodeToString(bytes)).apply()
    }

    override fun delete() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "t"
    }
}

/** Production store: non-exportable AES-GCM Keystore key, ciphertext in app-private SharedPreferences (excluded from backup). */
class KeystoreTokenStore(context: Context) : TokenStore by CipherTokenStore(
    AesGcmCipher(KeystoreKey("mm_device_token_key")),
    SharedPrefsByteStore(context, "mm_secure"),
)
