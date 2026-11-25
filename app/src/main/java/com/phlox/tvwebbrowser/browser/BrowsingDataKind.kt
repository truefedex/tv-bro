package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.webengine.DataClearing
import com.phlox.tvwebbrowser.webengine.DataProfile
import com.phlox.tvwebbrowser.webengine.SiteDataKind

/**
 * What Clear browsing data removes ([BrowserController.clearBrowsingData]), in the order of the rows of its
 * dialog; [siteData]: what the engine clears for it (none: the app's own data)
 */
enum class BrowsingDataKind(val siteData: Set<SiteDataKind> = emptySet()) {
    HISTORY,

    /** The open tabs: the profile shown gets a new one with the home page */
    TABS,

    /** All the bookmarks and folders, those of the home page too */
    BOOKMARKS,

    /** The list of the downloads that ended; their files and the downloads in progress or paused stay */
    DOWNLOADS,

    /** Cookies and stored data of all sites: the user is signed out of them */
    SITE_DATA(setOf(SiteDataKind.COOKIES, SiteDataKind.STORAGE)),

    /**
     * What the user set for each site (popup blocking, ad blocking, the view, the player, the zoom); the icons of
     * sites and the sites a parent allowed in the kids profile stay
     */
    SITE_SETTINGS,

    CACHE(setOf(SiteDataKind.CACHE));

    /** The engine clears it ([BrowsingDataChoice] asks the engine how) */
    val ofEngine: Boolean get() = siteData.isNotEmpty()
}

/**
 * The choice of Clear browsing data, without its look: a check box for each kind of data ([BrowsingDataKind],
 * the rows) in each of the [profiles] shown (the columns: the main one, the kids one while it has data, the
 * incognito mode while it is on). A box is disabled where nothing can be cleared: the incognito mode has only its
 * tabs and the data of its pages (its bookmarks, downloads and site settings are those of the main profile, and it
 * keeps no history), and the engine may not clear the data of its pages ([DataClearing.NONE]). The engine may clear the
 * data of some profiles only together ([DataClearing.WITH_OTHERS]): their boxes of that kind are checked and
 * unchecked together. The boxes checked are remembered ([remembered], as `PROFILE:KIND` names; boxes of
 * profiles not shown keep theirs), the cookies not checked at first: being signed out of all sites is chosen.
 */
class BrowsingDataChoice(
    val profiles: List<DataProfile>,
    private val clearing: (DataProfile) -> DataClearing,
    remembered: Set<String>?
) {
    companion object {
        val DEFAULT: Set<String> = setOf(nameOf(DataProfile.MAIN, BrowsingDataKind.HISTORY),
            nameOf(DataProfile.MAIN, BrowsingDataKind.CACHE))

        fun nameOf(profile: DataProfile, kind: BrowsingDataKind) = "${profile.name}:${kind.name}"
    }

    private val saved: Set<String> = remembered ?: DEFAULT
    private val checked = HashSet<Pair<DataProfile, BrowsingDataKind>>()

    init {
        for (profile in profiles) for (kind in BrowsingDataKind.entries) {
            if (isEnabled(profile, kind) && nameOf(profile, kind) in saved) checked += profile to kind
        }
        //a box of profiles cleared together checks the others
        for (kind in BrowsingDataKind.entries) {
            val together = togetherWith(profiles.firstOrNull { isChecked(it, kind) && linked(it, kind) } ?: continue, kind)
            together.forEach { checked += it to kind }
        }
    }

    fun isEnabled(profile: DataProfile, kind: BrowsingDataKind): Boolean = when {
        kind.ofEngine -> clearing(profile) != DataClearing.NONE
        kind == BrowsingDataKind.TABS -> true
        else -> profile != DataProfile.INCOGNITO
    }

    fun isChecked(profile: DataProfile, kind: BrowsingDataKind) = (profile to kind) in checked

    /** Whether the box goes with boxes of other profiles shown */
    fun linked(profile: DataProfile, kind: BrowsingDataKind): Boolean = togetherWith(profile, kind).size > 1

    /** Some boxes go with boxes of other profiles: the dialog says so */
    val hasLinks: Boolean get() = profiles.any { p -> BrowsingDataKind.entries.any { linked(p, it) } }

    /** Checks or unchecks the box, and those that go with it */
    fun toggle(profile: DataProfile, kind: BrowsingDataKind) {
        if (!isEnabled(profile, kind)) return
        val check = !isChecked(profile, kind)
        for (p in togetherWith(profile, kind)) {
            if (check) checked += p to kind else checked -= p to kind
        }
    }

    val anyChecked: Boolean get() = checked.isNotEmpty()

    /** The kinds of data to clear in each profile */
    fun chosen(): Map<DataProfile, Set<BrowsingDataKind>> =
        checked.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    /** What to remember: the boxes of the profiles shown, and the ones saved of the others */
    fun remembered(): Set<String> {
        val shown = profiles.map { it.name + ":" }
        return saved.filterTo(HashSet()) { name -> shown.none { name.startsWith(it) } } +
            checked.map { nameOf(it.first, it.second) }
    }

    private fun togetherWith(profile: DataProfile, kind: BrowsingDataKind): List<DataProfile> {
        if (!kind.ofEngine || clearing(profile) != DataClearing.WITH_OTHERS) return listOf(profile)
        return profiles.filter { clearing(it) == DataClearing.WITH_OTHERS }
    }
}
