package com.phlox.tvwebbrowser.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * Which profile the browser is to show, the main one or the kids one, and what keeps the main one locked.
 * The kids profile is on while its [pin] is set. Then the browser shows the kids profile unless the PIN
 * was entered ([enterMain]), and the main profile is locked again when the browser leaves the screen
 * ([LockDelay]). Entering the kids profile never needs the PIN.
 *
 * It is kept in a preferences file of its own, which backups leave out (`backup_rules.xml`): a restored
 * copy with the profile on and no PIN, or with the PIN of another device, would be worse than none.
 */
class ProfileLock(private val store: ProfilePin.Store, private val now: () -> Long = System::currentTimeMillis) {
    /** When the main profile is locked after the browser left the screen */
    enum class LockDelay(val millis: Long) {
        IMMEDIATELY(0), MINUTES_5(5 * 60_000L), MINUTES_30(30 * 60_000L),
        /** Only by the user (Kids profile in the menu) */
        MANUAL(Long.MAX_VALUE)
    }

    val pin = ProfilePin(store, now)

    /** The kids profile is on (a PIN is set) */
    val enabled: Boolean get() = pin.isSet

    /** The browser is to show the kids profile */
    val kidsActive: Boolean get() = enabled && store.get(KEY_KIDS_ACTIVE) != "0"

    var lockDelay: LockDelay
        get() = LockDelay.entries.firstOrNull { it.name == store.get(KEY_LOCK_DELAY) } ?: LockDelay.IMMEDIATELY
        set(value) = store.put(mapOf(KEY_LOCK_DELAY to value.name))

    /** The kids profile may download files and install apps (its own downloads only); on by default */
    var kidsDownloads: Boolean
        get() = store.get(KEY_KIDS_DOWNLOADS) != "0"
        set(value) = store.put(mapOf(KEY_KIDS_DOWNLOADS to if (value) null else "0"))

    /** Turns the kids profile on with the [newPin], or changes its PIN; the main profile stays unlocked */
    fun enable(newPin: String) {
        val wasEnabled = enabled
        pin.set(newPin)
        if (!wasEnabled) store.put(mapOf(KEY_KIDS_ACTIVE to "0", KEY_MAIN_LEFT_AT to null))
    }

    /** Turns the kids profile off: nothing is locked any more (its data stay) */
    fun disable() {
        pin.remove()
        store.put(mapOf(KEY_KIDS_ACTIVE to null, KEY_MAIN_LEFT_AT to null))
    }

    /** The PIN was entered: the main profile is unlocked */
    fun enterMain() = store.put(mapOf(KEY_KIDS_ACTIVE to "0", KEY_MAIN_LEFT_AT to null))

    /** The user goes to the kids profile, or the main one is locked */
    fun enterKids() {
        if (enabled) store.put(mapOf(KEY_KIDS_ACTIVE to "1", KEY_MAIN_LEFT_AT to null))
    }

    /** The browser left the screen while it showed the main profile */
    fun onMainLeftScreen() {
        if (!enabled || kidsActive) return
        when (lockDelay) {
            LockDelay.IMMEDIATELY -> enterKids()
            LockDelay.MANUAL -> {}
            else -> store.put(mapOf(KEY_MAIN_LEFT_AT to now().toString()))
        }
    }

    /** The browser is back on the screen with the main profile: locks it if it was away for too long */
    fun onMainBackOnScreen() {
        val leftAt = store.get(KEY_MAIN_LEFT_AT)?.toLongOrNull() ?: return
        val delay = lockDelay
        val time = now()
        //a clock turned back locks too
        if (delay != LockDelay.MANUAL && (time < leftAt || time - leftAt >= delay.millis)) {
            enterKids()
        } else {
            store.put(mapOf(KEY_MAIN_LEFT_AT to null))
        }
    }

    /**
     * The process of the app starts and nothing tells that the user was just there (the
     * device was switched off, the process died): the main profile is locked, unless only the user locks it
     */
    fun onMainColdStart() {
        if (!enabled || kidsActive || lockDelay == LockDelay.MANUAL) return
        //the system killed the process after the browser left the screen: the delay counts from then
        if (store.get(KEY_MAIN_LEFT_AT) != null) onMainBackOnScreen() else enterKids()
    }

    companion object {
        const val PREFS_NAME = "profile_lock"
        private const val KEY_KIDS_ACTIVE = "kids_active"
        private const val KEY_LOCK_DELAY = "lock_delay"
        private const val KEY_KIDS_DOWNLOADS = "kids_downloads"
        private const val KEY_MAIN_LEFT_AT = "main_left_at"

        @Volatile
        private var instance: ProfileLock? = null

        fun get(context: Context): ProfileLock = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: ProfileLock(PrefsStore(app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)))
                .also { instance = it }
        }
    }

    private class PrefsStore(private val prefs: SharedPreferences) : ProfilePin.Store {
        override fun get(key: String): String? = prefs.getString(key, null)

        override fun put(values: Map<String, String?>) {
            val editor = prefs.edit()
            for ((key, value) in values) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            //written at once: the process may be killed right after a wrong PIN or a lock
            editor.commit()
        }
    }
}
