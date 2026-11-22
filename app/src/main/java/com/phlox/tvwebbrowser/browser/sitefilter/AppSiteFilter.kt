package com.phlox.tvwebbrowser.browser.sitefilter

import android.net.Uri
import java.net.IDN
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import com.phlox.tvwebbrowser.adblock.sitefilter.SiteFilter
import com.phlox.tvwebbrowser.data.HostConfigRepository
import com.phlox.tvwebbrowser.webengine.EngineSiteFilter
import com.phlox.tvwebbrowser.webengine.SiteBlock
import com.phlox.tvwebbrowser.webengine.SiteCategory

/**
 * What the engines ask ([com.phlox.tvwebbrowser.webengine.WebEngineFactory.siteFilter]): the [filter] of the lists
 * ([SiteFilterService.filter]) with the [mask] of the categories the kids profile blocks, only while it is shown;
 * with [allowedOnly] it opens no page of a site a parent did not allow (the [mask] is 0 then: no lists). A site a parent let in ([HostConfigRepository.isSiteAllowed],
 * by its registrable domain, in the database of the kids profile) loads, and so do the requests of its pages.
 * [hostConfigs] gives the site settings of the profile shown (asked for every request: it takes no lock).
 */
class AppSiteFilter(
    private val filter: () -> SiteFilter?,
    private val mask: () -> Long,
    /** The kids profile opens only the pages of the sites a parent allowed */
    private val allowedOnly: () -> Boolean,
    private val hostConfigs: () -> HostConfigRepository,
    private val psl: () -> PublicSuffixList,
    private val now: () -> Long = System::currentTimeMillis,
    private val kidsProfile: () -> Boolean = { AppContext.kidsProfile },
) : EngineSiteFilter {
    override val active: Boolean get() = kidsProfile() && (mask() != 0L || allowedOnly())

    override fun check(url: Uri, isMainFrame: Boolean, pageUrl: Uri?): SiteBlock? {
        if (!kidsProfile()) return null
        val mask = mask()
        //pages only: the frames and requests of an allowed page load
        val onlyAllowed = isMainFrame && allowedOnly()
        if (mask == 0L && !onlyAllowed) return null
        val scheme = url.scheme
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
        val host = url.host?.takeIf { it.isNotEmpty() } ?: return null
        val index = if (mask == 0L) -1 else filter()?.match(host, mask) ?: -1
        if (index < 0 && !onlyAllowed) return null
        val configs = hostConfigs()
        val time = now()
        if (configs.isSiteAllowed(siteOf(host), time)) return null
        if (!isMainFrame) {
            val page = pageUrl?.host
            if (!page.isNullOrEmpty() && configs.isSiteAllowed(siteOf(page), time)) return null
        }
        return SiteBlock(if (index >= 0) SiteCategory.entries[index] else null)
    }

    /** The site a parent lets in for [host]: its registrable domain */
    fun siteOf(host: String): String = psl().registrableDomain(host.lowercase().trimEnd('.'))

    companion object {
        /**
         * The host of what a parent typed to allow a site (`example.com`, `https://www.example.com/page`); null
         * when it is not one of a site of the web
         */
        fun hostOfInput(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val uri = Uri.parse(withScheme)
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            //a name in another script as requests have it
            val host = uri.host?.let { runCatching { IDN.toASCII(it) }.getOrNull() }?.lowercase()?.trimEnd('.') ?: return null
            if (!host.contains('.') || host.startsWith('.') || host.contains("..")) return null
            if (host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' && it != '_' }) return null
            return host
        }
    }
}
