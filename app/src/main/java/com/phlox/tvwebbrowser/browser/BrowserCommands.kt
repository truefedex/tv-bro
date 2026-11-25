package com.phlox.tvwebbrowser.browser

/**
 * Browser actions that the user can trigger from anywhere: hotkeys, buttons, voice.
 * The UI shell implements them (the TV activity now; a phone shell can do it in its own way).
 */
interface BrowserCommands {
    /** Back in the history of the current tab (the shell decides what to do when there is none) */
    fun goBack()
    fun goForward()
    fun goHome()
    fun reload()
    fun startVoiceSearch()
    fun togglePlayback()
    fun stopPlayback()
    fun rewind()
    fun fastForward()
    /** Opens the search of text in the current page */
    fun findInPage()
    /** Scrolls a page up or down, as the Page Up and Page Down keys do where the focus is */
    fun scrollPage(up: Boolean)
    /** Opens a tab with the home page at the end */
    fun newTab()
    /** Closes the current tab (closing the only tab leaves one with the home page) */
    fun closeTab()
    /** Shows the tab [offset] places from the current one, going round the ends */
    fun switchTab(offset: Int)
}
