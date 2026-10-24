package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate

/**
 * Everything a web engine reports about its tab or asks from the browser core:
 * page loading, navigation, windows and the data of the built-in home page.
 */
interface TabEngineEvents {
    fun onOpenInNewTabRequested(url: String, navigateImmediately: Boolean): WebEngine?
    fun onProgressChanged(newProgress: Int)
    fun onReceivedTitle(title: String)
    fun onReceivedIcon(icon: Bitmap)
    fun shouldOverrideUrlLoading(url: String): Boolean
    fun onPageStarted(url: String?)
    fun onPageFinished(url: String?)
    fun onPageCertificateError(url: String?)
    fun onBlockedAd(uri: String)
    fun onBlockedDialog(newTab: Boolean)
    fun onCreateWindow(dialog: Boolean, userGesture: Boolean): View?
    fun closeWindow(internalRepresentation: Any)
    fun onVisited(url: String)
    fun getHomePageLinks(): List<HomePageLink>
    fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int)
    /** The built-in home page has loaded; its search bar is at [x], [y] (pixels of the engine view) */
    fun onHomePageSearchBarLocated(x: Int, y: Int)
    /**
     * The engine lost its view (the process that drew it died) and dropped it: the core attaches a new one,
     * which [WebEngine.getOrCreateView] gives with what the engine shows after such a loss
     */
    fun onEngineViewLost()
}

/**
 * Content blocking and site view decisions the engine asks for. Ads are asked of
 * [WebEngineFactory.adBlocker], for every tab.
 */
interface EnginePolicies {
    fun isDialogsBlockingEnabled(): Boolean
    fun shouldBlockNewWindow(dialog: Boolean, userGesture: Boolean): Boolean
    /** The pages of the [url]'s host load in the desktop view (see [SiteViewSupport]) */
    fun isDesktopView(url: String): Boolean
}

/**
 * Things the engine needs from the UI that shows it: the activity, menus, dialogs,
 * fullscreen, permissions, file picking, downloads and other apps.
 */
interface EngineUiHost {
    fun getActivity(): Activity
    fun onDownloadRequested(request: DownloadRequest)
    /**
     * Asks the user for Android permissions. [onResult] gets granted state of each permission
     * (empty if the request was interrupted).
     */
    fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit)

    /**
     * Starts an app that picks files. [onResult] gets its result code and data.
     * @return false if there is no such app
     */
    fun pickFile(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit): Boolean
    fun onCopyTextToClipboardRequested(url: String)
    fun onShareUrlRequested(url: String)
    fun onOpenInExternalAppRequested(url: String)
    fun initiateVoiceSearch()
    fun onEditHomePageBookmarkSelected(index: Int)
    fun onPrepareForFullscreen()
    fun onExitFullscreen()

    /** The page scrolled vertically to [scrollY] by [dy] (in pixels of the engine view, positive downwards) */
    fun onPageScrolled(scrollY: Int, dy: Int) {}
    fun suggestActionsForLink(baseUri: String?, linkUri: String?, srcUri: String?,
                              title: String?, altText: String?, textContent: String?,
                              x: Int, y: Int)
    fun onContextMenu(
        cursorDrawer: CursorDrawerDelegate, baseUri: String?, linkUri: String?, srcUri: String?,
        title: String?, altText: String?,
        textContent: String?, x: Int, y: Int
    )
}

/**
 * What a web engine talks to while its tab is shown: the browser core ([events], [policies])
 * and the UI ([uiHost]). Each part is available separately; the object itself delegates all of them.
 */
class WebEngineCallbacks(
    val events: TabEngineEvents,
    val policies: EnginePolicies,
    val uiHost: EngineUiHost
) : TabEngineEvents by events, EnginePolicies by policies, EngineUiHost by uiHost
