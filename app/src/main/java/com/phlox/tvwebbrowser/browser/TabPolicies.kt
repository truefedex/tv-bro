package com.phlox.tvwebbrowser.browser

import android.net.Uri
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.webengine.EnginePolicies

/**
 * Checks requests against the ads blocking filters.
 */
interface AdBlocker {
    fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean
}

/**
 * Content blocking decisions for the engine of one tab.
 */
class TabPolicies(
    private val tab: BrowserTab,
    private val controller: BrowserController,
    private val adBlocker: AdBlocker
) : EnginePolicies {

    override fun isAd(url: Uri, acceptHeader: String?, baseUri: Uri): Boolean? {
        return adBlocker.isAd(url, acceptHeader, baseUri)
    }

    /** The setting of the host of this tab's page; asked on the engine's threads */
    override fun isAdBlockingEnabled(): Boolean = controller.isAdBlockEnabled(tab)

    override fun isDialogsBlockingEnabled(): Boolean {
        if (tab.url == Config.HOME_PAGE_URL) return false
        return shouldBlockNewWindow(dialog = true, userGesture = false)
    }

    override fun shouldBlockNewWindow(dialog: Boolean, userGesture: Boolean): Boolean {
        val hostConfig = controller.findHostConfig(tab)
        return PopupBlockPolicy.shouldBlockNewWindow(hostConfig?.popupBlockLevel, dialog, userGesture)
    }

    override fun isDesktopView(url: String): Boolean = controller.isDesktopView(url)
}
