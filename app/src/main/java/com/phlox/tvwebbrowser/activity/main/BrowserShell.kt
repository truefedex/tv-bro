package com.phlox.tvwebbrowser.activity.main

import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.browser.BrowserCommands
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.utils.BackNavigationEventsAdapter
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate

/**
 * The look of the browser screen (the TV one now, a phone one later): its views and how they show
 * the browser. [MainActivity] keeps what does not depend on the look and gives it as [Host]:
 * the browser core, intents, navigation, incognito, downloads, keys and the screen's Back dispatcher.
 */
interface BrowserShell {
    /** The content of the activity */
    val root: View

    /** Holds the engine views of the tabs */
    val tabsContainer: ViewGroup

    /**
     * Adds the Back callbacks of the shell's overlays to the screen's [dispatcher] (the callback added
     * last goes first), with [fullscreen] (enabled while a page shows a fullscreen view) at its place among them.
     */
    fun registerBackCallbacks(dispatcher: OnBackPressedDispatcher, fullscreen: OnBackPressedCallback)

    /** Shows that the app is busy (loading the tabs, leaving the incognito mode) */
    fun showBusy(busy: Boolean)

    /** The saved tabs are loaded and the start tab is shown */
    fun onTabsLoaded()

    fun render(state: CurrentTabState)

    /** The events that concern the look; the activity handles the others (other apps, permissions) */
    fun onUiEvent(event: BrowserUiEvent)

    /** Back found no history in the current tab */
    fun onNoBackHistory()

    /** Something the user started will show a page (a new tab, a voice search): hide what covers it */
    fun showPage()

    fun onDownloadStarted()

    fun showContextMenu(
        tab: BrowserTab, uiHost: EngineUiHost, cursorDrawer: CursorDrawerDelegate,
        baseUri: String?, linkUri: String?, srcUri: String?,
        title: String?, altText: String?, textContent: String?, x: Int, y: Int
    )

    /**
     * What the shells get from the activity.
     */
    interface Host {
        val activity: AppCompatActivity
        val controller: BrowserController
        val settingsModel: SettingsModel
        /** Actions of hotkeys and buttons */
        val commands: BrowserCommands
        /** Turns Back events of keys, the system and game controllers into the screen's Back */
        val backEvents: BackNavigationEventsAdapter

        /** Loads the url in the current tab (in a new one if there is none) */
        fun navigate(url: String)
        /** Navigates to the url or searches the text */
        fun search(text: String)
        /** Opens the url in a new tab at [index] and shows it ([showPage]) */
        fun openInNewTab(url: String, index: Int): BrowserTab?
        /** Leaves the incognito mode if it is on and finishes the activity */
        fun closeWindow()
        fun toggleIncognitoMode()
        fun showDownloads()
        fun showHistory()
        fun showFavorites()
        fun showSettings()
    }
}
