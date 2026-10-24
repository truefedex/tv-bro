package com.phlox.tvwebbrowser.webengine

import android.net.Uri

/**
 * Ad blocking for the engines, set by the app in [WebEngineFactory.adBlocker] when it starts. Engines ask
 * it for every request of every tab (current or not), on their own threads; it knows the site settings.
 */
interface EngineAdBlocker {
    /**
     * Whether to block the request.
     *
     * @param type the type of the request as the WebExtensions API names it (GeckoView); null when the
     *  engine does not tell (WebView)
     * @param acceptHeader the Accept header, for engines that do not tell the type
     * @param isMainFrame the request loads the page of the tab itself
     * @param documentUrl the page or frame that made the request (WebView knows only the page); null if not known
     * @param topUrl the page of the tab, whose site settings apply; null if not known
     */
    fun shouldBlock(url: Uri, type: String?, acceptHeader: String?, isMainFrame: Boolean, documentUrl: Uri?,
                    topUrl: Uri?): Boolean

    /** Whether some filters block pages themselves: engines that can leave main frames unchecked must check them */
    val blocksPages: Boolean

    /**
     * The element hiding of a page or frame at [documentUrl], in the tab of [topUrl] (whose site setting applies):
     * the CSS to put into it at once, and whether to ask [genericHidingStyles] for its classes and ids.
     * Called on threads of the engine.
     */
    fun hidingStyles(documentUrl: Uri, topUrl: Uri?): PageHidingStyles

    /** The CSS hiding the elements of these [classes] and [ids] (as they appear) on a page of [hidingStyles] */
    fun genericHidingStyles(documentUrl: Uri, topUrl: Uri?, classes: List<String>, ids: List<String>): String
}

/** See [EngineAdBlocker.hidingStyles] */
data class PageHidingStyles(val css: String, val generic: Boolean) {
    companion object {
        val NONE = PageHidingStyles("", false)
    }
}
