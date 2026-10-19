package com.phlox.tvwebbrowser.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the pairing keys before they are stored. The app data (the database too) may be backed up and
 * restored on another device: the key of the Android keystore that wraps them never leaves this one,
 * so restored pairings can not be used (they are paired again).
 */
interface SyncKeyStore {
    fun wrap(key: ByteArray): String

    /** @return null if the key was wrapped on another device or the keystore lost its key */
    fun unwrap(wrapped: String): ByteArray?

    companion object {
        private var testInstance: SyncKeyStore? = null
        private val androidInstance by lazy { AndroidSyncKeyStore() }

        val instance: SyncKeyStore get() = testInstance ?: androidInstance

        /** Robolectric has no Android keystore */
        @VisibleForTesting
        fun setInstanceForTests(keys: SyncKeyStore?) {
            testInstance = keys
        }
    }
}

/** Keys as they are, for tests */
@VisibleForTesting
class PlainSyncKeyStore : SyncKeyStore {
    override fun wrap(key: ByteArray) = SyncCrypto.encode(key)
    override fun unwrap(wrapped: String) = SyncCrypto.decode(wrapped)
}

private class AndroidSyncKeyStore : SyncKeyStore {
    companion object {
        private const val TAG = "SyncKeyStore"
        private const val ALIAS = "tvbro_sync_pairings"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }

    private fun secretKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    override fun wrap(key: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(create = true))
        return SyncCrypto.encode(cipher.iv + cipher.doFinal(key))
    }

    override fun unwrap(wrapped: String): ByteArray? = try {
        val bytes = SyncCrypto.decode(wrapped) ?: return null
        val key = secretKey(create = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_SIZE))
        cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
    } catch (e: Exception) {
        Log.w(TAG, "a pairing key can not be unwrapped", e)
        null
    }
}
