package com.phlox.tvwebbrowser.browser

import android.content.Intent
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.HostConfig

/**
 * What the UI shows about the current tab.
 */
data class CurrentTabState(
    /** Changes when another tab becomes current (identity of the tab object) */
    val tabIdentity: Int = 0,
    val hasTab: Boolean = false,
    val url: String = "",
    /** The host of the page, "" if its url has none; its settings are the site settings */
    val host: String = "",
    val title: String = "",
    /** A page is loading: the UI offers to stop it instead of reloading */
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val adBlockEnabled: Boolean = false,
    val blockedAds: Int = 0,
    val blockedPopups: Int = 0,
    /** Popup blocking of the site, one of HostConfig.POPUP_BLOCK_* */
    val popupBlockLevel: Int = HostConfig.DEFAULT_BLOCK_POPUPS_VALUE,
    val certificateError: Boolean = false
)

/**
 * One-time things the UI should react to.
 */
sealed interface BrowserUiEvent {
    /** Page loading progress of the current tab, 0..100 */
    data class Progress(val value: Int) : BrowserUiEvent
    /** Title or icon of the tab changed */
    data class TabUpdated(val tab: BrowserTab) : BrowserUiEvent
    data class ThumbnailUpdated(val tab: BrowserTab) : BrowserUiEvent
    data class PopupBlocked(val newTab: Boolean) : BrowserUiEvent
    /** A page opened a new tab */
    data object TabOpenedByPage : BrowserUiEvent
    /** A page closed its tab */
    data object TabClosedByPage : BrowserUiEvent
    /** The url points to the local network and the app has no permission to access it */
    data object LocalNetworkPermissionNeeded : BrowserUiEvent
    /** "intent:" url */
    data class OpenInExternalApp(val url: String) : BrowserUiEvent
    /** A page wants to open an url with a non-web scheme, [intent] can handle it */
    data class AskToOpenInExternalApp(val url: String, val intent: Intent) : BrowserUiEvent
    data object NoAppToOpenUrl : BrowserUiEvent
}
