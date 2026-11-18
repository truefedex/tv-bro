package com.phlox.tvwebbrowser.webengine

/**
 * How pages of a site are shown:
 * - [MOBILE]: as on a phone, the engine's own User-Agent and viewport.
 * - [TABLET]: as on an Android tablet: a User-Agent of Android without the sign of a phone and the wide viewport
 *   of the desktop view; sites give it their desktop pages mostly. Anti-bot checks (Cloudflare Turnstile) refuse a
 *   User-Agent of a desktop system in an engine that is Android in every other way, but not this one.
 * - [DESKTOP]: as on a desktop computer: a desktop User-Agent and a wide viewport.
 *
 * [code] is how the view of a host is stored (0 and 1 were false and true, when there were only two).
 */
enum class SiteView(val code: Int) {
    MOBILE(0),
    DESKTOP(1),
    TABLET(2);

    /** Pages get the wide viewport of the desktop view, and the cursor is a mouse */
    val wide: Boolean get() = this != MOBILE

    companion object {
        fun ofCode(code: Int?): SiteView? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A [WebEngine] that can show pages in each [SiteView]. The browser core gives each page the view of its host
 * ([EnginePolicies.siteViewOf]): it sets [view] (and [userAgentOverride]) before it loads a url and reloads the page
 * when the view of its host changes.
 * The engine itself switches the view for navigations of the page to another host, before their request
 * where it can.
 */
interface SiteViewSupport {
    /** The view pages load in; a change takes effect with the next load */
    var view: SiteView

    /**
     * The User-Agent of all pages in place of the one of the view (the view still sets the viewport), as the
     * user chose it; null gives the one of the view. A change takes effect with the next load.
     */
    var userAgentOverride: String?
}

/** Null if the engine shows pages only one way */
val WebEngine.siteView: SiteViewSupport?
    get() = this as? SiteViewSupport
