package com.phlox.tvwebbrowser.profile

import com.phlox.tvwebbrowser.AppContext

/**
 * What the profile of this browser process lets its user do. The kids profile has its own pages data and
 * its own database (tabs, history, bookmarks, site settings), so what it keeps is its own; what is refused
 * there is what reaches out of it: the settings of the app, other apps, files, and the ways to leave no
 * trace. Shells do not show what is refused, and the activity refuses it again where it is done (hotkeys,
 * intents and pages reach those places too). A new feature that reaches out of the profile gets its
 * question here.
 */
class ProfilePolicy(val kids: Boolean) {
    /** The settings of the app (also those of the kids profile and its PIN) */
    val settings: Boolean get() = !kids
    /** No history is kept there */
    val incognito: Boolean get() = !kids
    /** Files of pages go to the storage of the device; opening and installing them */
    val downloads: Boolean get() = !kids
    /** Pages and files sent to the TV from other devices */
    val sendToTv: Boolean get() = !kids
    /** Links and videos opened in other apps */
    val externalApps: Boolean get() = !kids
    /** Deleting visits from the history */
    val deleteHistory: Boolean get() = !kids
    /** The built-in player in a window over other apps: its activity belongs to the main profile */
    val pictureInPicture: Boolean get() = !kids
    /** Checking for a new version and installing it */
    val appUpdates: Boolean get() = !kids
    /** The channels of the TV home screen show what the main profile keeps */
    val homeChannels: Boolean get() = !kids
    /** The system fills forms with what it saved (addresses, cards) */
    val autofill: Boolean get() = !kids

    companion object {
        /** Of this process */
        val current: ProfilePolicy get() = ProfilePolicy(AppContext.kidsProfile)
    }
}
