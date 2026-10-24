package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.webengine.EnginePolicies

/**
 * Content blocking decisions for the engine of one tab (requests are checked by the global
 * [com.phlox.tvwebbrowser.webengine.WebEngineFactory.adBlocker]).
 */
class TabPolicies(
    private val tab: BrowserTab,
    private val controller: BrowserController,
) : EnginePolicies {

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
