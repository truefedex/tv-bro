package com.phlox.tvwebbrowser.browser.adblock

import android.net.Uri
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.adblock.MatchResult
import com.phlox.tvwebbrowser.adblock.ResourceType
import com.phlox.tvwebbrowser.data.HostConfigRepository
import com.phlox.tvwebbrowser.webengine.EngineAdBlocker

/**
 * What the engines ask ([com.phlox.tvwebbrowser.webengine.WebEngineFactory.adBlocker]): the engine of the
 * [service], unless ads are shown on the site of the tab (its own setting, else the global one).
 * The repository is given once: getting it from the container takes a lock, and this runs for every request.
 */
class AppEngineAdBlocker(
    private val service: AdBlockService,
    private val hostConfigs: HostConfigRepository,
    private val config: Config,
) : EngineAdBlocker {

    override fun shouldBlock(url: Uri, type: String?, acceptHeader: String?, isMainFrame: Boolean, documentUrl: Uri?,
                             topUrl: Uri?): Boolean {
        val engine = service.engine ?: return false
        if (!isEnabledOn(if (isMainFrame) url else topUrl ?: documentUrl)) return false
        val host = url.host ?: return false
        val urlString = url.toString()
        val types = if (type != null) ResourceType.fromWebExtension(type) else
            ResourceType.guessFromAccept(acceptHeader, urlString, isMainFrame)
        //a page is first-party to itself
        val page = if (isMainFrame) url else documentUrl
        return engine.match(urlString, host, page?.toString(), page?.host, types) == MatchResult.BLOCK
    }

    override val blocksPages: Boolean get() = service.engine?.blocksPages ?: false

    /** Whether ads are blocked on the [page]: the setting of its host, else the global one; never on the home page */
    fun isEnabledOn(page: Uri?): Boolean {
        val pageString = page?.toString()
        if (pageString != null && pageString.startsWith(Config.HOME_PAGE_URL)) return false
        val host = page?.host?.takeIf { it.isNotEmpty() } ?: return config.adBlockEnabled
        return hostConfigs.adBlockSetting(host) ?: config.adBlockEnabled
    }
}
