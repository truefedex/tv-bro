package com.phlox.tvwebbrowser.profile

import com.phlox.tvwebbrowser.webengine.SiteCategory
import android.content.Context
import android.content.SharedPreferences
import com.phlox.tvwebbrowser.R

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

    /** The categories of sites the kids profile does not open ([SiteCategory.CUSTOM] by [kidsSiteListUrl]); all lists by default */
    var kidsSiteCategories: Set<SiteCategory>
        get() = store.get(KEY_KIDS_SITE_CATEGORIES)?.split(',')?.mapNotNull { SiteCategory.ofId(it) }?.toSet()
            ?: SiteCategory.listed.toSet()
        set(value) = store.put(mapOf(KEY_KIDS_SITE_CATEGORIES to value.joinToString(",") { it.id }))

    /** The address of the parent's own list of sites the kids profile does not open; "" for none */
    var kidsSiteListUrl: String
        get() = store.get(KEY_KIDS_SITE_LIST_URL) ?: ""
        set(value) = store.put(mapOf(KEY_KIDS_SITE_LIST_URL to value.trim().ifEmpty { null }))

    /**
     * The kids profile opens only the pages of the sites a parent allowed (Ask a parent, Settings), not the rest of
     * the web; off by default
     */
    var kidsAllowedOnly: Boolean
        get() = store.get(KEY_KIDS_ALLOWED_ONLY) == "1"
        set(value) = store.put(mapOf(KEY_KIDS_ALLOWED_ONLY to if (value) "1" else null))

    /** The kids profile may download files and install apps (its own downloads only); on by default */
    var kidsDownloads: Boolean
        get() = store.get(KEY_KIDS_DOWNLOADS) != "0"
        set(value) = store.put(mapOf(KEY_KIDS_DOWNLOADS to if (value) null else "0"))

    /**
     * When the app starts, the browser asks who is watching (the kids profile or the main one, which needs the PIN
     * while it is locked); on by default
     */
    var askWhoIsWatching: Boolean
        get() = store.get(KEY_ASK_WHO_IS_WATCHING) != "0"
        set(value) = store.put(mapOf(KEY_ASK_WHO_IS_WATCHING to if (value) null else "0"))

    /**
     * The name a parent gave the kids profile (the child's name: some children do not like to be in a "kids"
     * anything); null for the default, `R.string.profile_kids`, which follows the language of the system
     */
    var kidsName: String?
        get() = store.get(KEY_KIDS_NAME)?.takeIf { it.isNotBlank() }
        set(value) = store.put(mapOf(KEY_KIDS_NAME to value?.trim()?.take(MAX_NAME_LENGTH)?.ifEmpty { null }))

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
        private const val KEY_KIDS_SITE_CATEGORIES = "kids_site_categories"
        private const val KEY_KIDS_SITE_LIST_URL = "kids_site_list_url"
        private const val KEY_KIDS_ALLOWED_ONLY = "kids_allowed_only"
        private const val KEY_MAIN_LEFT_AT = "main_left_at"
        private const val KEY_ASK_WHO_IS_WATCHING = "ask_who_is_watching"
        private const val KEY_KIDS_NAME = "kids_name"
        const val MAX_NAME_LENGTH = 30

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

/** The name of the kids profile where it is shown: the one a parent gave it, else "Kids profile" */
fun ProfileLock.kidsTitle(context: Context): String = kidsName ?: context.getString(R.string.profile_kids)
