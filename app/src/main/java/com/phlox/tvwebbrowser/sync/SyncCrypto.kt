package com.phlox.tvwebbrowser.sync

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives of the sync channel: random bytes, HKDF-SHA256 (RFC 5869) and AES-256-GCM.
 */
object SyncCrypto {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 16
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_SIZE = 12

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** A random id that is safe in urls and file names */
    fun randomId(): String = encode(randomBytes(16))

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** @return null if the text is not base64url */
    fun decode(text: String): ByteArray? = try {
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 of [length] bytes (at most 32: one block of the expand step is enough here) */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, length: Int = KEY_SIZE): ByteArray {
        require(length <= 32)
        val prk = hmac(salt, secret)
        return hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1)).copyOf(length)
    }

    /**
     * AES-GCM with the [counter] as the IV: each key encrypts its messages in order, the counter
     * of a message is never used twice with the same key
     */
    fun seal(key: ByteArray, counter: Long, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, counter).doFinal(plain)

    /** @throws javax.crypto.AEADBadTagException if the data was not sealed with this key and counter */
    fun open(key: ByteArray, counter: Long, sealed: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, counter).doFinal(sealed)

    private fun cipher(mode: Int, key: ByteArray, counter: Long): Cipher {
        val iv = ByteArray(GCM_IV_SIZE)
        for (i in 0 until 8) {
            iv[GCM_IV_SIZE - 1 - i] = (counter ushr (8 * i)).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }
}
