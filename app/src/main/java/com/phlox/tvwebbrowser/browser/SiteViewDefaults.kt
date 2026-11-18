package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.webengine.SiteView
import java.util.Locale

/**
 * The site view of sites that look better in another one than the default: a site of the list (or a subdomain of it)
 * without a setting of its own gets its view, whatever the default ([BrowserController.defaultSiteView]) is;
 * the user can still choose another one for it in the site settings.
 */
object SiteViewDefaults {
    /** Sites and their view */
    private val SITES = mapOf(
        //the desktop pages of Qwant (home and results) are made for big screens: on a TV their text is tiny
        "qwant.com" to SiteView.MOBILE
    )

    /** The view of the [host] by the list, null when it is not in it */
    fun siteView(host: String): SiteView? {
        var name = host.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            SITES[name]?.let { return it }
            val dot = name.indexOf('.')
            if (dot < 0) return null
            name = name.substring(dot + 1)
        }
    }
}
