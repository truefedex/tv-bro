package com.phlox.tvwebbrowser.webengine

/**
 * A [WebEngine] that can show pages as on a desktop computer (a desktop User-Agent and a wide viewport)
 * or as on a phone. The browser core gives each page the view of its host ([EnginePolicies.isDesktopView]):
 * it sets [desktopView] before it loads a url and reloads the page when the view of its host changes.
 * The engine itself switches the view for navigations of the page to another host, before their request
 * where it can.
 */
interface SiteViewSupport {
    /** Pages load in the desktop view; a change takes effect with the next load */
    var desktopView: Boolean
}

/** Null if the engine shows pages only one way */
val WebEngine.siteView: SiteViewSupport?
    get() = this as? SiteViewSupport
