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
    private val adBlocker: AdBlocker,
    private val config: Config
) : EnginePolicies {

    override fun isAd(url: Uri, acceptHeader: String?, baseUri: Uri): Boolean? {
        return adBlocker.isAd(url, acceptHeader, baseUri)
    }

    // current behavior: the setting of the current tab is used, not of the tab this engine belongs to
    // (engines ask only while their tab is the current one)
    override fun isAdBlockingEnabled(): Boolean {
        return controller.currentTab.value?.adblock ?: config.adBlockEnabled
    }

    override fun isDialogsBlockingEnabled(): Boolean {
        if (tab.url == Config.HOME_PAGE_URL) return false
        return shouldBlockNewWindow(dialog = true, userGesture = false)
    }

    override fun shouldBlockNewWindow(dialog: Boolean, userGesture: Boolean): Boolean {
        val hostConfig = controller.findHostConfig(tab)
        return PopupBlockPolicy.shouldBlockNewWindow(hostConfig?.popupBlockLevel, dialog, userGesture)
    }
}
