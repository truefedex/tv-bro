package com.phlox.tvwebbrowser.webengine

/**
 * What [WebEngineProviderCallback.clearSiteData] removes of a site, and
 * [WebEngineProviderCallback.clearBrowsingData] of all sites
 */
enum class SiteDataKind {
    /** Cookies and sign-ins: the user is signed out of the site */
    COOKIES,

    /** Local storage, IndexedDB, Cache Storage, service workers and the like */
    STORAGE,

    /** The HTTP cache (no data of the user) */
    CACHE;

    companion object {
        val ALL: Set<SiteDataKind> = entries.toSet()
    }
}
