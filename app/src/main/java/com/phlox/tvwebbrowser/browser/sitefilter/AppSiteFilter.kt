package com.phlox.tvwebbrowser.browser.sitefilter

import android.net.Uri
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import com.phlox.tvwebbrowser.adblock.sitefilter.SiteFilter
import com.phlox.tvwebbrowser.data.HostConfigRepository
import com.phlox.tvwebbrowser.webengine.EngineSiteFilter
import com.phlox.tvwebbrowser.webengine.SiteBlock
import com.phlox.tvwebbrowser.webengine.SiteCategory

/**
 * What the engines ask ([com.phlox.tvwebbrowser.webengine.WebEngineFactory.siteFilter]): the [filter] of the lists
 * ([SiteFilterService.filter]) with the [mask] of the categories the kids profile blocks, only while it is shown. A site a parent let in ([HostConfigRepository.isSiteAllowed],
 * by its registrable domain, in the database of the kids profile) loads, and so do the requests of its pages.
 * [hostConfigs] gives the site settings of the profile shown (asked for every request: it takes no lock).
 */
class AppSiteFilter(
    private val filter: () -> SiteFilter?,
    private val mask: () -> Long,
    private val hostConfigs: () -> HostConfigRepository,
    private val psl: () -> PublicSuffixList,
    private val now: () -> Long = System::currentTimeMillis,
    private val kidsProfile: () -> Boolean = { AppContext.kidsProfile },
) : EngineSiteFilter {
    override val active: Boolean get() = kidsProfile() && mask() != 0L

    override fun check(url: Uri, isMainFrame: Boolean, pageUrl: Uri?): SiteBlock? {
        if (!kidsProfile()) return null
        val mask = mask()
        if (mask == 0L) return null
        val scheme = url.scheme
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
        val host = url.host?.takeIf { it.isNotEmpty() } ?: return null
        val filter = filter() ?: return null
        val index = filter.match(host, mask)
        if (index < 0) return null
        val configs = hostConfigs()
        val time = now()
        if (configs.isSiteAllowed(siteOf(host), time)) return null
        if (!isMainFrame) {
            val page = pageUrl?.host
            if (!page.isNullOrEmpty() && configs.isSiteAllowed(siteOf(page), time)) return null
        }
        return SiteBlock(SiteCategory.entries[index])
    }

    /** The site a parent lets in for [host]: its registrable domain */
    fun siteOf(host: String): String = psl().registrableDomain(host.lowercase().trimEnd('.'))
}
