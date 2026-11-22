package com.phlox.tvwebbrowser.webengine

import android.net.Uri

/**
 * Why the site filter blocks a site: the [category] of the list that has it; null for a page of a site that is not
 * among those a parent allowed, while the kids profile opens only those
 */
data class SiteBlock(val category: SiteCategory?)

/**
 * The filter of sites (the kids profile), set by the app in [WebEngineFactory.siteFilter] when the browser starts.
 * Engines ask it for every request of every tab, on their own threads, before ad blocking: a page it blocks shows the
 * block page instead ([SiteBlockPage]), a request of a page it blocks fails. It costs nothing where no filter applies.
 */
interface EngineSiteFilter {
    /**
     * The block of [url] (a page when [isMainFrame], else a request of [pageUrl]); null when it may load.
     *
     * @param pageUrl the page of the tab, whose site the parent may have allowed; null if not known
     */
    fun check(url: Uri, isMainFrame: Boolean, pageUrl: Uri?): SiteBlock?

    /** Whether the filter applies now: engines that leave main frames unchecked must check them then */
    val active: Boolean

    companion object {
        /**
         * The block page asks the app to let a site in through a link to this scheme (`tvbro-site-filter://ask?url=…`),
         * which the engines take from the navigations of pages
         */
        const val ASK_SCHEME = "tvbro-site-filter"

        /** The link of the block page of [url] */
        fun askUrl(url: String): String = "$ASK_SCHEME://ask?url=" + Uri.encode(url)

        /** The page that was blocked, of a link of [ASK_SCHEME]; null for another link */
        fun askedUrl(url: Uri): String? =
            if (url.scheme == ASK_SCHEME && !url.isOpaque) url.getQueryParameter("url")?.takeIf { it.isNotEmpty() } else null
    }
}
