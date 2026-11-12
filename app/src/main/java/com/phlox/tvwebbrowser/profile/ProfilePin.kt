package com.phlox.tvwebbrowser.profile

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The PIN that lets the user leave the kids profile: [LENGTH] digits, of which only a salted hash is kept.
 * Wrong tries lead to a pause that grows and is kept with the tries, so a restart does not end it. No
 * views and no Android: [store] is where it is kept, [now] the wall clock in milliseconds.
 */
class ProfilePin(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {
    /** A few strings by their names (a preferences file of its own) */
    interface Store {
        fun get(key: String): String?
        /** Sets the [values]; a null removes its key. All of them are written at once and before it returns. */
        fun put(values: Map<String, String?>)
    }

    sealed interface Result {
        data object Right : Result
        /** [triesLeft] before a pause; [waitMs] is the pause that has just started, or 0 */
        data class Wrong(val triesLeft: Int, val waitMs: Long) : Result
        /** A pause goes on: the PIN was not checked */
        data class Wait(val waitMs: Long) : Result
    }

    val isSet: Boolean get() = store.get(KEY_HASH) != null

    /** Replaces the PIN; the wrong tries are forgotten */
    fun set(pin: String) {
        require(isValid(pin))
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        store.put(mapOf(KEY_SALT to hex(salt), KEY_HASH to hex(hash(pin, salt)), KEY_FAILS to null, KEY_LAST_FAIL to null))
    }

    fun remove() = store.put(mapOf(KEY_SALT to null, KEY_HASH to null, KEY_FAILS to null, KEY_LAST_FAIL to null))

    private val fails: Int get() = store.get(KEY_FAILS)?.toIntOrNull() ?: 0

    /** Milliseconds until the next try is allowed, 0 when it is */
    fun waitLeft(): Long {
        val fails = fails
        if (fails < FREE_TRIES) return 0
        val last = store.get(KEY_LAST_FAIL)?.toLongOrNull() ?: 0L
        val time = now()
        if (time < last) {
            //the clock was turned back: the pause starts again
            store.put(mapOf(KEY_LAST_FAIL to time.toString()))
            return pauseAfter(fails)
        }
        return (last + pauseAfter(fails) - time).coerceAtLeast(0)
    }

    fun check(pin: String): Result {
        waitLeft().let { if (it > 0) return Result.Wait(it) }
        val salt = store.get(KEY_SALT)?.let(::unhex)
        val stored = store.get(KEY_HASH)?.let(::unhex)
        if (salt != null && stored != null && isValid(pin) && MessageDigest.isEqual(hash(pin, salt), stored)) {
            store.put(mapOf(KEY_FAILS to null, KEY_LAST_FAIL to null))
            return Result.Right
        }
        val fails = fails + 1
        store.put(mapOf(KEY_FAILS to fails.toString(), KEY_LAST_FAIL to now().toString()))
        return Result.Wrong((FREE_TRIES - fails).coerceAtLeast(0), pauseAfter(fails))
    }

    companion object {
        const val LENGTH = 4
        /** Wrong tries before the first pause (typos) */
        const val FREE_TRIES = 5
        private const val FIRST_PAUSE_MS = 30_000L
        private const val LONGEST_PAUSE_MS = 30 * 60_000L
        private const val ITERATIONS = 10_000
        private const val SALT_BYTES = 16
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_FAILS = "pin_fails"
        private const val KEY_LAST_FAIL = "pin_last_fail"

        fun isValid(pin: String) = pin.length == LENGTH && pin.all { it in '0'..'9' }

        /** 30 s after the fifth wrong try in a row, twice as long after each next one, 30 minutes at most */
        fun pauseAfter(fails: Int): Long =
            if (fails < FREE_TRIES) 0
            else (FIRST_PAUSE_MS shl (fails - FREE_TRIES).coerceAtMost(10)).coerceAtMost(LONGEST_PAUSE_MS)

        private fun hash(pin: String, salt: ByteArray): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun unhex(text: String): ByteArray? = runCatching {
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
