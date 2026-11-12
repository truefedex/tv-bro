package com.phlox.tvwebbrowser.activity

import com.phlox.tvwebbrowser.activity.main.BrowserSpace
import com.phlox.tvwebbrowser.activity.main.MainActivity

/**
 * The browser in the kids profile: the same as [MainActivity], in a process of its own (`:kids`), where the
 * engine keeps the data of pages apart and the app has its own database and tab files. What the profile
 * refuses is in [com.phlox.tvwebbrowser.profile.ProfilePolicy]. Its process stays when the activity
 * finishes, as the main one does: a link of another app starts a new activity in it while the old one
 * is destroyed.
 */
class KidsProfileMainActivity : MainActivity() {
    override val space: BrowserSpace get() = BrowserSpace.KIDS
}
