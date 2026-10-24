package com.phlox.tvwebbrowser.browser.adblock

import android.net.Uri
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.adblock.MatchResult
import com.phlox.tvwebbrowser.adblock.ResourceType
import com.phlox.tvwebbrowser.data.HostConfigRepository
import com.phlox.tvwebbrowser.webengine.EngineAdBlocker
import com.phlox.tvwebbrowser.webengine.PageHidingStyles
import com.phlox.tvwebbrowser.webengine.ScriptEngineFile
import com.phlox.tvwebbrowser.webengine.SiteAdBlockSettings
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

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
        val page = if (isMainFrame) url else pageOf(documentUrl, topUrl)
        return engine.match(urlString, host, page?.toString(), page?.host, types) == MatchResult.BLOCK
    }

    /**
     * The page whose filters apply in a document: the document, or for a frame of about:blank, about:srcdoc,
     * data: or blob: the page of the tab that made it (its ads would else be of no site, and no third-party or
     * site filter would apply)
     */
    private fun pageOf(documentUrl: Uri?, topUrl: Uri?): Uri? =
        if (documentUrl != null && isWebUrl(documentUrl)) documentUrl else topUrl ?: documentUrl

    override fun hidingStyles(documentUrl: Uri, topUrl: Uri?): PageHidingStyles {
        val engine = service.engine ?: return PageHidingStyles.NONE
        if (!isEnabledOn(topUrl ?: documentUrl)) return PageHidingStyles.NONE
        val page = pageOf(documentUrl, topUrl)?.takeIf(::isWebUrl) ?: return PageHidingStyles.NONE
        val styles = engine.hidingStyles(page.toString(), page.host!!)
        return PageHidingStyles(styles.css, styles.generic)
    }

    override fun genericHidingStyles(documentUrl: Uri, topUrl: Uri?, classes: List<String>, ids: List<String>): String {
        val engine = service.engine ?: return ""
        if (!isEnabledOn(topUrl ?: documentUrl)) return ""
        val page = pageOf(documentUrl, topUrl)?.takeIf(::isWebUrl) ?: return ""
        return engine.genericHidingStyles(page.toString(), page.host!!, classes, ids)
    }

    private fun isWebUrl(uri: Uri): Boolean {
        val scheme = uri.scheme
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }

    override val blocksPages: Boolean get() = service.engine?.blocksPages ?: false

    override val scriptEngineFingerprint: Long? get() = service.engine?.fingerprint

    override fun scriptEngineFile(): ScriptEngineFile? =
        service.engine?.let { ScriptEngineFile(it.fingerprint, it.requestFiltersFile()) }

    override val siteSettings: SiteAdBlockSettings?
        get() = hostConfigs.adBlockSettings()?.let { SiteAdBlockSettings(config.adBlockEnabled, it, Config.HOME_PAGE_URL) }

    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val notifyListeners = Runnable { listeners.forEach { it.run() } }
    //kept here: preferences hold their listeners weakly
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Config.ADBLOCK_ENABLED_PREF_KEY) notifyListeners.run()
    }

    init {
        service.addEngineListener(notifyListeners)
        hostConfigs.addAdBlockSettingsListener(notifyListeners)
        config.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun addChangeListener(listener: Runnable) {
        listeners += listener
    }

    override fun removeChangeListener(listener: Runnable) {
        listeners -= listener
    }

    /** Whether ads are blocked on the [page]: the setting of its host, else the global one; never on the home page */
    fun isEnabledOn(page: Uri?): Boolean {
        val pageString = page?.toString()
        if (pageString != null && pageString.startsWith(Config.HOME_PAGE_URL)) return false
        val host = page?.host?.takeIf { it.isNotEmpty() } ?: return config.adBlockEnabled
        return hostConfigs.adBlockSetting(host) ?: config.adBlockEnabled
    }
}
